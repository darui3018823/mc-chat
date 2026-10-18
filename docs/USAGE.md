# Usage

> **Japanese** — [日本語版はこちら](./USAGE.ja.md)

- [Chat window](#chat-window)
- [Chat HUD](#chat-hud)
- [Commands](#commands)
- [Japanize](#japanize)
- [Dictionary](#dictionary)
- [Fonts](#fonts)
- [Config file](#config-file)

---

## Chat window

MCChat connects automatically when you join a server. Press **Y** (rebindable in Controls) to open the chat window, and Enter to send.

| Key | Action |
|---|---|
| Enter | Send |
| ↑ / ↓ | Browse sent messages |
| Wheel / PageUp / PageDown | Scroll the log |
| Tab | Complete the first suggestion |
| Esc | Clear the input; close when it is empty |

- The top line shows the connection status, channel, and your display name
- The vanilla chat HUD is hidden while the window is open
- If a message is rejected for being sent too fast, a notice appears above the input and the text is put back
- Players who can use vanilla chat can also send with `/mcc <text>`

## Chat HUD

Received messages also appear in the vanilla chat HUD, in your PC's time zone:

```
[MCChat][2026/10/18 12:00] name: text
```

- History (last 50 messages) is shown only in the chat window, not dumped into the HUD on login
- After a reconnect, only the messages missed while disconnected (up to 10) are shown in the HUD
- Set `showInChatHud` to `false` to keep MCChat out of the HUD

---

## Commands

Type these in the chat window (or as `/mcc /...`). Typing `/` shows suggestions.

| Command | Action |
|---|---|
| `/jp <text>` | Convert the whole text from romaji and send it |
| `/japanize [on\|off]` | Toggle romaji conversion |
| `/dict add <word> <replacement>` | Add a dictionary entry |
| `/dict remove <word>` | Remove a dictionary entry |
| `/dict list` | List the dictionary |
| `/font default` | Back to the default font |
| `/font uniform` | Use Unifont |
| `/font system <file> [4-32]` | Use a font installed on your PC |
| `/font size <50-200>` | Chat window text size (%) |
| `/font <namespace:id>` | Use a resource-pack font |

To send text that starts with `/`, write `//` (`//help` → `/help`).

---

## Japanize

Like LunaChat, romaji is converted to Japanese before sending:

```
ohayougozaimasu!  →  ohayougozaimasu! (おはようございます！)
```

- On by default. Toggle with `/japanize`; saved as `japanize`
- Text that already contains Japanese is not converted
- Judged per word: words that are not romaji (`Google`, `OK`), all-caps words, and URLs are kept as is
- `/jp <text>` converts everything, even when `/japanize` is off
- Kanji conversion uses Google's transliteration API, so **the text is sent to Google**. On failure the kana is sent

## Dictionary

Registered words are replaced as is during conversion, like the LunaChat dictionary. Saved per PC in `dictionary` of `config/mcchat.json`.

```
/dict add maikura マイクラ     # kyou maikura yarou → kyou maikura yarou (今日マイクラやろう)
/dict remove maikura
/dict list
```

Matching is case-insensitive, anywhere in the text, longest word first.

---

## Fonts

Font settings apply only to MCChat lines (the chat window and the HUD).

### Text size

`/font size <50-200>` sets the chat window text size in percent, for any font.
The HUD follows vanilla's Chat Settings → Text Size.

### Fonts on your PC

`/font system <file>` uses a font from `C:\Windows\Fonts` and similar folders. Type `/font system ` to see suggestions.

```
/font system meiryo
/font system yugothm
/font system bizudgothicr 12    # glyph size (4-32, default 11)
```

- The mod copies the font into a resource pack at `resourcepacks/mcchat-system-font`, enables it, and reloads resources (takes a few seconds)
- For `.ttc` files, the first font in the collection is used
- The glyph size also applies to the HUD. Raise `systemFontOversample` (default 4) for sharper text
- Fonts activated through Adobe Fonts (Creative Cloud) are not supported
- Use `/font default` to go back. The pack can be removed from the Resource Packs screen

### Resource-pack fonts

Use `/font <namespace:id>`. For example, with a TTF, put these two files in a resource pack and run `/font mypack:chat`:

```
assets/mypack/font/chat.json:
  {"providers": [{"type": "ttf", "file": "mypack:chat.ttf", "size": 11, "oversample": 2, "shift": [0, 0]}]}
assets/mypack/font/chat.ttf
```

An unknown id makes the text invisible; use `/font default` to recover.

---

## Config file

`config/mcchat.json` is re-read every time you join a server. Settings changed by commands are saved here too.

| Field | Default | Description |
|---|---|---|
| `serverUrl` | `wss://mcchat.daruks.com/ws` | Server endpoint |
| `token` | `""` | Token from the admin |
| `showInChatHud` | `true` | Also show messages in the chat HUD |
| `channelOverride` | `""` | Use this channel in every world, including singleplayer (for testing) |
| `japanize` | `true` | Romaji conversion |
| `dictionary` | `{}` | Dictionary |
| `font` | `""` | Font id (empty for the default) |
| `systemFont` | `""` | File chosen with `/font system` |
| `systemFontSize` | `11` | Glyph size of the PC font |
| `systemFontOversample` | `4` | Sharpness of the PC font |
| `textScale` | `100` | Chat window text size (%) |
