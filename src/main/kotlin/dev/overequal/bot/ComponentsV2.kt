package dev.overequal.bot

import dev.overequal.viz.Visualization
import discord4j.core.`object`.component.ActionRow
import discord4j.core.`object`.component.Button
import discord4j.core.`object`.component.Container
import discord4j.core.`object`.component.File
import discord4j.core.`object`.component.ICanBeUsedInContainerComponent
import discord4j.core.`object`.component.MediaGallery
import discord4j.core.`object`.component.MediaGalleryItem
import discord4j.core.`object`.component.Separator
import discord4j.core.`object`.component.TextDisplay
import discord4j.core.`object`.component.TopLevelMessageComponent
import discord4j.core.`object`.component.UnfurledMediaItem
import discord4j.core.spec.MessageCreateFields
import discord4j.rest.util.Color
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/** A ready-to-send Components V2 payload: top-level components + their file uploads. */
data class V2Message(
    val components: List<TopLevelMessageComponent>,
    val files: List<MessageCreateFields.File>,
)

/**
 * Builders for the bot's Components V2 output. Charts are sent as a [Container]
 * (accent-coloured) holding a [TextDisplay] heading and a [MediaGallery] that
 * references the uploaded PNG via `attachment://`.
 */
object ComponentsV2 {
    private val ACCENT = Color.of(0x20, 0x5E, 0xA6) // Flexoki blue-600

    private val fileCounter = AtomicInteger()

    private fun freshName(id: String): String = "${id}_${fileCounter.getAndIncrement()}.png"

    /** One chart as its own container + file. */
    fun chart(
        viz: Visualization,
        png: ByteArray,
    ): V2Message = chart(viz, png, header = null)

    private fun chart(
        viz: Visualization,
        png: ByteArray,
        header: String?,
    ): V2Message {
        val name = freshName(viz.id)
        val file = MessageCreateFields.File.of(name, ByteArrayInputStream(png))
        val text =
            if (header == null) {
                "## ${viz.title}\n${viz.description}"
            } else {
                "$header\n\n### ${viz.title}\n${viz.description}"
            }
        val children: List<ICanBeUsedInContainerComponent> =
            listOf(
                TextDisplay.of(text),
                MediaGallery.of(MediaGalleryItem.of(UnfurledMediaItem.of("attachment://$name"))),
            )
        return V2Message(listOf(Container.of(ACCENT, children)), listOf(file))
    }

    /** Several charts batched into one message (one container each). */
    fun charts(items: List<Pair<Visualization, ByteArray>>): V2Message {
        val components = ArrayList<TopLevelMessageComponent>()
        val files = ArrayList<MessageCreateFields.File>()
        for ((viz, png) in items) {
            val one = chart(viz, png)
            components.addAll(one.components)
            files.addAll(one.files)
        }
        return V2Message(components, files)
    }

    /** One `/viz-all` page: up to four chart containers plus a navigation row. */
    fun chartsPage(
        guildName: String,
        periodLabel: String,
        totalCharts: Int,
        pageIndex: Int,
        pageCount: Int,
        items: List<Pair<Visualization, ByteArray>>,
        firstCustomId: String,
        previousCustomId: String,
        currentCustomId: String,
        nextCustomId: String,
        lastCustomId: String,
    ): V2Message {
        val components = ArrayList<TopLevelMessageComponent>()
        val files = ArrayList<MessageCreateFields.File>()
        val header =
            "## $guildName — $totalCharts visualizations\n" +
                "$periodLabel\n" +
                "Page ${pageIndex + 1} of $pageCount"

        for ((index, item) in items.withIndex()) {
            val one = chart(item.first, item.second, header = header.takeIf { index == 0 })
            components.addAll(one.components)
            files.addAll(one.files)
        }

        components.add(
            ActionRow.of(
                Button.secondary(firstCustomId, "First").disabled(pageIndex == 0),
                Button.secondary(previousCustomId, "Previous").disabled(pageIndex == 0),
                Button.secondary(currentCustomId, "Page ${pageIndex + 1}/$pageCount").disabled(),
                Button.primary(nextCustomId, "Next").disabled(pageIndex == pageCount - 1),
                Button.primary(lastCustomId, "Last").disabled(pageIndex == pageCount - 1),
            ),
        )
        return V2Message(components, files)
    }

    /** A plain text notice (status, errors, headers) as a single container. */
    fun notice(
        markdown: String,
        accent: Color = ACCENT,
    ): V2Message {
        val children: List<ICanBeUsedInContainerComponent> =
            listOf(TextDisplay.of(markdown), Separator.of())
        return V2Message(listOf(Container.of(accent, children)), emptyList())
    }

    /**
     * A text notice plus a downloadable file attachment. The [bytes] are uploaded
     * under [filename] and referenced from a Components V2 [File] component so the
     * client shows it as a native, downloadable attachment (not an image).
     */
    fun fileNotice(
        markdown: String,
        filename: String,
        bytes: ByteArray,
        accent: Color = ACCENT,
    ): V2Message {
        val upload = MessageCreateFields.File.of(filename, ByteArrayInputStream(bytes))
        val children: List<ICanBeUsedInContainerComponent> =
            listOf(
                TextDisplay.of(markdown),
                File.of(UnfurledMediaItem.of("attachment://$filename")),
            )
        return V2Message(listOf(Container.of(accent, children)), listOf(upload))
    }
}
