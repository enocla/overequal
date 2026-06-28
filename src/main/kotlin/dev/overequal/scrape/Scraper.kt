package dev.overequal.scrape

import dev.overequal.data.CacheMeta
import dev.overequal.data.ChannelCursor
import dev.overequal.data.MessageCache
import dev.overequal.data.RawChannel
import dev.overequal.data.RawGuild
import dev.overequal.data.RawMessage
import dev.overequal.data.RawUser
import dev.overequal.data.Time
import discord4j.common.util.Snowflake
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.CategorizableChannel
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Scrapes a guild's text channels into the [MessageCache]. Generalizes across
 * servers: it walks every readable text channel (optionally one named channel).
 *
 * **Incremental & durable.** Each channel keeps a [ChannelCursor] (its newest
 * message snowflake) in the cache metadata. A first scrape backfills history; a
 * later run fetches only messages *after* the cursor (`getMessagesAfter`), so we
 * never re-download what we already have. Messages are flushed to disk in batches
 * and the metadata is rewritten after every flush, so progress is durable (an
 * interrupted run keeps everything already written and resumes from the cursor)
 * and memory stays bounded regardless of corpus size.
 *
 * Forward-only by design: an interrupted *initial backfill* is not resumed from
 * its oldest point — the next run only picks up messages newer than the newest
 * one already cached. The reader de-duplicates by id, so any boundary overlap is
 * harmless.
 */
