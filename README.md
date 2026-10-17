# mc-chat

ゲーム内チャットとは別経路で、Mod を入れた仲間同士がチャットするための仕組み。

```
MC:JE + mcchat Mod ──wss──▶ Cloudflare ──▶ nginx ──▶ mcchat-server (127.0.0.1:25566) + SQLite
```

- チャンネルは「接続中の Minecraft サーバーアドレス」。`mc.daruks.com` にいる人同士だけで会話する
- 認証は管理者が発行するトークン（DB には SHA-256 のみ保存）
- メッセージ本文は AES-256-GCM で暗号化して保存（鍵は `MCCHAT_KEY`）

| ディレクトリ | 内容 |
|---|---|
| `server/` | Go 製サーバー `mcchat-server` と動作確認用 `mcchat-cli` |
| `mod/` | Fabric クライアント Mod（MC 1.21.11 / Fabric API 0.141+ / Java 21） |
| `deploy/` | VPS 用セットアップスクリプトと nginx 設定 |

## Mod の使い方

1. `mcchat-0.1.0.jar` を Fabric API と一緒に `mods/` へ入れる（Meteor と併用可）
2. 一度起動すると `config/mcchat.json` ができるので、`token` に発行されたトークンを書く
   ```json
   {
     "serverUrl": "wss://mcchat.daruks.com/ws",
     "token": "mcc_...",
     "showInChatHud": true,
     "channelOverride": ""
   }
   ```
3. サーバーに入ると自動接続。**Y キー**（操作設定で変更可）でチャット画面を開き、Enter で送信
   - 受信内容はチャット欄にも `[MCChat] 名前: 本文` で表示される
   - バニラのチャットが使える人は `/mcc 本文` でも送れる
   - 設定ファイルはサーバーに入るたびに読み直す

`channelOverride` を設定すると、シングルプレイを含むどのワールドでもそのチャンネルを使う（テスト用）。

## サーバー

### ローカルで動かす

```sh
cd server
export MCCHAT_KEY=$(go run ./cmd/mcchat-server keygen)
go run ./cmd/mcchat-server token add alice       # トークンが一度だけ表示される
go run ./cmd/mcchat-server serve                 # 127.0.0.1:25566
go run ./cmd/mcchat-cli -token mcc_... -channel mc.daruks.com
```

設定は `-config config.json`（無ければ既定値）。項目は `server/config.example.json` を参照。
`aliases` で別名アドレスを同じチャンネルにまとめられる。

### トークン管理

```sh
mcchat-server token add <名前>    # 名前は表示名。16文字まで
mcchat-server token list
mcchat-server token revoke <id>   # 接続中のクライアントも次の送信で切断される
```

### デプロイ（VPS）

前提: Go・pwsh・nginx、Cloudflare プロキシ（SSL Full）、Origin 証明書 `/etc/ssl/certs/cf.crt` / `/etc/ssl/private/cf.key`、Cloudflare の WebSockets 有効。

リポジトリを `/root/projects/mcchat` に置いて、VPS 上で実行する（再実行可）:

```sh
cd /root/projects/mcchat
pwsh ./deploy/setup.ps1                 # ビルド → systemd(mcchat) 登録・再起動 → nginx サイト配置・reload
./mcchat-server token add <名前>         # トークン発行
journalctl -u mcchat -f                 # ログ
```

初回は `.env` に暗号鍵が生成される。**このファイルを必ずバックアップすること**（失うと保存済みメッセージは復号できない）。

| パス（`/root/projects/mcchat/`） | 用途 |
|---|---|
| `mcchat-server` | バイナリ |
| `config.json` | 設定（初回のみ example からコピー） |
| `.env` | `MCCHAT_KEY`（0600） |
| `mcchat.db` | SQLite |

鍵の入れ替え: 新しい鍵を `MCCHAT_KEY`・新しい `MCCHAT_KEY_ID` に設定し、旧鍵を `MCCHAT_OLD_KEYS="1:<旧鍵>"` に移す。

## 開発

```sh
cd server && go test -race ./...

cd mod && ./gradlew build          # build/libs/mcchat-*.jar
```

Gradle（Loom 1.18）の実行には **Java 25** が必要（`gradle/gradle-daemon-jvm.properties` で自動選択。Mod 自体は Java 21 向け）。

`mod/src/test` の `ChatConnectionIT` は、起動中の mcchat-server に対して接続処理を検証する。
`MCCHAT_IT_URL` / `MCCHAT_IT_TOKEN_A` / `MCCHAT_IT_TOKEN_B` を設定したときだけ実行される。
