# MCChat

Minecraft: Java Edition 用のゲーム外チャットです。ゲーム内チャットが使えない人とも、Mod を入れた仲間同士で会話できます。

```
MC:JE + MCChat Mod ──wss──▶ Cloudflare ──▶ nginx ──▶ mcchat-server + SQLite
```

> **English** — [English version](../README.md)

---

## 特長

- **別経路のチャット** — バニラのチャットを通さないので、ゲーム内チャットが制限されていても使えます
- **サーバーごとのチャンネル** — 同じ Minecraft サーバーアドレスに接続している人同士だけで会話します
- **チャット画面** — **Y** キーで開きます。ログのスクロール、送信履歴（↑↓）、コマンド候補（Tab）に対応しています
- **ローマ字変換（Japanize）** — LunaChat と同じく `ohayou` → `ohayou (おはよう)` のように変換します。個人用の辞書も使えます
- **フォント** — Unifont、リソースパックのフォント、PC に入っているフォントを使えます。文字の大きさも変えられます
- **トークン認証** — トークンは管理者が発行します。サーバーには SHA-256 のハッシュだけを保存します
- **暗号化して保存** — メッセージ本文は AES-256-GCM で暗号化して保存します

---

## 導入

### 必要なもの

- Minecraft: Java Edition **1.21.11**
- Fabric Loader 0.18 以上と **Fabric API** 0.141 以上
- Java 21 以上
- 管理者から受け取ったトークン

Meteor Client と一緒に入れても動きます。

### 手順

1. [Releases](https://github.com/darui3018823/mc-chat/releases) から `mcchat-<version>.jar` をダウンロードし、Fabric API と一緒に `mods/` に入れます
2. 一度ゲームを起動すると `config/mcchat.json` ができます
3. `token` にトークンを書きます
   ```json
   {
     "serverUrl": "wss://mcchat.daruks.com/ws",
     "token": "mcc_..."
   }
   ```
4. サーバーに入ると自動で接続します。**Y** キーでチャット画面を開きます

> 使い方、コマンド、ローマ字変換、辞書、フォント、設定項目の一覧：**[docs/USAGE.ja.md](../docs/USAGE.ja.md)**

---

## サーバー

Go 製のサーバー（`server/`）を VPS 上で Cloudflare と nginx の後ろに置き、`127.0.0.1:25566` で待ち受けます。

```sh
cd server
export MCCHAT_KEY=$(go run ./cmd/mcchat-server keygen)
go run ./cmd/mcchat-server token add alice   # トークンが一度だけ表示されます
go run ./cmd/mcchat-server serve
```

> 設定、トークン管理、VPS へのデプロイ：**[docs/SERVER.ja.md](../docs/SERVER.ja.md)**

---

## 開発

### 必要なもの

- Go 1.26 以上
- Java 25（Gradle / Loom 1.18 の実行用。Mod 自体は Java 21 向けにビルドします）

```sh
cd server && go test -race ./...
cd mod && ./gradlew build          # mod/build/libs/mcchat-<version>.jar
```

`mod/src/test` の `ChatConnectionIT` は、起動中の mcchat-server に対して接続処理を確認します。
`MCCHAT_IT_URL`、`MCCHAT_IT_TOKEN_A`、`MCCHAT_IT_TOKEN_B` を設定したときだけ実行されます。

### リリース

`mod/gradle.properties` の `version` を上げてコミットし、同じ番号のタグを push します。
GitHub Actions（`.github/workflows/release.yml`）が jar をビルドして Release に添付します。

```sh
git tag v1.0.1 && git push origin v1.0.1
```

---

## 技術スタック

- **Mod** — Java、Fabric（Loom、Mojang マッピング）、`java.net.http.WebSocket`
- **サーバー** — Go、[coder/websocket](https://github.com/coder/websocket)、[modernc.org/sqlite](https://modernc.org/sqlite)
- **インフラ** — Cloudflare、nginx、systemd

---

## ライセンス

MIT License — © 2026 darui3018823  
詳しくは [LICENSE](../LICENSE) を参照してください。

このプロジェクトは Mojang Studios および Microsoft とは関係ありません。  
第三者の権利表記は [NOTICE](../NOTICE) を参照してください。
