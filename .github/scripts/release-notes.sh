#!/usr/bin/env bash
# Writes release notes for tag $1 to stdout: a summary by Claude Haiku from the
# commits since the previous tag, followed by the commit list itself.
# Exits non-zero if the summary cannot be produced, so the caller can fall back.
set -euo pipefail

tag=$1
prev=$(git describe --tags --abbrev=0 "$tag^" 2>/dev/null || true)
range=${prev:+$prev..}$tag
commits=$(git log --no-merges --reverse --format='- %s (%h)' "$range")
details=$(git log --no-merges --reverse --format='### %s%n%n%b' "$range")

system='You write release notes for MCChat, a Fabric client mod for Minecraft that adds a chat outside the game chat.
The readers are players who install the mod, not developers.
Write in Japanese, in Markdown, starting with "## 変更点" and grouping items under "### 新機能", "### 修正", "### その他" (omit empty groups).
Describe only what the commits say, in terms of what players see or can do. Do not invent features, numbers or reasons.
Leave out changes that do not affect players (CI, refactoring, tests, server deployment) unless nothing else is left.
Output only the notes.'

request=$(jq -n --arg system "$system" --arg tag "$tag" --arg prev "${prev:-none}" --arg details "$details" '{
  model: "claude-haiku-5-5",
  max_tokens: 4000,
  output_config: {effort: "low"},
  system: $system,
  messages: [{role: "user", content: "Release \($tag) (previous: \($prev)). Commits:\n\n\($details)"}]
}')

response=$(curl -sS --fail-with-body https://api.anthropic.com/v1/messages \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d "$request")

stop=$(jq -r .stop_reason <<<"$response")
summary=$(jq -r '[.content[] | select(.type == "text") | .text] | join("")' <<<"$response")
if [ "$stop" != "end_turn" ] || [ -z "$summary" ]; then
  echo "release-notes: no usable summary (stop_reason=$stop)" >&2
  exit 1
fi

printf '%s\n\n<details>\n<summary>Commits</summary>\n\n%s\n\n</details>\n' "$summary" "$commits"
if [ -n "$prev" ]; then
  printf '\n**Full Changelog**: https://github.com/%s/compare/%s...%s\n' "$GITHUB_REPOSITORY" "$prev" "$tag"
fi
