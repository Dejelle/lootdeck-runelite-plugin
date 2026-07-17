# LootDeck RuneLite plugin

> **Unofficial fan project. Not affiliated with, endorsed by, or sponsored by Jagex.**

Earn a collectible trading card while you play Old School RuneScape. The plugin is **cosmetic
and read-only**: it reports qualifying gameplay to the LootDeck service and shows a drop when the
server awards a pack. It does **not** automate anything or affect gameplay.

## Development

Day-to-day development happens in the main LootDeck project (a private monorepo that also
holds the website and API); this repository tracks **Plugin Hub releases** — each release
lands here as a single sync commit. Issues and PRs are welcome here, but fixes are usually
ported into the main project and flow back through the next release commit.

## Network usage disclosure (Plugin Hub requirement)

- **Opt-in:** nothing is sent until you link your account (paste a code from the website).
- **What is sent** to `https://api-production-decd.up.railway.app`:
  - Your OSRS *account hash* (`getAccountHash()`, a non-reversible number) and display name.
  - A gameplay event when you do a qualifying activity: an activity id (e.g. `KILL_ZULRAH`,
    `CLUE_HARD`) or, for gathering, a resource **item id + quantity**. Plus a timestamp and a
    random idempotency id.
  - Your linked account's low-privilege bearer token (issued at link time) and a plugin version
    header on each request.
  - Your current world number, only when you submit a bug report from the plugin.
- **What is NOT sent:** no passwords, no bank contents, no chat, no location, no automation.
- **No gameplay effect, no real-money value.** Cards are cosmetic collectibles.
- **Standard profile only:** Leagues/DMM/beta worlds are ignored.
- All reporting runs off the client thread; you can disable the sound/animation in config, and
  revoke the token from the website at any time.
- **Token storage:** the bearer token lives in RuneLite's per-profile config, stored **device-local
  in plaintext** — a RuneLite limitation shared by every plugin that holds an API key. It is
  low-privilege (report drops; read/open *your* packs) and can be revoked anytime from the website's
  **Link** page, which invalidates it server-side immediately.

## Donations

The side panel has a **Donate** button that opens `https://ko-fi.com/lootdeck`. Donations are
voluntary, keep the servers running, and **grant nothing in-game** — no packs, no cards, no odds
changes. There are no paid or "premium" plugin features; everything works for free.

## Build

Requires JDK **11** (Temurin). From the repo root:

```bash
./gradlew build            # produces build/libs/*.jar
```

## Run it locally (test before Plugin Hub submission)

RuneLite loads external plugins through the Plugin Hub — a stock client won't load a loose jar.
To test your own plugin you run RuneLite **in developer mode with the plugin loaded**, via the
included launcher `src/test/java/com/lootdeck/tcg/TcgPluginTest.java`:

```java
ExternalPluginManager.loadBuiltin(TcgPlugin.class);
RuneLite.main(args);
```

**From the command line (no IDE):**

```bash
./gradlew run              # launches RuneLite with LootDeck loaded (assertions enabled)
```

**In IntelliJ IDEA:**

1. `File → Open` this repo (import as a Gradle project); set the Project SDK to JDK 11.
2. Open `TcgPluginTest`, click the green ▶ next to `main`, choose **Run** (or Debug).
3. RuneLite launches with **LootDeck** already enabled. Log into OSRS, open the LootDeck side
   panel, and paste a 6-char link code from the website's **Link** page.

The plugin talks to the production API (a fixed endpoint — not a user-facing config field). To
point it at a local server during development, pass the JVM flag `-Dlootdeck.apiBase=http://localhost:4000`.

## Plugin Hub submission

This repository is the standalone plugin source referenced by a manifest entry in
[`runelite/plugin-hub`](https://github.com/runelite/plugin-hub). To submit, add a file
`plugins/lootdeck` there containing this repo's URL and a pinned commit hash, then open a PR whose
description includes the network-usage disclosure above. CI runs `./gradlew build`.

## License

BSD 2-Clause — see [LICENSE](LICENSE).
