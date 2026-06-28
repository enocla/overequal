package dev.overequal.viz.impl

import dev.overequal.data.Dataset
import dev.overequal.data.Time
import dev.overequal.viz.Charts
import dev.overequal.viz.Theme
import dev.overequal.viz.Visualization
import dev.overequal.viz.toPngBytes
import org.jetbrains.kotlinx.kandy.util.color.Color
import kotlin.math.ln

private const val OTHERS = "Others"

/** Distinct hue per member keyed by activity rank (matches the timeline scheme). */
private fun memberColors(top: List<String>): Map<String, Color> = top.withIndex().associate { (i, name) -> name to Theme.distinct(i) }

/** Weekly messages by the top 30 members, stacked bars over time. */
object Weekly : Visualization {
    override val id = "weekly"
    override val title = "Weekly Messages by Top 30 Members"
    override val description = "Stacked weekly message volume of the most active members."

    override fun render(ds: Dataset): ByteArray? {
        val top = topAuthors(ds, 30)
        if (top.isEmpty()) return null
        val set = top.toHashSet()
        val perWeek = HashMap<Pair<String, java.time.LocalDate>, Int>()
        for (m in ds.messages) {
            if (m.authorName !in set) continue
            perWeek.merge(m.authorName to Time.weekStart(m.timestamp), 1, Int::plus)
        }
        // Emit in bottom->top order (least active first) so the stack puts the most
        // active member on top; lets-plot stacks in data-appearance order.
        val order = top.asReversed()
        val weeks = perWeek.keys.map { it.second }.toSortedSet()
        val x = ArrayList<Double>()
        val y = ArrayList<Double>()
        val g = ArrayList<String>()
        for (name in order) {
            for (wk in weeks) {
                val c = perWeek[name to wk] ?: continue
                x.add(Time.yearFraction(wk))
                y.add(c.toDouble())
                g.add(name)
            }
        }
        if (x.isEmpty()) return null
        return Charts
            .stacked(
                ds = ds,
                title = title,
                xLabel = "Week",
                yLabel = "Messages",
                x = x,
                y = y,
                group = g,
                groupOrder = order,
                colors = memberColors(top),
                asArea = false,
            ).toPngBytes()
    }
}

/** Cumulative share (%) of all messages by author over time, stacked area. */
object CumulativeShare : Visualization {
    override val id = "cumulative_share"
    override val title = "Cumulative Share of Messages by Author"
    override val description = "How the share of total messages was split as it accumulated."

    override fun render(ds: Dataset): ByteArray? = cumulative(ds, title, percent = true)
}

/** Cumulative absolute messages by the top 30 members over time, stacked area. */
object CumulativeAbsolute : Visualization {
    override val id = "cumulative_absolute"
    override val title = "Cumulative Messages by Top 30 Members"
    override val description = "Running total of messages per member over time."

    override fun render(ds: Dataset): ByteArray? = cumulative(ds, title, percent = false)
}

private fun cumulative(
    ds: Dataset,
    title: String,
    percent: Boolean,
): ByteArray? {
    val top = topAuthors(ds, 30)
    if (top.isEmpty()) return null
    val set = top.toHashSet()
    val weeks = sortedSetOf<java.time.LocalDate>()
    val perWeek = HashMap<Pair<String, java.time.LocalDate>, Int>()
    for (m in ds.messages) {
        val wk = Time.weekStart(m.timestamp)
        weeks.add(wk)
        val grp = if (m.authorName in set) m.authorName else OTHERS
        perWeek.merge(grp to wk, 1, Int::plus)
    }
    if (weeks.isEmpty()) return null

    val groups = if (percent) top + OTHERS else top
    // bottom->top stack order (data-appearance order): Others at the very bottom,
    // then least active up to most active on top.
    val order = (if (percent) listOf(OTHERS) else emptyList()) + top.asReversed()
    val running = HashMap<String, Int>().apply { groups.forEach { put(it, 0) } }
    val x = ArrayList<Double>()
    val y = ArrayList<Double>()
    val g = ArrayList<String>()
    for (wk in weeks) {
        for (grp in groups) running.merge(grp, perWeek[grp to wk] ?: 0, Int::plus)
        val total = if (percent) running.values.sum().coerceAtLeast(1) else 1
        val xf = Time.yearFraction(wk)
        for (grp in order) {
            x.add(xf)
            y.add(if (percent) running.getValue(grp) * 100.0 / total else running.getValue(grp).toDouble())
            g.add(grp)
        }
    }

    val colors = memberColors(top) + (OTHERS to Theme.GRAY.getValue(300))
    val groupOrder = order
    return Charts
        .stacked(
            ds = ds,
            title = title,
            xLabel = "Week",
            yLabel = if (percent) "Cumulative share of all messages (%)" else "Cumulative messages",
            x = x,
            y = y,
            group = g,
            groupOrder = groupOrder,
            colors = colors,
            asArea = true,
        ).toPngBytes()
}

/** Hourly spread (round-the-clock evenness) of the top 30 members, blue bars. */
object SpreadDistribution : Visualization {
    override val id = "spread_distribution"
    override val title = "Hourly Spread Among the Top 30 Members"
    override val description = "Round-the-clock evenness (1.0 = uniform across 24 hours)."

    override fun render(ds: Dataset): ByteArray? {
        val top = topAuthors(ds, 30)
        if (top.isEmpty()) return null
        val hours = HashMap<String, IntArray>()
        for (m in ds.messages) {
            if (m.authorName !in top) continue
            hours.getOrPut(m.authorName) { IntArray(24) }[Time.hour(m.timestamp)]++
        }
        val maxEntropy = ln(24.0) / ln(2.0)
        val rows =
            top
                .map { it to entropyBits(hours[it] ?: IntArray(24)) / maxEntropy }
                .sortedByDescending { it.second } // most spread first (top)
        val labels = rows.map { it.first }
        val values = rows.map { it.second }
        val colors = Theme.gradient(Theme.BLUE, labels.size).asReversed()
        return Charts
            .horizontalBars(
                ds = ds,
                title = title,
                xLabel = "Spread (round-the-clock evenness, 0–1)",
                labels = labels,
                values = values,
                colors = colors,
                yLabel = "Member (most spread at top)",
            ).toPngBytes()
    }
}
