package dev.overequal.viz.impl

import dev.overequal.data.Dataset
import dev.overequal.viz.Charts
import dev.overequal.viz.Theme
import dev.overequal.viz.Visualization
import dev.overequal.viz.toPngBytes

/** Messages by member, top 30, as a blue frequency-gradient horizontal bar chart. */
object Top30 : Visualization {
    override val id = "top30"
    override val title = "Messages by Member (Top 30)"
    override val description = "The 30 most active members by message count."

    override fun render(ds: Dataset): ByteArray? {
        val top =
            ds.messages
                .groupingBy { it.authorName }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(30)
        if (top.isEmpty()) return null

        val labels = top.map { it.key } // most active first (top of chart)
        val values = top.map { it.value.toDouble() }
        // gradient() is light->dark; reverse so the most active row is darkest.
        val colors = Theme.gradient(Theme.BLUE, labels.size).asReversed()

        return Charts
            .horizontalBars(
                ds = ds,
                title = title,
                xLabel = "Messages",
                yLabel = "Member",
                labels = labels,
                values = values,
                colors = colors,
            ).toPngBytes()
    }
}
