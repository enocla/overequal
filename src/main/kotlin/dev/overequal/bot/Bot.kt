package dev.overequal.bot

import dev.overequal.data.Dataset
import dev.overequal.data.DatasetLoader
import dev.overequal.data.MessageCache
import dev.overequal.data.RenderOptions
import dev.overequal.scrape.Scraper
import dev.overequal.viz.Visualization
import dev.overequal.viz.Visualizations
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.guild.GuildCreateEvent
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.discordjson.json.ApplicationCommandOptionChoiceData
import discord4j.discordjson.json.ApplicationCommandOptionData
import discord4j.discordjson.json.ApplicationCommandRequest
import discord4j.gateway.intent.Intent
import discord4j.gateway.intent.IntentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * The Discord bot. Registers per-guild slash commands (instant, and works in any
 * server it joins) and serves them: `/scrape`, `/viz`, `/viz-all`, `/status`,
 * `/bot-info`.
 * Output is Components V2 (see [ComponentsV2]).
 */
class Bot(
    private val config: BotConfig,
) {
    private val log = LoggerFactory.getLogger(Bot::class.java)
    private val cache = MessageCache(config.dataDir)
    private val scraper = Scraper(cache, config.scrapeRatePerSecond)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Short git commit hash embedded at compile time into the classpath resource
     * `/version.properties` by the `generateVersionProperties` Gradle task.
     * Falls back to "unknown" if the resource is absent (e.g. running from an IDE
     * without having run the build task).
     */
    private val gitHash: String =
        runCatching {
            val props = java.util.Properties()
            Bot::class.java.getResourceAsStream("/version.properties")?.use { props.load(it) }
            (props.getProperty("git.hash") ?: "unknown").ifBlank { "unknown" }
        }.getOrDefault("unknown")

    fun run() =
        runBlocking {
            val gateway =
                DiscordClient
                    .create(config.token)
                    .gateway()
                    // Non-privileged intents plus MESSAGE_CONTENT (privileged), so the
                    // scraper can read historical message text. MESSAGE_CONTENT must be
                    // enabled in the Developer Portal too, else the gateway closes with
                    // 4014; the other privileged intents (presences/members) are never
                    // requested.
                    .setEnabledIntents(
                        IntentSet.nonPrivileged().or(IntentSet.of(Intent.MESSAGE_CONTENT)),
                    ).login()
                    .awaitSingle()
            val appId = gateway.restClient.applicationId.awaitSingle()
            log.info("logged in; application id {}", appId)

            scope.launch {
                gateway.on(GuildCreateEvent::class.java).asFlow().collect { ev ->
                    runCatching { registerCommands(gateway, appId, ev.guild.id.asLong()) }
                        .onFailure { log.error("command registration failed for {}: {}", ev.guild.name, it.message) }
                }
            }
            scope.launch {
                gateway.on(ChatInputInteractionEvent::class.java).asFlow().collect { ev ->
                    scope.launch { handle(ev) }
                }
            }

            gateway.onDisconnect().awaitFirstOrNull()
        }

    // --- command registration ----------------------------------------------

    private suspend fun registerCommands(
        gateway: GatewayDiscordClient,
        appId: Long,
        guildId: Long,
    ) {
        val redactOpts =
            listOf(
                boolOpt("exclude_bots", "Exclude bot accounts from the analysis"),
                boolOpt("redact_names", "Replace member names with anonymous pseudonyms"),
                boolOpt("redact_content", "Hide message text (skips word/slur charts)"),
            )
        val vizChoices =
            Visualizations.all.take(25).map {
                ApplicationCommandOptionChoiceData
                    .builder()
                    .name(it.id)
                    .value(it.id)
                    .build()
            }

        val commands =
            listOf(
                ApplicationCommandRequest
                    .builder()
                    .name("scrape")
                    .description("Scrape and cache this server's messages")
                    .addOption(stringOpt("channel", "Only this channel (default: all text channels)", required = false))
                    .addOption(intOpt("limit", "Max messages to fetch (default: all)"))
                    .build(),
                ApplicationCommandRequest
                    .builder()
                    .name("viz")
                    .description("Render one visualization from the cached corpus")
                    .addOption(
                        ApplicationCommandOptionData
                            .builder()
                            .name("name")
                            .description("Which visualization")
                            .type(ApplicationCommandOption.Type.STRING.value)
                            .required(true)
                            .choices(vizChoices)
                            .build(),
                    ).apply { redactOpts.forEach { addOption(it) } }
                    .build(),
                ApplicationCommandRequest
                    .builder()
                    .name("viz-all")
                    .description("Render every visualization from the cached corpus")
                    .apply { redactOpts.forEach { addOption(it) } }
                    .build(),
                ApplicationCommandRequest
                    .builder()
                    .name("status")
                    .description("Show what is cached for this server")
                    .build(),
                ApplicationCommandRequest
                    .builder()
                    .name("bot-info")
                    .description("Show the running bot version (git commit hash)")
                    .build(),
            )

        gateway.restClient
            .applicationService
            .bulkOverwriteGuildApplicationCommand(appId, guildId, commands)
            .collectList()
            .awaitSingle()
        log.info("registered {} commands for guild {}", commands.size, guildId)
    }

    private fun boolOpt(
        name: String,
        desc: String,
    ) = ApplicationCommandOptionData
        .builder()
        .name(name)
        .description(desc)
        .type(ApplicationCommandOption.Type.BOOLEAN.value)
        .required(false)
        .build()

    private fun stringOpt(
        name: String,
        desc: String,
        required: Boolean,
    ) = ApplicationCommandOptionData
        .builder()
        .name(name)
        .description(desc)
        .type(ApplicationCommandOption.Type.STRING.value)
        .required(required)
        .build()

    private fun intOpt(
        name: String,
        desc: String,
    ) = ApplicationCommandOptionData
        .builder()
        .name(name)
        .description(desc)
        .type(ApplicationCommandOption.Type.INTEGER.value)
        .required(false)
        .build()

    // --- command handling ---------------------------------------------------

    private suspend fun handle(event: ChatInputInteractionEvent) {
        val guildId =
            event.interaction.guildId
                .orElse(null)
                ?.asString()
        if (guildId == null) {
            event.reply("This bot only works inside a server.").withEphemeral(true).awaitFirstOrNull()
            return
        }
        try {
            when (event.commandName) {
                "scrape" -> handleScrape(event, guildId)
                "viz" -> handleViz(event, guildId)
                "viz-all" -> handleVizAll(event, guildId)
                "status" -> handleStatus(event, guildId)
                "bot-info" -> handleBotInfo(event)
            }
        } catch (e: Exception) {
            log.error("command {} failed: {}", event.commandName, e.message, e)
            send(event, ComponentsV2.notice("⚠️ Something went wrong: ${e.message}"))
        }
    }

    private suspend fun handleScrape(
        event: ChatInputInteractionEvent,
        guildId: String,
    ) {
        event.deferReply().awaitFirstOrNull()
        val guild = event.interaction.guild.awaitSingle()
        val channel = event.getOptionAsString("channel").orElse(null)
        val limit =
            event
                .getOptionAsLong("limit")
                .orElse(0L)
                .toInt()
                .takeIf { it > 0 }

        val priorCount = cache.meta(guildId)?.messageCount ?: 0
        val meta =
            scraper.scrape(guild, channel, limit) { ch, total ->
                log.debug("progress #{} total {}", ch, total)
            }
        val added = (meta.messageCount - priorCount).coerceAtLeast(0)
        send(
            event,
            ComponentsV2.notice(
                buildString {
                    append("## ✅ Scrape complete\n")
                    append("**${"%,d".format(added)}** new messages")
                    if (priorCount > 0) append(" (**${"%,d".format(meta.messageCount)}** total cached)")
                    append(" across **${meta.channels.size}** channels.\n")
                    if (meta.firstTimestamp != null) append("Period: `${meta.firstTimestamp}` → `${meta.lastTimestamp}`\n")
                    append("Run `/viz` or `/viz-all` to render charts.")
                },
            ),
        )
    }

    private suspend fun handleViz(
        event: ChatInputInteractionEvent,
        guildId: String,
    ) {
        val id = event.getOptionAsString("name").orElse("")
        val viz = Visualizations.byId[id]
        if (viz == null) {
            event.reply("Unknown visualization `$id`.").withEphemeral(true).awaitFirstOrNull()
            return
        }
        event.deferReply().awaitFirstOrNull()
        val ds = loadDataset(event, guildId) ?: return
        if (viz.requiresContent && ds.contentRedacted) {
            send(event, ComponentsV2.notice("ℹ️ `${viz.id}` needs message text, which is redacted. Re-run without `redact_content`."))
            return
        }
        val png = withContext(Dispatchers.Default) { Visualizations.render(viz, ds) }
        if (png == null) {
            send(event, ComponentsV2.notice("ℹ️ Not enough data to render `${viz.id}`."))
            return
        }
        send(event, ComponentsV2.chart(viz, png))
    }

    private suspend fun handleVizAll(
        event: ChatInputInteractionEvent,
        guildId: String,
    ) {
        event.deferReply().awaitFirstOrNull()
        val ds = loadDataset(event, guildId) ?: return

        val rendered = ArrayList<Pair<Visualization, ByteArray>>()
        for (viz in Visualizations.all) {
            val png = runCatching { withContext(Dispatchers.Default) { Visualizations.render(viz, ds) } }.getOrNull()
            if (png != null) rendered.add(viz to png)
        }
        if (rendered.isEmpty()) {
            send(event, ComponentsV2.notice("ℹ️ Not enough data to render anything."))
            return
        }
        send(event, ComponentsV2.notice("## 📊 ${ds.guildName} — ${rendered.size} visualizations\n${ds.periodLabel()}"))
        // Up to 4 charts (containers + files) per message to stay within limits.
        for (batch in rendered.chunked(4)) {
            send(event, ComponentsV2.charts(batch))
        }
    }

    private suspend fun handleStatus(
        event: ChatInputInteractionEvent,
        guildId: String,
    ) {
        event.deferReply().awaitFirstOrNull()
        val meta = cache.meta(guildId)
        val text =
            if (meta == null) {
                "## 📭 Nothing cached yet\nRun `/scrape` to fetch this server's messages."
            } else {
                buildString {
                    append("## 📦 Cache status\n")
                    append("**${"%,d".format(meta.messageCount)}** messages from **${meta.channels.size}** channels.\n")
                    if (meta.firstTimestamp != null) append("Period: `${meta.firstTimestamp}` → `${meta.lastTimestamp}`\n")
                    append("Available charts: ${Visualizations.ids().joinToString(", ") { "`$it`" }}")
                }
            }
        send(event, ComponentsV2.notice(text))
    }

    private suspend fun handleBotInfo(
        event: ChatInputInteractionEvent,
    ) {
        event.deferReply().awaitFirstOrNull()
        send(
            event,
            ComponentsV2.notice(
                "## 🤖 Bot info\n**Version (git commit):** `$gitHash`",
            ),
        )
    }

    // --- helpers ------------------------------------------------------------

    private suspend fun loadDataset(
        event: ChatInputInteractionEvent,
        guildId: String,
    ): Dataset? {
        if (!cache.hasCache(guildId)) {
            send(event, ComponentsV2.notice("📭 No data cached yet. Run `/scrape` first."))
            return null
        }
        val options =
            RenderOptions(
                redactNames = event.getOptionAsBoolean("redact_names").orElse(false),
                redactContent = event.getOptionAsBoolean("redact_content").orElse(false),
                excludeBots = event.getOptionAsBoolean("exclude_bots").orElse(false),
            )
        val guildName = cache.meta(guildId)?.guildName ?: "this server"
        val raws = withContext(Dispatchers.Default) { cache.read(guildId) }
        return withContext(Dispatchers.Default) { DatasetLoader.build(raws, guildName, options) }
    }

    private suspend fun send(
        event: ChatInputInteractionEvent,
        message: V2Message,
    ) {
        event
            .createFollowup()
            .withComponents(*message.components.toTypedArray())
            .withFiles(*message.files.toTypedArray())
            .awaitSingle()
    }

    companion object {
        fun start() {
            val config = BotConfig.load()
            Bot(config).run()
        }
    }
}
