# overequal

A Discord bot (Kotlin + [Discord4J](https://github.com/Discord4J/Discord4J)) that
**scrapes a server's messages, caches them, and renders data visualizations** with
[Kandy](https://github.com/Kotlin/kandy), posted back as **Components V2** messages.

It is a Kotlin re-implementation of the visualizations in the reference Python project
under `./datavis` (kept only as a reference). Charts use the Flexoki theme from that
project; every chart's subtitle reflects the **time period of the cached data**.

## Requirements

- JDK 21+ (developed on JDK 26).
- A Discord bot application + token.

## Setup

1. Put your token in `.env` (gitignored) at the repo root:

   ```
   DISCORD_TOKEN=your-bot-token
   # optional: history requests/second for /scrape (Discord4J fetches ~100
   # messages per request). Default 1.0 (~100 msg/s); <=0 means unlimited.
   SCRAPE_RATE_PER_SECOND=1.0
   ```

2. In the [Discord Developer Portal](https://discord.com/developers/applications) for your
   app: **Bot → Privileged Gateway Intents → enable "Message Content Intent"**. Without it
   the gateway rejects the connection with `4014`, since the bot requests that intent so the
   scraper can read message text.

3. Invite the bot with the `bot` and `applications.commands` scopes and at least
   *View Channels* + *Read Message History* permissions.

## Run

**Bot:**

```bash
./gradlew run --args="bot"
# or build a runnable image:
./gradlew installDist && ./build/install/overequal/bin/overequal bot
```

Slash commands are registered per-guild on join (instant, works in any server):

| Command | Description |
| --- | --- |
| `/scrape [channel] [limit]` | Scrape & cache the server's messages (all channels, or one; optional cap). |
| `/viz <name> [exclude_bots] [redact_names] [redact_content]` | Render one visualization. |
| `/viz-all [exclude_bots] [redact_names] [redact_content]` | Render every visualization. |
| `/status` | Show what's cached for this server. |

Visualizations read from the **cache**, not a live re-scrape — run `/scrape` once, then
`/viz` / `/viz-all` as often as you like.

**Redaction / options (server-agnostic):**

- `redact_names` — replace member names everywhere with stable pseudonyms (`member_001`…).
- `redact_content` — hide message text (length-based charts still work; word/slur charts
  are skipped with a notice).
- `exclude_bots` — drop bot accounts (and their mentions) from the analysis.

## Headless rendering (no Discord)

Render straight from any `merged.jsonl`-shaped corpus (including the reference one) to PNGs:

```bash
./gradlew run --args="render --input data/<guild>/messages.jsonl --out out --viz all \
  --guild 'My Server' [--redact-names] [--redact-content] [--exclude-bots]"
# single chart: --viz top30
```

## Visualizations

`top30`, `weekly_rate`, `share_pie`, `mention_ratio`, `hourly`, `message_length`,
`messages_per_day_dist`, `clt_daily`, `message_length_per_member`, `unique_members`,
`mention_scatter`, `spread_vs_rate`, `spread_distribution`, `mentions`, `hourly_grid`,
`timeline`, `weekly`, `cumulative_share`, `cumulative_absolute`, `top30_words`,
`slurs_aggregate`, `slurs_per_message`.

(The reference's LLM-classified charts — topics / sentiment / typos — and the word-cloud are
out of scope here: this bot derives everything from the cached corpus alone.)

## Development

- Format: `./gradlew spotlessApply` (ktlint).
- Build: `./gradlew build`.
- See `AGENTS.md` for architecture, the Components V2 API notes, and known quirks.
