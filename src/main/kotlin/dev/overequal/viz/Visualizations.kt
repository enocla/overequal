package dev.overequal.viz

import dev.overequal.viz.impl.Top30

/** The registry of all visualizations the bot/CLI can render (and "run all"). */
object Visualizations {
    val all: List<Visualization> =
        listOf(
            Top30,
        )

    val byId: Map<String, Visualization> = all.associateBy { it.id }

    fun ids(): List<String> = all.map { it.id }
}
