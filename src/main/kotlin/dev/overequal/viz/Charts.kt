package dev.overequal.viz

import dev.overequal.data.Dataset
import dev.overequal.viz.ChartStyle.standard
import org.jetbrains.kotlinx.kandy.dsl.categorical
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.ir.Plot
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.barsH
import org.jetbrains.kotlinx.kandy.util.color.Color

/**
 * Reusable Kandy chart builders carrying the Flexoki look. Kept here so each
 * visualization is just data prep + one call.
 */
object Charts {
    /**
     * Horizontal bars with per-bar colours. [labels]/[values]/[colors] are given
     * in **top-to-bottom display order** (most important first); the categorical
     * y order is set so the first row sits at the top (config rule 10).
     */
    fun horizontalBars(
        ds: Dataset,
        title: String,
        xLabel: String,
        labels: List<String>,
        values: List<Double>,
        colors: List<Color>,
        yLabel: String = "",
        width: Int = 1100,
        height: Int = 900,
    ): Plot {
        // Lets-Plot draws the first y category at the bottom, so reverse to put
        // labels[0] at the top.
        val order = labels.asReversed()
        return plot {
            barsH {
                y(labels) {
                    scale = categorical(categories = order)
                    axis.name = yLabel
                }
                x(values) { axis.name = xLabel }
                fillColor(labels) {
                    scale = categorical(*labels.zip(colors).toTypedArray())
                }
            }
            layout { standard(title, ds, width, height) }
        }
    }
}
