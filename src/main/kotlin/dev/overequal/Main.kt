package dev.overequal

import dev.overequal.bot.Bot
import dev.overequal.cli.RenderCli
import dev.overequal.viz.Fonts

/**
 * Entry point. Subcommands:
 *   render …   headless: render visualizations from a JSONL corpus to PNGs.
 *   bot        run the Discord bot (token from DISCORD_TOKEN / .env).
 */
fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    Fonts.register()

    when (args.firstOrNull()) {
        "render" -> RenderCli.run(args.drop(1))
        "bot" -> Bot.start()
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
