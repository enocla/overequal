package dev.overequal.scrape

import dev.overequal.data.CacheMeta
import dev.overequal.data.MessageCache
import dev.overequal.data.RawChannel
import dev.overequal.data.RawGuild
import dev.overequal.data.RawMessage
import dev.overequal.data.RawUser
import dev.overequal.data.Time
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.channel.CategorizableChannel
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for live [MessageCreateEvent]s and keeps the on-disk cache fresh between
 * `/scrape` runs, mirroring the incremental scraper's per-channel cursors.
 *
 * **Contract.** The watcher is a *freshness supplement*, not a discovery mechanism:
 * - It only touches guilds that already have a cache ([MessageCache.hasCache]).
 * - Within such a guild it only updates channels the scraper has already visited
 *   (i.e. that already have a [dev.overequal.data.ChannelCursor]). A message in a
 *   channel with no cursor — a channel created after the last scrape, an empty-at-
 *   scrape channel, a thread — is dropped; the next `/scrape` backfills that channel
 *   in full. This is what avoids the old data-loss bug where a watcher-minted cursor
 *   made the next scrape skip all prior history.
 *
 * **Batched & durable-by-recovery.** Incoming messages are buffered in memory and
 * flushed (one `appendBatch` + one `writeMeta`) every [FLUSH_INTERVAL_MS] or once a
 * guild's buffer reaches [FLUSH_SIZE], instead of rewriting `meta.json` per message.
 * A cursor only advances on flush, so anything buffered-but-not-yet-flushed (e.g. on
 * a crash) is simply re-fetched by the next `/scrape` via `getMessagesAfter`.
 *
 * **Scrape coordination.** While a `/scrape` owns a guild ([MessageCache.isScraping])
 * the flush is deferred — the scraper rebuilds meta from its own accumulator, so a
 * concurrent write here would be a lost update. The buffer drains once the scrape
 * ends; a snowflake floor (only messages strictly newer than the channel cursor are
 * kept) prevents double-counting anything the scrape already captured.
 */
