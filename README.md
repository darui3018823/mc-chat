# MCChat

Out-of-game chat for Minecraft: Java Edition — talk with friends who have the mod, even when in-game chat is unavailable to them.

```
MC:JE + MCChat mod ──wss──▶ Cloudflare ──▶ nginx ──▶ mcchat-server + SQLite
```

> **Japanese** — [日本語版はこちら](./japanese/README.md)

---

## Features

- **Separate chat path** — messages never go through vanilla chat, so they work even when in-game chat is restricted
- **Per-server channels** — you only talk with people connected to the same Minecraft server address
- **Chat window** — open with **Y**: log with scrolling, send history (↑↓), and command suggestions (Tab)
- **Romaji to Japanese (Japanize)** — LunaChat-style conversion: `ohayou` → `ohayou (おはよう)`, with a personal dictionary
- **Fonts** — Unifont, resource-pack fonts, or fonts installed on your PC, plus adjustable text size
- **Token auth** — tokens are issued by the admin; the server stores only their SHA-256 hashes
- **Encrypted storage** — message bodies are stored with AES-256-GCM

---

## Install

### Requirements

- Minecraft: Java Edition **1.21.11**
- Fabric Loader 0.18+ and **Fabric API** 0.141+
- Java 21+
- A token from the admin

Works alongside Meteor Client.

### Setup

1. Download `mcchat-<version>.jar` from [Releases](https://github.com/darui3018823/mc-chat/releases) and put it in `mods/` together with Fabric API
2. Launch the game once — `config/mcchat.json` is created
3. Write your token into `token`:
   ```json
   {
     "serverUrl": "wss://mcchat.daruks.com/ws",
     "token": "mcc_..."
   }
   ```
4. Join a server. MCChat connects automatically; press **Y** to open the chat window

> Usage, commands, Japanize, dictionary, fonts, and every config field: **[docs/USAGE.md](./docs/USAGE.md)**.

---

## Server

The Go server (`server/`) runs behind Cloudflare and nginx on a VPS, listening on `127.0.0.1:25566`.

```sh
cd server
export MCCHAT_KEY=$(go run ./cmd/mcchat-server keygen)
go run ./cmd/mcchat-server token add alice   # prints the token once
go run ./cmd/mcchat-server serve
```

> Configuration, token management, and VPS deployment: **[docs/SERVER.md](./docs/SERVER.md)**.

---

## Development

### Requirements

- Go 1.27.3+
- Java 25 (to run Gradle / Loom 1.18; the mod itself targets Java 21)

```sh
cd server && go test -race ./...
cd mod && ./gradlew build          # mod/build/libs/mcchat-<version>.jar
```

`ChatConnectionIT` in `mod/src/test` checks the connection against a running mcchat-server.
It runs only when `MCCHAT_IT_URL`, `MCCHAT_IT_TOKEN_A`, and `MCCHAT_IT_TOKEN_B` are set.

### Release

Bump `version` in `mod/gradle.properties`, commit, and push a matching tag.
GitHub Actions (`.github/workflows/release.yml`) builds the jar and attaches it to the release.
Release notes are written by Claude Haiku from the commits since the previous tag (`ANTHROPIC_API_KEY` secret); GitHub's generated notes are used if that fails.
Run the workflow manually with a tag to preview the notes without releasing.

```sh
git tag v1.0.1 && git push origin v1.0.1
```

---

## Tech Stack

- **Mod** — Java, Fabric (Loom, Mojang mappings), `java.net.http.WebSocket`
- **Server** — Go, [coder/websocket](https://github.com/coder/websocket), [modernc.org/sqlite](https://modernc.org/sqlite)
- **Infra** — Cloudflare, nginx, systemd

---

## License

BSD 3-Clause License — © 2026 Ahoshinet Labs / darui3018823  
See [LICENSE](./LICENSE) for details.

This project is not affiliated with Mojang Studios or Microsoft.  
See [NOTICE](./NOTICE) for third-party notices.
