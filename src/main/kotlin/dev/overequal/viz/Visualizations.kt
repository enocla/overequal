package dev.overequal.viz

import dev.overequal.data.Dataset
import dev.overequal.viz.impl.CltDaily
import dev.overequal.viz.impl.CumulativeAbsolute
import dev.overequal.viz.impl.CumulativeShare
import dev.overequal.viz.impl.Hourly
import dev.overequal.viz.impl.HourlyGrid
import dev.overequal.viz.impl.MentionRatio
import dev.overequal.viz.impl.MentionScatter
import dev.overequal.viz.impl.Mentions
import dev.overequal.viz.impl.MessageLength
import dev.overequal.viz.impl.MessageLengthPerMember
import dev.overequal.viz.impl.MessagesPerDay
import dev.overequal.viz.impl.SharePie
import dev.overequal.viz.impl.SpreadDistribution
import dev.overequal.viz.impl.SpreadVsRate
import dev.overequal.viz.impl.Timeline
import dev.overequal.viz.impl.Top30
import dev.overequal.viz.impl.Top30Words
import dev.overequal.viz.impl.UniqueMembers
import dev.overequal.viz.impl.Weekly
import dev.overequal.viz.impl.WeeklyRate

/** The registry of all visualizations the bot/CLI can render (and "run all"). */
object Visualizations {
    val all: List<Visualization> =
        listOf(
            Top30,
            WeeklyRate,
            SharePie,
            MentionRatio,
            Hourly,
            MessageLength,
            MessagesPerDay,
            CltDaily,
            MessageLengthPerMember,
            UniqueMembers,
            MentionScatter,
            SpreadVsRate,
            SpreadDistribution,
            Mentions,
            HourlyGrid,
            Timeline,
            Weekly,
            CumulativeShare,
            CumulativeAbsolute,
            Top30Words,
        )

    val byId: Map<String, Visualization> = all.associateBy { it.id }

    fun ids(): List<String> = all.map { it.id }

    /**
     * Render [viz] against [ds], honouring content redaction: a chart that needs
     * message text is skipped when the dataset's content is redacted.
     */
    fun render(
        viz: Visualization,
        ds: Dataset,
    ): ByteArray? {
        if (viz.requiresContent && ds.contentRedacted) return null
        return viz.render(ds)
    }
}
