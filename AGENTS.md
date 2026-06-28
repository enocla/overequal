# overequal — agent working notes

A Discord bot (Kotlin + Discord4J) that scrapes a server's messages, caches them,
and renders the data visualizations defined by the reference Python project under
`./datavis` (a symlink; **reference only — do not modify, do not port verbatim,
re-implement in Kotlin**). Output uses Discord **Components V2**.

## Goal / requirements (from the user)

- Discord bot built on **Discord4J** (https://github.com/Discord4J/Discord4J), **all Kotlin**.
- Command to **scrape all messages** in the server, with parameters.
- Commands to **run visualizations**, including a command to **run all** (the set in `./datavis`).
- Option to **redact names and channel contents**.
- Output uses **Components V2** embeds.
- Kotlin, **formatted regularly** (spotless + ktlint).
- **Prefer external dependencies** over hand-rolling features → charts use **Kandy**.
- Scraped messages must be **cached**; viz read from the cache, not a live re-scrape.
- Visualization outputs must **reflect the time period of the data** (date range in title/footer).
- **Generalize across all servers**: no hardcoded user/bot/highlight names; no account
  merging (the reference's `april` case is ignored). Instead provide a **bot-exclusion
  option** (`excludeBots`, keyed off the `isBot` flag).

## Conventions

- **Git:** commit regularly at checkpoints with long, descriptive messages that read
  like working notes (what/why, decisions, gotchas). **Never** add a `Co-Authored-By:
  Claude` trailer; use the existing configured git identity (`Tnixc <tnixxc@gmail.com>`).
- **Formatting:** `./gradlew spotlessApply` (ktlint 1.5.0) before committing code.
- **Build/run:** `./gradlew build`, `./gradlew run --args="..."` for the headless CLI.
- Don't commit the scraped corpus or rendered PNGs (see `.gitignore`).

## Tech stack (versions pinned & verified on Maven Central)

- Kotlin 2.4.0, Gradle 9.6.1 (wrapper), host JDK 26 but **target JVM 21**
  (both `compileKotlin` jvmTarget and `java` source/targetCompatibility = 21).
- `com.discord4j:discord4j-core:3.3.2` — first STABLE release with full Components V2.
- `kotlinx-coroutines-reactor:1.11.0` — bridge Reactor `Mono`/`Flux` ↔ coroutines.
- `kotlinx-serialization-json:1.11.0` — cache (`merged.jsonl`-equivalent) format.
- `kandy-lets-plot:0.8.4` — JetBrains' Kotlin plotting lib; transitively brings
  `lets-plot-image-export`, so headless PNG export works with no native setup.
- `kotlinx-cli:0.3.6` — headless local CLI for rendering.
- `logback-classic:1.5.37`.

## Components V2 API (Discord4J 3.3.2, verified against the 3.3.2 source)

- Top-level send: `event.deferReply()` then `event.createFollowup().withComponents(container).withFiles(...)`.
  `withComponents(...)` auto-adds the `IS_COMPONENTS_V2` flag. With V2 you CANNOT set
  `content`/`embeds`/`stickers`/`poll`.
- `Container.of(Color color, TopLevelMessageComponent... children)` (Container IS a TopLevelMessageComponent).
- `TextDisplay.of(String markdown)` (max 4000 chars) — markdown is supported.
- `Separator.of(boolean visible, Separator.SpacingSize)`.
- `MediaGallery.of(MediaGalleryItem...)`, `MediaGalleryItem.of(UnfurledMediaItem)`,
  `UnfurledMediaItem.of("attachment://<filename>")`.
- File upload: `MessageCreateFields.File.of("name.png", inputStream)`; reference it from a
  component via `attachment://name.png`.
- `Color` is `discord4j.rest.util.Color`.

## Data format (see `datavis/data-format.md`)

One JSON object per message. Key fields we use: `id`, `timestamp` (ISO-8601), `content`,
`author{ id, name, nickname, isBot }`, `mentions[]` (author-shaped), `reactions[]`,
`channel{ id, name, category }`, `guild{ id, name }`. 

## Visualization theme (see `datavis/config.py`)

Flexoki palette (BLACK `#100F0F`, PAPER `#FFFCF0`, plus 50–950 ramps of red/orange/yellow/
green/cyan/blue/purple/magenta/gray). Body/label font **IBM Plex Sans** (bundled in
`resources/fonts/`, registered with AWT at startup by `viz/Fonts.kt` so Lets-Plot resolves
the family on any machine; falls back to a logical sans if registration fails). Rules:
per-bar frequency gradient (same hue, darker = larger); grid lines `BLACK @ alpha .25, lw .3`
(.5 single-axis); axis lines/ticks are ink (Lets-Plot's default is blue — overridden in
`ChartStyle`); most-active member at the TOP of bar charts. Charts get a fixed padding
inset around the plotting area (`ChartStyle.PAD`).

## Scraping (incremental + durable)

`scrape/Scraper.kt` streams each channel's history as a coroutine flow and flushes
to disk in batches of `BATCH_SIZE` (500), rewriting `meta.json` after every flush —
so memory stays bounded on huge guilds and an interrupted run keeps everything
already written. Per-channel **cursors** (`ChannelCursor.newestId`, the highest
message snowflake seen) live in `CacheMeta.channels`. Re-running `/scrape`:
- no cursor yet → full history backfill (`getMessagesBefore(now)`),
- cursor present → only messages newer than it (`getMessagesAfter(newestId)`),
  so we never re-download what we already have (**forward-only incremental**; an
  interrupted *initial* backfill isn't resumed from its oldest point, just picked up
  forward of the newest cached message). `MessageCache.readJsonl` de-dups by `id`,
  so any boundary overlap is harmless. `CacheMeta.messageCount`/first/last span the
  whole cache and accumulate across runs.

`scrape/RateLimiter.kt` paces history requests: one permit per page (Discord4J
fetches `Scraper.PAGE_SIZE` = 100 msgs/REST call), evenly spaced. Default
`Scraper.DEFAULT_RATE_PER_SECOND` = 1.0 req/s (~100 msg/s); override via
`SCRAPE_RATE_PER_SECOND` (env/.env), `<=0` = unlimited. Suspending the flow
collector backpressures the `Flux`, so it throttles the actual REST calls.

## Architecture (planned)

```
dev.overequal
  Main.kt                 CLI dispatcher (scrape/render headless) + bot launch
  bot/                    Bot, command registry, slash commands
  data/                   Models (serializable), MessageCache, Dataset (+ time period/stats)
  scrape/                 Scraper (Discord4J -> cache)
  redact/                 Redactor (names -> pseudonyms; content blanking)
  viz/                    Theme, chart toolkit (Java2D), Visualization registry, impls
  components/             Components V2 builders
```

## Charting with Kandy (gotchas learned)

Charts are built with the Kandy DSL and the Flexoki look in `viz/`:
- `viz/Theme.kt` — palette/gradients as Kandy `Color`s.
- `viz/ChartStyle.kt` — `Layout.flexoki(showLegend)` applies paper canvas, ink text,
  faint major grid (`#C3C1B8`), no minor grid, legend hidden by default.
- `viz/Render.kt` — `Plot.toPngBytes(scale, dpi)` via Lets-Plot `toBufferedImage` (no temp files).

Gotchas:
- The style sub-blocks (`global {}`, `legend {}`, `panel.grid {}`, `plotCanvas {}`) need
  `import org.jetbrains.kotlinx.kandy.util.context.invoke` (the `SelfInvocationContext` op).
- The Lets-Plot **style** translator rejects RGBA — style colours must be hex/named.
- `plot {}`/`layout {}`/`categorical`/`barsH` live in different packages — see Main.kt imports.
- Per-bar arbitrary colours: `fillColor(labels) { scale = categorical(*labels.zip(colors)) }`
  with the legend hidden. Discrete axis order isn't data order — set it explicitly to keep
  the most-active member at the top.
- Set `-Djava.awt.headless=true` before rendering.

Pixel-perfect parity with matplotlib (donut leader lines, dual fonts, RGBA heatmaps) is a
non-goal; faithful intent + the Flexoki theme is. Discord4J/serialization/Kandy are all
external deps per the user's preference.

## Secrets / running the bot

- The bot token lives in `.env` as `DISCORD_TOKEN` (gitignored). The bot loads it from
  there (or the `DISCORD_TOKEN` env var). Never commit `.env` or print the token.

## Known quirks

- Kandy 0.8.4 `pie` ignores theme axis-blanking (`Style.Void`/`blankAxes()` have no
  effect on the cartesian axes drawn around a pie) — the donut shows faint axes. Cosmetic;
  revisit later (possibly via a coord tweak or a newer Kandy).

## Status

**Feature-complete.** 22 corpus visualizations implemented and verified against a 60k
sample (top30, weekly_rate, share_pie, mention_ratio, hourly, message_length,
messages_per_day_dist, clt_daily, message_length_per_member, unique_members,
mention_scatter, spread_vs_rate, spread_distribution, mentions, hourly_grid, timeline,
weekly, cumulative_share, cumulative_absolute, top30_words, slurs_aggregate,
slurs_per_message). LLM charts (topics/sentiment/typos) and the word-cloud are out of scope.

Bot (`Bot.kt`): `/scrape /viz /viz-all /status`, Components V2 output, per-guild command
registration. Live-verified login + command registration with the real token. Scraping
message text needs MESSAGE_CONTENT enabled in the Developer Portal (else gateway 4014);
the bot always requests that intent. Headless CLI: `render`.

See `git log` for the running checkpoint history and `README.md` for setup/run.
