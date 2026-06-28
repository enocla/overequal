package dev.overequal.viz

import org.slf4j.LoggerFactory
import java.awt.Font
import java.awt.GraphicsEnvironment

/**
 * Registers the bundled IBM Plex Sans TTFs with the local AWT [GraphicsEnvironment]
 * so Lets-Plot's headless rasterizer can resolve the family by name on any machine
 * (the fonts ship in `resources/fonts/`, not just the dev box's `~/Library/Fonts`).
 *
 * [sans] holds the actual family name reported by the registered regular face, which
 * the charts feed to Kandy via `FontFamily.custom(Fonts.sans)`. If registration fails
 * it stays at the expected default and Lets-Plot falls back to a logical sans.
 */
object Fonts {
    private val log = LoggerFactory.getLogger(Fonts::class.java)

    /** Family name used by the charts; updated to the registered face's real family. */
    var sans: String = "IBM Plex Sans"
        private set

    private var registered = false

    @Synchronized
    fun register() {
        if (registered) return
        registered = true
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        // Regular first so its family name wins; italic added for emphasis runs.
        listOf("/fonts/IBMPlexSans.ttf" to true, "/fonts/IBMPlexSans-Italic.ttf" to false)
            .forEach { (path, isRegular) ->
                val stream = Fonts::class.java.getResourceAsStream(path)
                if (stream == null) {
                    log.warn("bundled font {} not found on classpath; charts fall back to default sans", path)
                    return@forEach
                }
                stream.use {
                    runCatching {
                        val font = Font.createFont(Font.TRUETYPE_FONT, it)
                        ge.registerFont(font)
                        if (isRegular) sans = font.family
                    }.onFailure { e -> log.warn("failed to register {}: {}", path, e.message) }
                }
            }
        log.info("chart font family: {}", sans)
    }
}
