package dev.overequal.viz

import dev.overequal.data.Dataset
import org.jetbrains.kotlinx.kandy.letsplot.feature.Layout
import org.jetbrains.kotlinx.kandy.letsplot.style.LegendPosition
import org.jetbrains.kotlinx.kandy.letsplot.style.Style
import org.jetbrains.kotlinx.kandy.util.context.invoke

/**
 * Shared Flexoki look for every chart, applied inside a `layout { }` block:
 * paper canvas + panel, ink text, faint major grid lines, no minor grid, and the
 * legend hidden by default (per-bar frequency gradients carry no legend in the
 * reference). Built on `Style.None` so nothing from a base theme leaks through.
 */
object ChartStyle {
    fun Layout.flexoki(showLegend: Boolean = false) {
        style(Style.None) {
            global {
                background {
                    fillColor = Theme.PAPER
                    borderLineColor = Theme.PAPER
                    borderLineWidth = 0.0
                }
                text { color = Theme.BLACK }
            }
            plotCanvas {
                background {
                    fillColor = Theme.PAPER
                    borderLineColor = Theme.PAPER
                    borderLineWidth = 0.0
                }
            }
            panel.grid {
                majorLine {
                    color = Theme.GRID
                    width = 0.4
                }
                minorLine { blank = true }
            }
            legend {
                position = if (showLegend) LegendPosition.Right else LegendPosition.None
            }
        }
    }

    /**
     * Apply the title, the dataset's time-period subtitle (so every chart reflects
     * the data's period, per the requirement), size and Flexoki style in one call.
     */
    fun Layout.standard(
        title: String,
        ds: Dataset,
        width: Int = 1100,
        height: Int = 850,
        showLegend: Boolean = false,
    ) {
        this.title = title
        this.subtitle = ds.subtitle()
        this.size = width to height
        flexoki(showLegend)
    }
}
