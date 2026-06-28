package dev.overequal.viz

import dev.overequal.data.Dataset
import org.jetbrains.kotlinx.kandy.letsplot.feature.Layout
import org.jetbrains.kotlinx.kandy.letsplot.settings.font.FontFamily
import org.jetbrains.kotlinx.kandy.letsplot.style.LegendPosition
import org.jetbrains.kotlinx.kandy.letsplot.style.Style
import org.jetbrains.kotlinx.kandy.util.context.invoke

/**
 * Shared Flexoki look for every chart, applied inside a `layout { }` block:
 * paper canvas + panel, ink text in IBM Plex Sans, faint major grid lines, no minor
 * grid, fixed padding around the plotting area, and the legend hidden by default
 * (per-bar frequency gradients carry no legend in the reference). Built on
 * `Style.None` so nothing from a base theme leaks through.
 */
object ChartStyle {
    /** Fixed breathing room (px) between the plotting area and the canvas edges. */
    private const val PAD = 28.0

    fun Layout.flexoki(
        showLegend: Boolean = false,
        blankAxes: Boolean = false,
    ) {
        val fontFamily = FontFamily.custom(Fonts.sans)
        // Pie/donut charts use Style.Void (no axes/grid); everything else builds on
        // Style.None and draws a faint major grid.
        style(if (blankAxes) Style.Void else Style.None) {
            global {
                background {
                    fillColor = Theme.PAPER
                    borderLineColor = Theme.PAPER
                    borderLineWidth = 0.0
                }
                text {
                    color = Theme.BLACK
                    this.fontFamily = fontFamily
                }
                title { this.fontFamily = fontFamily }
            }
            plotCanvas {
                background {
                    fillColor = Theme.PAPER
                    borderLineColor = Theme.PAPER
                    borderLineWidth = 0.0
                }
                // Fixed padding between the plot panel and the canvas edges, so titles,
                // axis labels and bars never crowd the border.
                inset(PAD, PAD, PAD, PAD)
            }
            if (!blankAxes) {
                panel.grid {
                    majorLine {
                        color = Theme.GRID
                        width = 0.4
                    }
                    minorLine { blank = true }
                }
                // Lets-Plot defaults the axis lines/ticks to a blue; make them ink.
                axis {
                    line {
                        color = Theme.BLACK
                        width = 0.6
                    }
                    ticks {
                        color = Theme.BLACK
                        width = 0.6
                    }
                }
            }
            legend {
                position = if (showLegend) LegendPosition.Right else LegendPosition.None
            }
            if (blankAxes) blankAxes()
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
        blankAxes: Boolean = false,
    ) {
        this.title = title
        this.subtitle = ds.subtitle()
        this.size = width to height
        flexoki(showLegend, blankAxes)
    }
}
