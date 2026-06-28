package dev.overequal.scrape

import dev.overequal.data.CacheMeta
import dev.overequal.data.ChannelCursor
import dev.overequal.data.RawChannel
import dev.overequal.data.RawMessage
import dev.overequal.data.RawUser
import dev.overequal.data.Time
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [mergeLiveBatch], the pure meta merge the live watcher flushes through. */
class LiveMergeTest {
    private fun pending(
        channelId: String,
        name: String,
        snowflake: Long,
        ts: Instant,
    ): Pending {
        val raw =
            RawMessage(
                id = snowflake.toString(),
                timestamp = ts.toString(),
                author = RawUser(id = "u", name = "u"),
                channel = RawChannel(channelId, null, null, name),
            )
        return Pending(raw, channelId, name, snowflake, ts)
    }

    /** Base meta: one known channel `c1` with cursor at snowflake 100, count 10, Jan 10–20. */
    private fun baseMeta() =
        CacheMeta(
            guildId = "g",
            guildName = "G",
            messageCount = 10,
            firstTimestamp = "2024-01-10T00:00:00Z",
            lastTimestamp = "2024-01-20T00:00:00Z",
            channels = listOf(ChannelCursor("c1", "general", newestId = "100", count = 10)),
            scrapedAtEpochSeconds = 0,
        )

    private fun instant(iso: String) = Time.parse(iso)

    @Test
    fun `known channel advances cursor count and period`() {
        val base = baseMeta()
        val result =
            mergeLiveBatch(
                base,
                listOf(
                    pending("c1", "general", 110, instant("2024-01-22T00:00:00Z")),
                    pending("c1", "general", 120, instant("2024-01-25T00:00:00Z")),
                ),
            )

        assertEquals(2, result.appended.size)
        assertEquals(12, result.meta.messageCount)
        val cursor = result.meta.cursorFor("c1")!!
        assertEquals("120", cursor.newestId)
        assertEquals(12, cursor.count)
        // lastTimestamp extends to the newest; first is unchanged.
        assertEquals(instant("2024-01-25T00:00:00Z"), Time.parse(result.meta.lastTimestamp!!))
        assertEquals(instant("2024-01-10T00:00:00Z"), Time.parse(result.meta.firstTimestamp!!))
    }

    @Test
    fun `unknown channel is dropped`() {
        val base = baseMeta()
        val result = mergeLiveBatch(base, listOf(pending("c2", "newroom", 500, instant("2024-02-01T00:00:00Z"))))

        assertTrue(result.appended.isEmpty(), "messages in a channel with no cursor are not appended")
        assertEquals(10, result.meta.messageCount)
        assertNull(result.meta.cursorFor("c2"), "no cursor is minted for an unscraped channel")
        assertEquals(1, result.meta.channels.size)
    }

    @Test
    fun `messages at or below the cursor are filtered out`() {
        val base = baseMeta()
        val result =
            mergeLiveBatch(
                base,
                listOf(
                    pending("c1", "general", 50, instant("2024-01-05T00:00:00Z")), // below cursor
                    pending("c1", "general", 100, instant("2024-01-19T00:00:00Z")), // equal to cursor
                    pending("c1", "general", 130, instant("2024-01-26T00:00:00Z")), // genuinely new
                ),
            )

        assertEquals(1, result.appended.size)
        assertEquals("130", result.appended.single().id)
        assertEquals(11, result.meta.messageCount)
        assertEquals("130", result.meta.cursorFor("c1")!!.newestId)
        // The Jan 5 message was filtered, so firstTimestamp must NOT have moved backward.
        assertEquals(instant("2024-01-10T00:00:00Z"), Time.parse(result.meta.firstTimestamp!!))
    }

    @Test
    fun `firstTimestamp extends backward for a kept older message`() {
        val base = baseMeta()
        // Snowflake is above the cursor (kept) but timestamp predates the current first.
        val result = mergeLiveBatch(base, listOf(pending("c1", "general", 140, instant("2024-01-01T00:00:00Z"))))

        assertEquals(1, result.appended.size)
        assertEquals(instant("2024-01-01T00:00:00Z"), Time.parse(result.meta.firstTimestamp!!))
        assertEquals(instant("2024-01-20T00:00:00Z"), Time.parse(result.meta.lastTimestamp!!))
    }
}
