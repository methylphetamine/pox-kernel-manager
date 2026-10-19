#!/usr/bin/env bash
#
# Sends a single Telegram message per build: the APK as a document with the
# build summary as its caption. When the upload cannot happen (missing APK,
# file above Telegram's 50 MB document limit, or a rejected upload) it falls
# back to a text-only message carrying the same information - never a second
# message, so one build is always one message per chat.
#
# Usage: notify-telegram.sh <apk-path> <title>
#
# Secrets:
#   TELEGRAM_BOT_TOKEN  required; without it the step skips with a warning
#   TELEGRAM_CHAT_ID    optional, comma/space separated list of chat ids.
#                       When unset the chat is discovered from the bot's
#                       recent updates (same fallback the kernel CI uses).
#
set -uo pipefail

token="${TELEGRAM_BOT_TOKEN:-}"
apk_path="${1:-}"
title="${2:-Pox Kernel Manager}"

if [ -z "$token" ]; then
  echo "::warning::TELEGRAM_BOT_TOKEN not configured - skipping Telegram notification"
  exit 0
fi

api="https://api.telegram.org/bot${token}"

# Resolve the most recent group (or any chat) that messaged the bot.
resolve_chat() {
  curl -sS --max-time 30 "${api}/getUpdates?limit=100" | python3 -c '
import json, sys
try:
    updates = json.load(sys.stdin).get("result", [])
except Exception:
    sys.exit(0)
chats = {}
for update in updates:
    msg = update.get("message") or update.get("edited_message") or update.get("channel_post") or update.get("my_chat_member") or {}
    chat = msg.get("chat") or {}
    if chat.get("id") is not None:
        chats[chat["id"]] = chat.get("type")
groups = [cid for cid, kind in chats.items() if kind in ("group", "supergroup")]
print(groups[-1] if groups else (list(chats)[-1] if chats else ""))
'
}

chat_ids="${TELEGRAM_CHAT_ID:-}"
if [ -z "$chat_ids" ]; then
  chat_ids="$(resolve_chat || true)"
fi
if [ -z "$chat_ids" ]; then
  echo "::warning::No Telegram chat found (bot not in a group / no recent updates; set TELEGRAM_CHAT_ID) - skipping"
  exit 0
fi

apk_name=""
apk_size=""
apk_sha=""
if [ -n "$apk_path" ] && [ -f "$apk_path" ]; then
  apk_name="$(basename "$apk_path")"
  apk_size="$(du -h "$apk_path" | awk '{print $1}')"
  apk_sha="$(sha256sum "$apk_path" | awk '{print $1}')"
fi

run_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-}/actions/runs/${GITHUB_RUN_ID:-}"
message="$(printf '%s\nRef: %s\nCommit: %.7s\nAPK: %s (%s)\nSHA256: %s\nRun: %s\n' \
  "$title" "${GITHUB_REF_NAME:-unknown}" "${GITHUB_SHA:-unknown}" \
  "${apk_name:-not attached}" "${apk_size:-n/a}" "${apk_sha:-n/a}" "$run_url")"

IFS=', ' read -r -a chats <<< "$chat_ids"

for chat_id in "${chats[@]}"; do
  [ -n "$chat_id" ] || continue
  sent=0

  if [ -n "$apk_path" ] && [ -f "$apk_path" ] && [ "$(stat -c%s "$apk_path")" -le 50000000 ]; then
    curl -sS --max-time 600 \
      -F "chat_id=${chat_id}" \
      -F "document=@${apk_path}" \
      -F "caption=${message}" \
      "${api}/sendDocument" > /tmp/tg_doc.json || true
    grep -q '"ok":true' /tmp/tg_doc.json 2>/dev/null && sent=1
    if [ "$sent" -ne 1 ]; then
      echo "::warning::Telegram sendDocument rejected for ${chat_id} - falling back to a text message"
    fi
  else
    echo "::warning::APK missing or above the Telegram 50 MB document limit - sending text only"
  fi

  if [ "$sent" -ne 1 ]; then
    curl -sS --max-time 60 -X POST "${api}/sendMessage" \
      --data-urlencode "chat_id=${chat_id}" \
      --data-urlencode "text=${message}" \
      --data-urlencode "disable_web_page_preview=true" > /tmp/tg_msg.json || true
    grep -q '"ok":true' /tmp/tg_msg.json 2>/dev/null || echo "::warning::Telegram sendMessage failed for ${chat_id}"
  fi
done
