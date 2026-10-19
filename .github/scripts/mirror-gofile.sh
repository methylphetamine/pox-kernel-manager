#!/usr/bin/env bash
#
# Mirrors one build artifact to Gofile and exposes the download page as
# GOFILE_PAGE (through $GITHUB_ENV when running in Actions). Used by both the
# compile and release jobs so branch pushes and tags each produce a link the
# phone can open directly.
#
# Usage: mirror-gofile.sh <file>
#
# The Gofile API hands out a rotating {server}.gofile.io upload host, so the
# server list is walked until one answers with a downloadPage. The md5 in the
# response is compared with a local md5sum before the link is trusted. Every
# failure path just warns: callers use continue-on-error, so a Gofile outage
# never fails a build.
#
set -uo pipefail

file="${1:-}"
if [ -z "$file" ] || [ ! -f "$file" ]; then
  echo "::warning::No file to mirror"
  exit 0
fi

local_md5="$(md5sum "$file" | awk '{print $1}')"
servers="$(curl -sS --max-time 60 https://api.gofile.io/servers \
  | python3 -c "import json,sys;print(' '.join(s['name'] for s in json.load(sys.stdin)['data']['servers']))" 2>/dev/null || true)"
if [ -z "$servers" ]; then
  echo "::warning::Gofile server list unavailable"
  exit 0
fi

resp=""
for server in $servers; do
  echo "Gofile server: $server"
  resp="$(curl -sS --max-time 900 -F "file=@${file}" "https://${server}.gofile.io/contents/uploadfile" || true)"
  if grep -q '"downloadPage"' <<<"$resp"; then
    break
  fi
  resp=""
done

if [ -z "$resp" ]; then
  echo "::warning::Gofile mirror upload failed"
  exit 0
fi

page="$(python3 -c "import json,sys;print(json.load(sys.stdin)['data']['downloadPage'])" <<<"$resp")"
remote_md5="$(python3 -c "import json,sys;print(json.load(sys.stdin)['data']['md5'])" <<<"$resp")"

echo "Gofile mirror page: $page"
if [ -n "${GITHUB_ENV:-}" ]; then
  echo "GOFILE_PAGE=$page" >> "$GITHUB_ENV"
fi
if [ "$remote_md5" != "$local_md5" ]; then
  echo "::warning::Gofile md5 mismatch (local=${local_md5} remote=${remote_md5})"
else
  echo "md5 verified: ${local_md5}"
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### Gofile mirror"
    echo ""
    echo "- **Download page**: $page"
    echo "- **md5**: \`$local_md5\`"
  } >> "$GITHUB_STEP_SUMMARY"
fi
