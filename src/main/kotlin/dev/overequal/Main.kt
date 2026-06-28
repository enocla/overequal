package dev.overequal

import dev.overequal.cli.RenderCli

/**
 * Entry point. Subcommands:
 *   render …   headless: render visualizations from a JSONL corpus to PNGs.
 *   bot        run the Discord bot (added in a later checkpoint).
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")

    when (args.firstOrNull()) {
        "render" -> RenderCli.run(args.drop(1))
        "bot" -> println("bot mode not wired yet — coming in a later checkpoint")
        else ->
            println(
                """
                overequal — Discord dataviz bot

                Usage:
                  render --input <file.jsonl> [--out <dir>] [--viz <id>|all]
                         [--guild <name>] [--redact-names] [--redact-content] [--exclude-bots]
                  bot
                """.trimIndent(),
            )
    }
}
