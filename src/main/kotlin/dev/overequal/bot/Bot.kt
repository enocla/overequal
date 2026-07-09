package dev.overequal.bot

import dev.overequal.data.Dataset
import dev.overequal.data.DatasetLoader
import dev.overequal.data.MessageCache
import dev.overequal.data.RenderOptions
import dev.overequal.scrape.MessageWatcher
import dev.overequal.scrape.Scraper
import dev.overequal.viz.Visualization
import dev.overequal.viz.Visualizations
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.guild.GuildCreateEvent
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.event.domain.interaction.SelectMenuInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.core.`object`.component.SelectMenu
import discord4j.core.`object`.entity.Guild
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val messageWatcher = MessageWatcher(cache)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val vizAllSessions = ConcurrentHashMap<String, VizAllSession>()

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

            // Register for guilds we're already in. We enumerate over REST rather
            // than relying on GuildCreateEvent for these: the gateway's default
            // (buffering) dispatcher replays the startup event burst only to the
            // FIRST subscriber, so with two independent on(...) collectors the
            // GuildCreateEvent one can lose the race and silently skip startup
            // registration (no "registered N commands" line; newly added commands
            // never appear while previously-registered ones persist on Discord's
            // side). REST is a direct call, unaffected by that race.
            scope.launch {
                gateway.restClient.guilds.asFlow().collect { g ->
                    runCatching { registerCommands(gateway, appId, g.id().asLong()) }
                        .onFailure { log.error("command registration failed for {}: {}", g.name(), it.message) }
                }
            }

            // Guilds joined while the bot is running: the collector is already
            // attached by now, so there's no startup-burst race here. Re-running
            // for a guild already handled above is harmless (bulkOverwrite is
            // idempotent).
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
            scope.launch {
                gateway.on(SelectMenuInteractionEvent::class.java).asFlow().collect { ev ->
                    scope.launch { handleSelect(ev) }
                }
            }
            messageWatcher.watch(gateway, scope)

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
                    .name(it.title)
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
                ApplicationCommandRequest
                    .builder()
                    .name("export")
                    .description("Upload cached messages to a temporary host (expires in 1 hour)")
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
                "export" -> handleExport(event, guildId)
            }
        } catch (e: Exception) {
            log.error("command {} failed: {}", event.commandName, e.message, e)
            send(event, ComponentsV2.notice("⚠️ Something went wrong: ${e.message}"))
        }
    }

    private suspend fun handleSelect(event: SelectMenuInteractionEvent) {
        if (!event.customId.startsWith(VIZ_ALL_SELECT_PREFIX)) return

        try {
            handleVizAllSelect(event)
        } catch (e: Exception) {
            log.error("select interaction {} failed: {}", event.customId, e.message, e)
            runCatching {
                event.reply("⚠️ Something went wrong: ${e.message}").withEphemeral(true).awaitFirstOrNull()
            }
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
            send(event, ComponentsV2.notice("ℹ️ **${viz.title}** needs message text, which is redacted. Re-run without `redact_content`."))
            return
        }
        val png = withContext(Dispatchers.Default) { Visualizations.render(viz, ds) }
        if (png == null) {
            send(event, ComponentsV2.notice("ℹ️ Not enough data to render **${viz.title}**."))
            return
        }
        send(event, ComponentsV2.chart(viz, png))
    }

    private suspend fun handleVizAll(
        event: ChatInputInteractionEvent,
        guildId: String,
    ) {
        // Ephemeral: only the invoking user sees the picker (and its followup
        // edits), so switching charts can't spam the channel.
        event.deferReply().withEphemeral(true).awaitFirstOrNull()
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

        val sessionId = newVizAllSessionId()
        pruneVizAllSessions()
        vizAllSessions[sessionId] =
            VizAllSession(
                guildName = ds.guildName,
                periodLabel = ds.periodLabel(),
                // A string select carries at most 25 options; we render 22 charts
                // today, so this only guards against future additions.
                rendered = rendered.take(SELECT_MENU_MAX_OPTIONS),
                expiresAtMillis = System.currentTimeMillis() + VIZ_ALL_SESSION_TTL_MS,
            )
        send(event, vizAllChartMessage(sessionId, index = 0))
    }

    private suspend fun handleVizAllSelect(event: SelectMenuInteractionEvent) {
        val sessionId =
            parseVizAllSelect(event.customId)
                ?: run {
                    event.reply("That chart picker is invalid.").withEphemeral(true).awaitFirstOrNull()
                    return
                }

        pruneVizAllSessions()
        val session = vizAllSessions[sessionId]
        if (session == null) {
            event.reply("This `/viz-all` picker expired. Run `/viz-all` again.").withEphemeral(true).awaitFirstOrNull()
            return
        }
        val index = event.values.firstOrNull()?.toIntOrNull() ?: 0

        // Acknowledge with a deferred edit, then patch the message over the
        // followup webhook. The interaction *callback* edit drops multipart file
        // uploads, so a component referencing a fresh `attachment://` 400s with
        // UNFURLED_MEDIA_ITEM_REFERENCED_ATTACHMENT_NOT_FOUND.
        event.deferEdit().awaitFirstOrNull()
        val message = vizAllChartMessage(sessionId, index)
        event
            .editReply()
            .withComponents(*message.components.toTypedArray())
            .withFiles(*message.files.toTypedArray())
            // Drop the previous chart's PNG so attachments don't pile up.
            .withAttachmentsOrNull(emptyList())
            .awaitSingle()
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
                    if (meta.channels.isNotEmpty()) {
                        append("\n**Channels with data** (these stay live; others need a `/scrape`):\n")
                        val ranked = meta.channels.sortedByDescending { it.count }
                        ranked.take(STATUS_CHANNEL_LIMIT).forEach {
                            append("• #${it.name} — ${"%,d".format(it.count)}\n")
                        }
                        val rest = ranked.size - STATUS_CHANNEL_LIMIT
                        if (rest > 0) append("• …and **$rest** more\n")
                    }
                    append("\nAvailable charts: ${Visualizations.all.joinToString(", ") { "**${it.title}**" }}")
                }
            }
        send(event, ComponentsV2.notice(text))
    }

    private suspend fun handleBotInfo(event: ChatInputInteractionEvent) {
        event.deferReply().awaitFirstOrNull()
        send(
            event,
            ComponentsV2.notice(
                "## 🤖 Bot info\n**Version (git commit):** `$gitHash`",
            ),
        )
    }

    private suspend fun handleExport(
        event: ChatInputInteractionEvent,
        guildId: String,
    ) {
        event.deferReply().awaitFirstOrNull()

        if (!cache.hasCache(guildId)) {
            send(event, ComponentsV2.notice("📭 No data cached yet. Run `/scrape` first."))
            return
        }

        val meta =
            cache.meta(guildId)
                ?: run {
                    send(event, ComponentsV2.notice("⚠️ Could not read cache metadata."))
                    return
                }

        val path = cache.messagesPath(guildId)
        val raw =
            withContext(Dispatchers.IO) {
                java.nio.file.Files
                    .readAllBytes(path)
            }

        // The inline-attachment ceiling isn't fixed: Discord raises it with the
        // guild's boost tier. Read the live tier and size the branch accordingly,
        // falling back to the base 10 MiB if the guild can't be fetched.
        val premiumTier =
            event
                .interaction
                .guild
                .awaitFirstOrNull()
                ?.premiumTier
        val uploadLimit = uploadLimitFor(premiumTier)

        // Compress first: it shrinks both the direct-upload payload and the
        // ciphertext, and decides which branch we take (against the upload limit).
        val compressed = withContext(Dispatchers.Default) { Export.compress(raw) }
        val messages = "%,d".format(meta.messageCount)

        if (compressed.size <= uploadLimit) {
            // Small enough to send inline — no third party, no encryption needed.
            send(
                event,
                ComponentsV2.fileNotice(
                    markdown =
                        "## 📤 Export\n" +
                            "**$messages** messages, zstd-compressed to **${humanBytes(compressed.size)}**.\n" +
                            "Attached directly below.",
                    filename = "overequal-$guildId.jsonl.zst",
                    bytes = compressed,
                ),
            )
            return
        }

        // Too big to attach: encrypt with a one-time random key and upload the
        // ciphertext to litterbox. The key travels in the message, not the file,
        // so the host only ever sees opaque bytes.
        val encrypted = withContext(Dispatchers.Default) { Export.encrypt(compressed) }
        val filename = "overequal-$guildId.jsonl.zst.enc"
        val result = withContext(Dispatchers.IO) { Export.upload(encrypted.payload, filename) }

        result.fold(
            onSuccess = { url ->
                send(
                    event,
                    ComponentsV2.notice(
                        "## 📤 Exported to litterbox (encrypted)\n" +
                            "**$messages** messages, zstd-compressed then AES-256-GCM encrypted " +
                            "(**${humanBytes(encrypted.payload.size)}**).\n" +
                            "Expires in **1 hour**.\n" +
                            "URL: $url\n" +
                            "Key (base64): `${encrypted.keyBase64}`\n" +
                            "Payload layout: `nonce(12B) || ciphertext || tag(16B)`, " +
                            "then zstd-decompress the plaintext.",
                    ),
                )
            },
            onFailure = { e ->
                log.error("export failed: {}", e.message, e)
                send(event, ComponentsV2.notice("⚠️ Export failed: ${e.message}"))
            },
        )
    }

    /** Renders a byte count as a short human-readable string (KiB/MiB). */
    private fun humanBytes(n: Int): String =
        when {
            n >= 1 shl 20 -> "%.1f MiB".format(n / (1 shl 20).toDouble())
            n >= 1 shl 10 -> "%.1f KiB".format(n / (1 shl 10).toDouble())
            else -> "$n B"
        }

    /**
     * The per-file attachment ceiling for a guild, in bytes. Discord doesn't
     * expose the limit as a field; it's a function of the boost ([PremiumTier])
     * level: base/T1 = 10 MiB, T2 = 50 MiB, T3 = 100 MiB. Anything unknown
     * (including a guild we couldn't fetch) falls back to the base 10 MiB.
     */
    private fun uploadLimitFor(tier: Guild.PremiumTier?): Int =
        when (tier) {
            Guild.PremiumTier.TIER_2 -> 50 * 1024 * 1024
            Guild.PremiumTier.TIER_3 -> 100 * 1024 * 1024
            else -> 10 * 1024 * 1024
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

    private fun vizAllChartMessage(
        sessionId: String,
        index: Int,
    ): V2Message {
        val session = vizAllSessions.getValue(sessionId)
        val selected = index.coerceIn(0, session.rendered.lastIndex)
        val (viz, png) = session.rendered[selected]
        val options =
            session.rendered.mapIndexed { i, (v, _) ->
                SelectMenu.Option
                    .of(v.title.take(SELECT_LABEL_MAX_CHARS), i.toString())
                    .withDescription(v.description.take(SELECT_LABEL_MAX_CHARS))
                    .withDefault(i == selected)
            }
        val select =
            SelectMenu
                .of(vizAllCustomId(sessionId), options)
                .withPlaceholder("Pick a visualization")

        return ComponentsV2.chartPicker(
            guildName = session.guildName,
            periodLabel = session.periodLabel,
            totalCharts = session.rendered.size,
            viz = viz,
            png = png,
            select = select,
        )
    }

    private fun newVizAllSessionId(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(16)

    private fun vizAllCustomId(sessionId: String): String = "$VIZ_ALL_SELECT_PREFIX:$sessionId"

    /** `overequal:viz-all:<sessionId>` → session id, or null if malformed. */
    private fun parseVizAllSelect(customId: String): String? {
        val parts = customId.split(":")
        if (parts.size != 3 || parts[0] != "overequal" || parts[1] != "viz-all") return null
        return parts[2].takeIf { it.isNotEmpty() }
    }

    private fun pruneVizAllSessions(nowMillis: Long = System.currentTimeMillis()) {
        for ((sessionId, session) in vizAllSessions) {
            if (session.expiresAtMillis <= nowMillis) {
                vizAllSessions.remove(sessionId, session)
            }
        }
    }

    private data class VizAllSession(
        val guildName: String,
        val periodLabel: String,
        val rendered: List<Pair<Visualization, ByteArray>>,
        val expiresAtMillis: Long,
    )

    companion object {
        /** Max channels listed in `/status` before collapsing the rest into a "+N more" line. */
        private const val STATUS_CHANNEL_LIMIT = 25
        private const val VIZ_ALL_SESSION_TTL_MS = 30L * 60L * 1000L
        private const val VIZ_ALL_SELECT_PREFIX = "overequal:viz-all"

        /** Discord's hard cap on options in a string select menu. */
        private const val SELECT_MENU_MAX_OPTIONS = 25

        /** Discord's hard cap on a select option's label/description length. */
        private const val SELECT_LABEL_MAX_CHARS = 100

        fun start() {
            val config = BotConfig.load()
            Bot(config).run()
        }
    }
}