class Scraper(
    private val cache: MessageCache,
    private val ratePerSecond: Double = DEFAULT_RATE_PER_SECOND,
) {
    private val log = LoggerFactory.getLogger(Scraper::class.java)

    suspend fun scrape(
        guild: Guild,
        channelName: String? = null,
        limit: Int? = null,
        onProgress: (suspend (channel: String, total: Int) -> Unit)? = null,
    ): CacheMeta {
        val guildId = guild.id.asString()
        // Own the guild's meta for the whole run: the live watcher defers its flush while a
        // scrape is in flight (MessageCache.isScraping) so it can't clobber the meta this
        // rebuilds from its in-memory accumulator.
        cache.beginScrape(guildId)
        try {
            val limiter = RateLimiter(ratePerSecond)
            log.info(
                "scraping guild {} at {}",
                guild.name,
                if (limiter.unlimited) "unlimited rate" else "$ratePerSecond req/s (~${(ratePerSecond * PAGE_SIZE).toInt()} msg/s)",
            )
            val channels =
                guild
                    .channels
                    .collectList()
                    .awaitSingle()
                    .filterIsInstance<GuildMessageChannel>()
                    .filter { channelName == null || it.name.equals(channelName, ignoreCase = true) }

            // Resume from existing metadata when present; otherwise start fresh (and
            // clear any stale corpus so a full backfill doesn't double up on disk).
            val prior = cache.meta(guildId)
            if (prior == null) cache.truncate(guildId)

            val state = ScrapeState(guildId, guild.name, prior)
            var remaining = limit ?: Int.MAX_VALUE

            for (ch in channels) {
                if (remaining <= 0) break
                val category = (ch as? CategorizableChannel)?.category?.awaitFirstOrNull()?.name
                val cursor = prior?.cursorFor(ch.id.asString())

                val added =
                    runCatching { scrapeChannel(ch, category, guild, cursor, remaining, state, limiter, onProgress) }
                        .getOrElse {
                            // Missing read permission (or a transient error) on a channel is
                            // expected; skip it. Anything flushed so far is already durable.
                            log.warn("skipping #{}: {}", ch.name, it.message)
                            0
                        }
                remaining -= added
            }

            val meta = state.toMeta()
            cache.withMetaLock(guildId) { cache.writeMeta(meta) }
            log.info("cache now holds {} messages for guild {} ({})", meta.messageCount, guild.name, guildId)
            return meta
        } finally {
            cache.endScrape(guildId)
        }
    }

    /** Stream one channel, flushing batches; returns how many new messages were added. */
    private suspend fun scrapeChannel(
        ch: GuildMessageChannel,
        category: String?,
        guild: Guild,
        cursor: ChannelCursor?,
        remaining: Int,
        state: ScrapeState,
        limiter: RateLimiter,
        onProgress: (suspend (channel: String, total: Int) -> Unit)?,
    ): Int {
        // With a cursor: only messages newer than the newest one cached (incremental,
        // ascending). Without: full history backfill (descending from now).
        val flow: Flow<Message> =
            if (cursor?.newestId != null) {
                ch.getMessagesAfter(Snowflake.of(cursor.newestId)).asFlow()
            } else {
                ch.getMessagesBefore(Snowflake.of(Instant.now())).asFlow()
            }

        val batch = ArrayList<RawMessage>(BATCH_SIZE)
        var added = 0
        var consumed = 0
        var newestId: Long = cursor?.newestId?.toLong() ?: Long.MIN_VALUE

        suspend fun flush() {
            if (batch.isEmpty()) return
            cache.appendBatch(state.guildId, batch)
            state.record(ch.id.asString(), ch.name, batch.size, newestId)
            cache.withMetaLock(state.guildId) { cache.writeMeta(state.toMeta()) }
            batch.clear()
            onProgress?.invoke(ch.name, state.totalCount)
        }

        flow.collect { m ->
            if (added >= remaining) return@collect
            // One permit per page of history (Discord4J fetches PAGE_SIZE per REST
            // call). Suspending here applies backpressure to the underlying Flux, so
            // it paces the actual requests, not just our consumption.
            if (consumed % PAGE_SIZE == 0) limiter.acquire()
            consumed++
            val author = m.author.orElse(null) ?: return@collect
            batch.add(
                RawMessage(
                    id = m.id.asString(),
                    type = m.type.name,
                    timestamp = m.timestamp.toString(),
                    content = m.content,
                    author = author.toRaw(),
                    mentions = m.userMentions.map { it.toRaw() },
                    channel = RawChannel(ch.id.asString(), null, category, ch.name),
                    guild = RawGuild(guild.id.asString(), guild.name),
                ),
            )
            newestId = maxOf(newestId, m.id.asLong())
            state.fold(m.timestamp)
            added++
            if (batch.size >= BATCH_SIZE) flush()
        }
        flush()
        if (added > 0) log.info("scraped #{}: +{} messages (total {})", ch.name, added, state.totalCount)
        return added
    }

    /**
     * Running cache state across a scrape: per-channel cursors, total count and the
     * overall time span, all seeded from prior metadata so increments accumulate.
     */
    private class ScrapeState(
        val guildId: String,
        val guildName: String,
        prior: CacheMeta?,
    ) {
        private val cursors = LinkedHashMap<String, ChannelCursor>()
        var totalCount: Int = prior?.messageCount ?: 0
            private set
        private var first: Instant? = prior?.firstTimestamp?.let { runCatching { Time.parse(it) }.getOrNull() }
        private var last: Instant? = prior?.lastTimestamp?.let { runCatching { Time.parse(it) }.getOrNull() }

        init {
            prior?.channels?.forEach { cursors[it.channelId] = it }
        }

        fun fold(ts: Instant) {
            if (first == null || ts.isBefore(first)) first = ts
            if (last == null || ts.isAfter(last)) last = ts
        }

        /** Apply a flushed batch: bump the channel cursor and the running total. */
        fun record(
            channelId: String,
            name: String,
            batchSize: Int,
            newestId: Long,
        ) {
            val existing = cursors[channelId]
            val count = (existing?.count ?: 0) + batchSize
            cursors[channelId] =
                ChannelCursor(
                    channelId = channelId,
                    name = name,
                    newestId = if (newestId == Long.MIN_VALUE) existing?.newestId else newestId.toString(),
                    count = count,
                )
            totalCount += batchSize
        }

        fun toMeta(): CacheMeta =
            CacheMeta(
                guildId = guildId,
                guildName = guildName,
                messageCount = totalCount,
                firstTimestamp = first?.let { Time.isoString(it) },
                lastTimestamp = last?.let { Time.isoString(it) },
                channels = cursors.values.toList(),
                scrapedAtEpochSeconds = Instant.now().epochSecond,
            )
    }

    private fun discord4j.core.`object`.entity.User.toRaw(): RawUser = RawUser(id.asString(), username, globalName.orElse(null), isBot)

    companion object {
        /** Messages buffered in memory before a flush to disk + metadata rewrite. */
        private const val BATCH_SIZE = 500

        /** Discord4J fetches message history this many per REST request. */
        const val PAGE_SIZE = 100

        /**
         * Default scrape pace: 1 history request/second (~100 messages/s given
         * [PAGE_SIZE]). Overridable via config; a non-positive value is unlimited.
         */
        const val DEFAULT_RATE_PER_SECOND = 1.0
    }
}
