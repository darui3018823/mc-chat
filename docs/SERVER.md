# Server

> **Japanese** — [日本語版はこちら](./SERVER.ja.md)

---

## Run locally

```sh
cd server
export MCCHAT_KEY=$(go run ./cmd/mcchat-server keygen)
go run ./cmd/mcchat-server token add alice       # prints the token once
go run ./cmd/mcchat-server serve                 # 127.0.0.1:25566
go run ./cmd/mcchat-cli -token mcc_... -channel mc.daruks.com
```

`mcchat-cli` is a client for testing.

## Configuration

Pass `-config config.json` (defaults are used if the file does not exist). See [`server/config.example.json`](../server/config.example.json).

| Field | Default | Description |
|---|---|---|
| `listen` | `127.0.0.1:25566` | Listen address |
| `db` | `mcchat.db` | SQLite path |
| `history` | `50` | Messages sent as history on join |
| `maxRunes` | `256` | Max message length |
| `rateBurst` / `ratePerSec` | `3` / `1` | Send rate limit per token |
| `aliases` | `{}` | Map alternative addresses to one channel |

A channel is the Minecraft server host name (lowercase, port 25565 omitted).

---

## Tokens

```sh
mcchat-server token add <name>    # display name, up to 16 characters

# To keep the token off the VPS screen and history: generate it locally and register only the hash
mcchat-server token gen                       # (local) prints the token and hash
mcchat-server token add -hash <hash> <name>   # (VPS)
mcchat-server token list
mcchat-server token revoke <id>   # connected clients are dropped on their next send
```

---

## Deploy (VPS)

Prerequisites: Go, pwsh, nginx, Cloudflare proxy (SSL Full), an Origin certificate at `/etc/ssl/certs/cf.crt` / `/etc/ssl/private/cf.key`, and WebSockets enabled in Cloudflare.

Put the repository at `/root/projects/mcchat` and run on the VPS (safe to re-run):

```sh
cd /root/projects/mcchat
pwsh ./deploy/setup.ps1                 # build → register/restart systemd (mcchat) → install nginx site and reload
./mcchat-server token add <name>         # issue a token
journalctl -u mcchat -f                 # logs
```

The first run generates the encryption key in `.env`. **Back this file up** — without it, stored messages cannot be decrypted.

| Path (`/root/projects/mcchat/`) | Purpose |
|---|---|
| `mcchat-server` | Binary |
| `config.json` | Config (copied from the example on the first run only) |
| `.env` | `MCCHAT_KEY` (0600) |
| `mcchat.db` | SQLite |

### Key rotation

Set the new key as `MCCHAT_KEY` with a new `MCCHAT_KEY_ID`, and move the old key to `MCCHAT_OLD_KEYS="1:<old key>"`.
