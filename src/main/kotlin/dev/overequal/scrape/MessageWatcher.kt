package dev.overequal.scrape

import dev.overequal.data.ChannelCursor
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for live [MessageCreateEvent]s and appends each message to the
 * on-disk cache, keeping the [dev.overequal.data.ChannelCursor] in
 * [dev.overequal.data.CacheMeta] consistent with the incremental scraper.
 *
 * The watcher only activates for guilds that already have a cache -- i.e. where
 * [MessageCache.hasCache] returns `true`. A guild must be scraped at least once
 * via `/scrape` before live updates begin.
 *
 * **Note:** if a message arrives in a channel the scraper has never visited, the
 * watcher creates a fresh [ChannelCursor] with `count = 1` and `newestId` set to
 * this message's snowflake. The next `/scrape` will resume from that cursor,
 * silently skipping all prior history in that channel. This is a deliberate
 * consequence of the scrape-first contract.
 */
class MessageWatcher(private val cache: MessageCache) {
    private val log = LoggerFactory.getLogger(MessageWatcher::class.java)

    /** Per-guild mutex protecting the meta read-modify-write cycle. */
    private val metaLocks = ConcurrentHashMap<String, Mutex>()

    private fun metaMutex(guildId: String): Mutex = metaLocks.getOrPut(guildId) { Mutex() }

    /**
     * Subscribes to [MessageCreateEvent]s on [gateway], launching a long-lived
     * collect loop inside [scope]. The function returns immediately; the coroutine
     * lifetime is owned by [scope].
     */
    fun watch(gateway: GatewayDiscordClient, scope: CoroutineScope) {
        scope.launch {
            gateway.on(MessageCreateEvent::class.java).asFlow().collect { ev ->
                val guildId = ev.guildId.orElse(null)?.asString() ?: return@collect
                if (!cache.hasCache(guildId)) return@collect
                runCatching { handle(ev, guildId) }
                    .onFailure { log.warn("watcher error: {}", it.message, it) }
            }
        }
    }

    private suspend fun handle(ev: MessageCreateEvent, guildId: String) {
        val message = ev.message
        val author = message.author.orElse(null) ?: return
        val ch = message.channel.awaitSingle() as? GuildMessageChannel ?: return
        val category = (ch as? CategorizableChannel)?.category?.awaitFirstOrNull()?.name
        val guildName = ev.guild.awaitSingle().name
        val mentions =
            message.userMentions.collectList().awaitSingle().map { u ->
                RawUser(u.id.asString(), u.username, u.globalName.orElse(null), u.isBot)
            }

        val rawMessage =
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

        cache.appendBatch(guildId, listOf(rawMessage))

        metaMutex(guildId).withLock {
            val existing = cache.meta(guildId)
            if (existing == null) {
                log.warn("meta missing for guild {} despite cache existing; skipping cursor update", guildId)
                return
            }
            val channelId = ch.id.asString()
            val msgSnowflake = message.id.asLong()
            val msgTimestamp = message.timestamp
            val existingCursor = existing.cursorFor(channelId)
            val updatedCursor =
                ChannelCursor(
                    channelId = channelId,
                    name = ch.name,
                    newestId = maxOf(existingCursor?.newestId?.toLong() ?: Long.MIN_VALUE, msgSnowflake).toString(),
                    count = (existingCursor?.count ?: 0) + 1,
                )
            val updatedChannels =
                existing.channels
                    .filter { it.channelId != channelId }
                    .plus(updatedCursor)
            val updatedLastTimestamp =
                if (existing.lastTimestamp == null) {
                    Time.isoString(msgTimestamp)
                } else {
                    val existingLast = runCatching { Time.parse(existing.lastTimestamp) }.getOrNull()
                    if (existingLast == null || msgTimestamp.isAfter(existingLast)) {
                        Time.isoString(msgTimestamp)
                    } else {
                        existing.lastTimestamp
                    }
                }
            val updatedFirstTimestamp =
                if (existing.firstTimestamp == null) {
                    Time.isoString(msgTimestamp)
                } else {
                    val existingFirst = runCatching { Time.parse(existing.firstTimestamp) }.getOrNull()
                    if (existingFirst == null || msgTimestamp.isBefore(existingFirst)) {
                        Time.isoString(msgTimestamp)
                    } else {
                        existing.firstTimestamp
                    }
                }
            val updatedMeta =
                existing.copy(
                    messageCount = existing.messageCount + 1,
                    firstTimestamp = updatedFirstTimestamp,
                    lastTimestamp = updatedLastTimestamp,
                    channels = updatedChannels,
                )
            cache.writeMeta(updatedMeta)
        }

        log.debug(
            "watched message {} in channel {} guild {}",
            message.id.asString(),
            ch.id.asString(),
            guildId,
        )
    }
}