class MessageWatcher(
    private val cache: MessageCache,
) {
    private val log = LoggerFactory.getLogger(MessageWatcher::class.java)

    /** Per-guild buffer of received-but-unflushed messages. Guarded by `synchronized(deque)`. */
    private val buffers = ConcurrentHashMap<String, ArrayDeque<Pending>>()

    /**
     * Subscribes to [MessageCreateEvent]s and launches the periodic flusher, both inside
     * [scope]. Returns immediately; the coroutine lifetimes are owned by [scope].
     */
    fun watch(
        gateway: GatewayDiscordClient,
        scope: CoroutineScope,
    ) {
        scope.launch {
            gateway.on(MessageCreateEvent::class.java).asFlow().collect { ev ->
                val guildId = ev.guildId.orElse(null)?.asString() ?: return@collect
                if (!cache.hasCache(guildId)) return@collect
                runCatching { receive(ev, guildId) }
                    .onFailure { log.warn("watcher receive error: {}", it.message, it) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                runCatching { flushAll() }
                    .onFailure { log.warn("watcher flush error: {}", it.message, it) }
            }
        }
    }

    /** Build the [RawMessage] (same mapping as the scraper) and buffer it. */
    private suspend fun receive(
        ev: MessageCreateEvent,
        guildId: String,
    ) {
        val message = ev.message
        val author = message.author.orElse(null) ?: return
        val ch = message.channel.awaitSingle() as? GuildMessageChannel ?: return
        val category = (ch as? CategorizableChannel)?.category?.awaitFirstOrNull()?.name
        val guildName = ev.guild.awaitSingle().name
        val mentions =
            message.userMentions.map { u ->
                RawUser(u.id.asString(), u.username, u.globalName.orElse(null), u.isBot)
            }

        val raw =
            RawMessage(
                id = message.id.asString(),
                type = message.type.name,
                timestamp = message.timestamp.toString(),
                content = message.content,
                author = RawUser(author.id.asString(), author.username, author.globalName.orElse(null), author.isBot),
                mentions = mentions,
                channel = RawChannel(ch.id.asString(), null, category, ch.name),
                guild = RawGuild(guildId, guildName),
            )

        val size = buffer(guildId, Pending(raw, ch.id.asString(), ch.name, message.id.asLong(), message.timestamp))
        if (size >= FLUSH_SIZE) flushGuild(guildId)
    }

    private fun buffer(
        guildId: String,
        pending: Pending,
    ): Int {
        val deque = buffers.computeIfAbsent(guildId) { ArrayDeque() }
        return synchronized(deque) {
            deque.addLast(pending)
            // Bound memory if a long scrape defers flushing: drop the oldest. Dropped
            // messages are re-fetched by the next /scrape (the cursor is unchanged).
            while (deque.size > MAX_BUFFER) deque.removeFirst()
            deque.size
        }
    }

    private fun drain(guildId: String): List<Pending> {
        val deque = buffers[guildId] ?: return emptyList()
        return synchronized(deque) {
            if (deque.isEmpty()) {
                emptyList()
            } else {
                val copy = ArrayList(deque)
                deque.clear()
                copy
            }
        }
    }

    private fun bufferSize(guildId: String): Int {
        val deque = buffers[guildId] ?: return 0
        return synchronized(deque) { deque.size }
    }

    private suspend fun flushAll() {
        for (guildId in buffers.keys) {
            if (bufferSize(guildId) == 0) continue
            flushGuild(guildId)
        }
    }

    /** Drain the buffer and persist it in one append + one meta write, under the meta lock. */
    private suspend fun flushGuild(guildId: String) {
        cache.withMetaLock(guildId) {
            // Defer while a scrape owns the meta; the buffer is retained for the next pass.
            if (cache.isScraping(guildId)) return@withMetaLock
            val existing = cache.meta(guildId) ?: return@withMetaLock
            val drained = drain(guildId)
            if (drained.isEmpty()) return@withMetaLock

            val result = mergeLiveBatch(existing, drained)
            if (result.appended.isEmpty()) return@withMetaLock
            cache.appendBatch(guildId, result.appended)
            cache.writeMeta(result.meta)
            log.debug("flushed {} live message(s) for guild {}", result.appended.size, guildId)
        }
    }

    companion object {
        /** Buffer drain cadence. */
        const val FLUSH_INTERVAL_MS = 3_000L

        /** Flush a guild eagerly once its buffer reaches this size, between intervals. */
        const val FLUSH_SIZE = 200

        /** Hard cap on a guild's buffer (only approached when a long scrape defers flushing). */
        const val MAX_BUFFER = 10_000
    }
}

/** A received-but-unflushed live message plus the fields the meta merge needs. */
internal data class Pending(
    val raw: RawMessage,
    val channelId: String,
    val channelName: String,
    val snowflake: Long,
    val timestamp: Instant,
)

/** Result of [mergeLiveBatch]: the new meta and exactly the messages to append to the JSONL. */
internal data class LiveMergeResult(
    val meta: CacheMeta,
    val appended: List<RawMessage>,
)

/**
 * Pure meta merge for a batch of live messages. Only advances channels that already
 * have a cursor (known to the scraper) and only counts messages strictly newer than
 * that cursor, so unknown channels and scrape-boundary overlaps are dropped. Returns
 * the updated meta and the subset of messages that should actually be appended.
 */
internal fun mergeLiveBatch(
    existing: CacheMeta,
    drained: List<Pending>,
): LiveMergeResult {
    val cursors = existing.channels.associateBy { it.channelId }.toMutableMap()
    val appended = ArrayList<RawMessage>()
    var first = existing.firstTimestamp?.let { runCatching { Time.parse(it) }.getOrNull() }
    var last = existing.lastTimestamp?.let { runCatching { Time.parse(it) }.getOrNull() }

    for ((channelId, msgs) in drained.groupBy { it.channelId }) {
        val cursor = cursors[channelId] ?: continue // known channels only
        val floor = cursor.newestId?.toLongOrNull() ?: Long.MIN_VALUE
        val fresh = msgs.filter { it.snowflake > floor }.sortedBy { it.snowflake }
        if (fresh.isEmpty()) continue

        appended += fresh.map { it.raw }
        cursors[channelId] =
            cursor.copy(
                newestId = fresh.maxOf { it.snowflake }.coerceAtLeast(floor).toString(),
                count = cursor.count + fresh.size,
            )
        for (p in fresh) {
            if (first == null || p.timestamp.isBefore(first)) first = p.timestamp
            if (last == null || p.timestamp.isAfter(last)) last = p.timestamp
        }
    }

    val meta =
        existing.copy(
            messageCount = existing.messageCount + appended.size,
            firstTimestamp = first?.let { Time.isoString(it) } ?: existing.firstTimestamp,
            lastTimestamp = last?.let { Time.isoString(it) } ?: existing.lastTimestamp,
            channels = cursors.values.toList(),
        )
    return LiveMergeResult(meta, appended)
}
