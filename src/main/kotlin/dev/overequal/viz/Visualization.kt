package dev.overequal.viz

import dev.overequal.data.Dataset

/**
 * One renderable chart. [id] is the stable command/file name; [requiresContent]
 * marks charts that analyse message text, which are skipped (with a notice) when
 * the dataset has content redacted.
 */
interface Visualization {
    val id: String
    val title: String
    val description: String
    val requiresContent: Boolean get() = false

    /** Render to PNG bytes, or `null` if it cannot run on this dataset. */
    fun render(ds: Dataset): ByteArray?
}
