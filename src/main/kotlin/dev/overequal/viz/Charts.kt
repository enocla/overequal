package dev.overequal.viz

import dev.overequal.data.Dataset
import dev.overequal.viz.ChartStyle.standard
import org.jetbrains.kotlinx.kandy.dsl.categorical
import org.jetbrains.kotlinx.kandy.dsl.continuous
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.ir.Plot
import org.jetbrains.kotlinx.kandy.letsplot.feature.Position
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.feature.position
import org.jetbrains.kotlinx.kandy.letsplot.layers.area
import org.jetbrains.kotlinx.kandy.letsplot.layers.bars
import org.jetbrains.kotlinx.kandy.letsplot.layers.barsH
import org.jetbrains.kotlinx.kandy.letsplot.layers.pie
import org.jetbrains.kotlinx.kandy.letsplot.layers.points
import org.jetbrains.kotlinx.kandy.letsplot.layers.tiles
import org.jetbrains.kotlinx.kandy.util.color.Color
import kotlin.math.ceil
import kotlin.math.floor

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

    /**
     * Vertical bars over a continuous x, coloured by a frequency gradient within
     * one [hue] (darker = larger, config rule 7). Used for the hourly chart and
     * the histograms.
     */
    fun frequencyBars(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        x: List<Double>,
        y: List<Double>,
        hue: Map<Int, Color>,
        width: Int = 1300,
        height: Int = 760,
    ): Plot =
        plot {
            bars {
                x(x) { axis.name = xLabel }
                y(y) { axis.name = yLabel }
                fillColor(y) {
                    scale = continuous(range = hue.getValue(200)..hue.getValue(800))
                }
            }
            layout { standard(title, ds, width, height) }
        }

    /** A line with a faint filled area beneath it (cumulative-over-time charts). */
    fun lineArea(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        x: List<Double>,
        y: List<Double>,
        color: Color,
        width: Int = 1100,
        height: Int = 620,
    ): Plot =
        plot {
            area {
                x(x) {
                    axis.name = xLabel
                    val years = ceil(x.min()).toInt()..floor(x.max()).toInt()
                    if (years.first <= years.last) {
                        axis.breaksLabeled(years.map { it.toDouble() }, years.map { it.toString() })
                    }
                }
                y(y) { axis.name = yLabel }
                fillColor = color
                alpha = 0.12
                borderLine.color = color
                borderLine.width = 2.0
            }
            layout { standard(title, ds, width, height) }
        }

    /** A donut chart with leader-free categorical slices. */
    fun donut(
        ds: Dataset,
        title: String,
        labels: List<String>,
        values: List<Double>,
        colors: List<Color>,
        showLegend: Boolean = true,
        width: Int = 1000,
        height: Int = 900,
    ): Plot =
        plot {
            pie {
                slice(values)
                fillColor(labels) {
                    scale = categorical(*labels.zip(colors).toTypedArray())
                }
                size = 45.0
                hole = 0.55
                stroke = 1.0
                strokeColor = Theme.PAPER
            }
            layout { standard(title, ds, width, height, showLegend, blankAxes = true) }
        }

    /** A scatter plot of points coloured by a single [color] (or per-point). */
    fun scatter(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        x: List<Double>,
        y: List<Double>,
        color: Color,
        pointSize: Double = 6.0,
        width: Int = 1000,
        height: Int = 820,
    ): Plot =
        plot {
            points {
                x(x) { axis.name = xLabel }
                y(y) { axis.name = yLabel }
                size = pointSize
                this.color = color
                alpha = 0.85
            }
            layout { standard(title, ds, width, height) }
        }

    /**
     * A heatmap whose cells are coloured by explicit per-cell colours. [xs]/[ys]
     * are parallel cell coordinates (categories) and [cellColors] the matching
     * colours; an identity categorical scale maps each distinct colour to itself.
     */
    fun heatmap(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        xs: List<String>,
        ys: List<String>,
        xOrder: List<String>,
        yOrder: List<String>,
        cellColors: List<Color>,
        width: Int = 1200,
        height: Int = 1100,
    ): Plot {
        val hexes = cellColors.map { (it as org.jetbrains.kotlinx.kandy.util.color.StandardColor.AsHexColor).hexString }
        val distinct = hexes.distinct()
        return plot {
            tiles {
                x(xs) {
                    scale = categorical(categories = xOrder)
                    axis.name = xLabel
                }
                y(ys) {
                    scale = categorical(categories = yOrder)
                    axis.name = yLabel
                }
                fillColor(hexes) {
                    scale = categorical(*distinct.map { it to Color.hex(it) }.toTypedArray())
                }
            }
            layout { standard(title, ds, width, height) }
        }
    }

    /**
     * Stacked layers (bars or area) from long-form data. [x]/[y]/[group] are
     * parallel per-point lists; [groupOrder] sets the stack/legend order (put the
     * most-active group last so it sits on top); [colors] maps group -> colour.
     * [yearAxis] labels a fractional-year x with whole years.
     */
    fun stacked(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        x: List<Double>,
        y: List<Double>,
        group: List<String>,
        groupOrder: List<String>,
        colors: Map<String, Color>,
        asArea: Boolean,
        yearAxis: Boolean = true,
        showLegend: Boolean = true,
        width: Int = 1300,
        height: Int = 820,
    ): Plot {
        val data = mapOf("x" to x, "y" to y, "g" to group)
        val scalePairs = groupOrder.map { it to colors.getValue(it) }.toTypedArray()
        val years = if (x.isEmpty()) IntRange.EMPTY else ceil(x.min()).toInt()..floor(x.max()).toInt()
        return plot(data) {
            if (asArea) {
                area {
                    x("x") {
                        axis.name = xLabel
                        if (yearAxis && !years.isEmpty()) axis.breaksLabeled(years.map { it.toDouble() }, years.map { it.toString() })
                    }
                    y("y") { axis.name = yLabel }
                    fillColor("g") { scale = categorical(*scalePairs) }
                    alpha = 0.9
                    position = Position.stack()
                }
            } else {
                bars {
                    x("x") {
                        axis.name = xLabel
                        if (yearAxis && !years.isEmpty()) axis.breaksLabeled(years.map { it.toDouble() }, years.map { it.toString() })
                    }
                    y("y") { axis.name = yLabel }
                    fillColor("g") { scale = categorical(*scalePairs) }
                    position = Position.stack()
                }
            }
            layout { standard(title, ds, width, height, showLegend) }
        }
    }

    /**
     * A heatmap coloured by a continuous value gradient within one [hue]
     * (light = small, dark = large). [xs]/[ys]/[values] are parallel per-cell lists.
     */
    fun heatmapValue(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        xs: List<String>,
        ys: List<String>,
        xOrder: List<String>,
        yOrder: List<String>,
        values: List<Double>,
        hue: Map<Int, Color>,
        width: Int = 1200,
        height: Int = 1100,
    ): Plot =
        plot {
            tiles {
                x(xs) {
                    scale = categorical(categories = xOrder)
                    axis.name = xLabel
                }
                y(ys) {
                    scale = categorical(categories = yOrder)
                    axis.name = yLabel
                }
                fillColor(values) {
                    scale = continuous(range = hue.getValue(50)..hue.getValue(900))
                }
            }
            layout { standard(title, ds, width, height) }
        }

    /**
     * A heatmap with a continuous numeric x (e.g. fractional year) and a
     * categorical y, cells coloured by explicit per-cell colours. Used for the
     * weekly activity timeline, where there are too many time buckets to label
     * categorically.
     */
    fun heatmapTimeline(
        ds: Dataset,
        title: String,
        xLabel: String,
        yLabel: String,
        x: List<Double>,
        ys: List<String>,
        yOrder: List<String>,
        cellColors: List<Color>,
        tileWidth: Double,
        width: Int = 1300,
        height: Int = 1000,
    ): Plot {
        val hexes = cellColors.map { (it as org.jetbrains.kotlinx.kandy.util.color.StandardColor.AsHexColor).hexString }
        val distinct = hexes.distinct()
        return plot {
            tiles {
                x(x) {
                    axis.name = xLabel
                    val years = ceil(x.min()).toInt()..floor(x.max()).toInt()
                    if (years.first <= years.last) {
                        axis.breaksLabeled(years.map { it.toDouble() }, years.map { it.toString() })
                    }
                }
                y(ys) {
                    scale = categorical(categories = yOrder)
                    axis.name = yLabel
                }
                this.width = tileWidth
                fillColor(hexes) {
                    scale = categorical(*distinct.map { it to Color.hex(it) }.toTypedArray())
                }
            }
            layout { standard(title, ds, width, height) }
        }
    }
}
