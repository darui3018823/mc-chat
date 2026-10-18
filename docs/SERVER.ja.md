# サーバー

> **English** — [English version](./SERVER.md)

---

## ローカルで動かす

```sh
cd server
export MCCHAT_KEY=$(go run ./cmd/mcchat-server keygen)
go run ./cmd/mcchat-server token add alice       # トークンが一度だけ表示されます
go run ./cmd/mcchat-server serve                 # 127.0.0.1:25566
go run ./cmd/mcchat-cli -token mcc_... -channel mc.daruks.com
```

`mcchat-cli` は動作確認用のクライアントです。

## 設定

`-config config.json` で指定します（ファイルがなければ既定値）。例は [`server/config.example.json`](../server/config.example.json) です。

| 項目 | 既定値 | 内容 |
|---|---|---|
| `listen` | `127.0.0.1:25566` | 待ち受けアドレス |
| `db` | `mcchat.db` | SQLite のパス |
| `history` | `50` | 接続時に送る過去ログの件数 |
| `maxRunes` | `256` | 本文の最大文字数 |
| `rateBurst` / `ratePerSec` | `3` / `1` | 送信の回数制限（トークンごと） |
| `aliases` | `{}` | 別名アドレスを同じチャンネルにまとめる |

チャンネル名は Minecraft サーバーのホスト名（小文字、ポート 25565 は省略）です。

---

## トークン管理

```sh
mcchat-server token add <名前>    # 名前は表示名。16 文字まで

# トークンを VPS の画面や履歴に出したくない場合：手元で生成してハッシュだけ登録します
mcchat-server token gen                       # （手元）token と hash を表示
mcchat-server token add -hash <hash> <名前>    # （VPS）
mcchat-server token list
mcchat-server token revoke <id>   # 接続中のクライアントも次の送信で切断されます
```

---

## デプロイ（VPS）

前提：Go・pwsh・nginx、Cloudflare プロキシ（SSL Full）、Origin 証明書 `/etc/ssl/certs/cf.crt` / `/etc/ssl/private/cf.key`、Cloudflare の WebSockets 有効。

リポジトリを `/root/projects/mcchat` に置いて、VPS 上で実行します（何度実行しても大丈夫です）。

```sh
cd /root/projects/mcchat
pwsh ./deploy/setup.ps1                 # ビルド → systemd(mcchat) 登録・再起動 → nginx サイト配置・reload
./mcchat-server token add <名前>         # トークン発行
journalctl -u mcchat -f                 # ログ
```

初回は `.env` に暗号鍵が作られます。**このファイルは必ずバックアップしてください**（失うと保存済みメッセージを復号できません）。

| パス（`/root/projects/mcchat/`） | 用途 |
|---|---|
| `mcchat-server` | バイナリ |
| `config.json` | 設定（初回のみ example からコピー） |
| `.env` | `MCCHAT_KEY`（0600） |
| `mcchat.db` | SQLite |

### 鍵の入れ替え

新しい鍵を `MCCHAT_KEY`、新しい ID を `MCCHAT_KEY_ID` に設定し、旧鍵を `MCCHAT_OLD_KEYS="1:<旧鍵>"` に移します。
