package dev.overequal.cli

import dev.overequal.data.DatasetLoader
import dev.overequal.data.MessageCache
import dev.overequal.data.RenderOptions
import dev.overequal.viz.Visualizations
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * Headless renderer: load a JSONL corpus (a cache file or the reference
 * `merged.jsonl`), build a [dev.overequal.data.Dataset] and render one or all
 * visualizations to PNGs. Lets the charts be developed/verified without Discord.
 *
 * Usage:
 *   render --input <file.jsonl> [--out <dir>] [--viz <id>|all]
 *          [--guild <name>] [--redact-names] [--redact-content] [--exclude-bots]
 */
object RenderCli {
    private val log = LoggerFactory.getLogger(RenderCli::class.java)

    fun run(args: List<String>) {
        val opts = parse(args)
        val input = opts["input"] ?: error("--input <file.jsonl> is required")
        val outDir = Path(opts["out"] ?: "out")
        val which = opts["viz"] ?: "all"
        val guild = opts["guild"] ?: "this server"
        val options =
            RenderOptions(
                redactNames = flag(args, "--redact-names"),
                redactContent = flag(args, "--redact-content"),
                excludeBots = flag(args, "--exclude-bots"),
            )

        log.info("loading {} …", input)
        val raws = MessageCache(Path(".")).readJsonl(Path(input))
        val ds = DatasetLoader.build(raws, guild, options)
        log.info("dataset: {} messages, period {}", ds.count, ds.periodLabel())

        outDir.createDirectories()
        val targets =
            if (which == "all") {
                Visualizations.all
            } else {
                listOf(Visualizations.byId[which] ?: error("unknown viz '$which'. known: ${Visualizations.ids()}"))
            }

        for (viz in targets) {
            val bytes =
                runCatching { viz.render(ds) }.getOrElse {
                    log.error("failed to render {}: {}", viz.id, it.message, it)
                    null
                }
            if (bytes == null) {
                log.warn("skipped {} (no output)", viz.id)
                continue
            }
            val file: Path = outDir.resolve("${viz.id}.png")
            file.writeBytes(bytes)
            log.info("wrote {} ({} bytes)", file, bytes.size)
        }
    }

    private fun parse(args: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && i + 1 < args.size && !args[i + 1].startsWith("--")) {
                map[a.removePrefix("--")] = args[i + 1]
                i += 2
            } else {
                i += 1
            }
        }
        return map
    }

    private fun flag(
        args: List<String>,
        name: String,
    ): Boolean = args.contains(name)
}
