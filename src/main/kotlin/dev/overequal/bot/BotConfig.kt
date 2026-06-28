package dev.overequal.bot

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Runtime configuration for the bot. The token comes from the `DISCORD_TOKEN`
 * environment variable, or a `.env` file (gitignored) as `DISCORD_TOKEN=...`.
 */
data class BotConfig(
    val token: String,
    val dataDir: Path = Path("data"),
    /**
     * Request the privileged MESSAGE_CONTENT intent (needed to scrape message
     * text). Must be enabled in the Discord Developer Portal too, else the
     * gateway closes with 4014. Disable via `MESSAGE_CONTENT_INTENT=false` to run
     * the bot without scraping text (e.g. before enabling it in the portal).
     */
    val messageContentIntent: Boolean = true,
) {
    companion object {
        fun load(envFile: Path = Path(".env")): BotConfig {
            val dotenv = readDotenv(envFile)
            val token =
                System.getenv("DISCORD_TOKEN")?.takeIf { it.isNotBlank() }
                    ?: dotenv["DISCORD_TOKEN"]
                    ?: error("DISCORD_TOKEN not set (env var or .env file)")
            val mci =
                (System.getenv("MESSAGE_CONTENT_INTENT") ?: dotenv["MESSAGE_CONTENT_INTENT"])
                    ?.lowercase() != "false"
            return BotConfig(token = token.trim(), messageContentIntent = mci)
        }

        private fun readDotenv(file: Path): Map<String, String> {
            if (!file.exists()) return emptyMap()
            return file
                .readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
                .associate { line ->
                    val k = line.substringBefore("=").trim()
                    val v = line.substringAfter("=").trim().trim('"', '\'')
                    k to v
                }
        }
    }
}
