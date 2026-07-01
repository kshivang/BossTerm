#!/usr/bin/env bash
#
# publish-supabase-release.sh
#
# Publishes a desktop-app release to Supabase so clients can update via the
# Supabase-primary path (with GitHub as the automatic backup):
#   1. uploads each platform binary to Supabase Storage
#         <bucket>/<app>/<version>/<asset>
#   2. upserts a row into the `app_releases` table (which fires Supabase Realtime,
#      pushing the new release to every running client).
#
# Large installers (the macOS .dmg is ~160 MB) exceed Cloudflare's ~100 MB
# single-request body limit — Cloudflare fronts Supabase and rejects the upload
# with HTTP 413 before it reaches Storage. Such assets are uploaded via the TUS
# resumable protocol in 6 MiB chunks instead, so no single request is too large.
#
# Usage:
#   publish-supabase-release.sh <app> <version> <channel> <asset_dir> <bucket>
#
# Example:
#   publish-supabase-release.sh boss 9.2.17 stable release-assets app-releases
#
# Required environment:
#   SUPABASE_URL                e.g. https://api.risaboss.com
#   SUPABASE_SERVICE_ROLE_KEY   service-role key (CI secret; bypasses RLS). Never ship in the client.
#
# Only files with known installer extensions (.dmg .msi .deb .rpm .jar) in
# <asset_dir> are uploaded. Re-running for the same (app, version) upserts.

set -euo pipefail

APP="${1:?usage: publish-supabase-release.sh <app> <version> <channel> <asset_dir> <bucket>}"
VERSION="${2:?missing version}"
CHANNEL="${3:?missing channel}"
ASSET_DIR="${4:?missing asset_dir}"
BUCKET="${5:?missing bucket}"

: "${SUPABASE_URL:?SUPABASE_URL must be set}"
: "${SUPABASE_SERVICE_ROLE_KEY:?SUPABASE_SERVICE_ROLE_KEY must be set}"

SUPABASE_URL="${SUPABASE_URL%/}"  # strip trailing slash
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required" >&2; exit 1; }

# Scratch space for resumable-upload chunks; cleaned up on exit.
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

content_type_of() {
  case "$1" in
    *.dmg) echo "application/x-apple-diskimage" ;;
    *.msi) echo "application/x-msi" ;;
    *.deb) echo "application/vnd.debian.binary-package" ;;
    *.rpm) echo "application/x-rpm" ;;
    *.jar) echo "application/java-archive" ;;
    *)     echo "application/octet-stream" ;;
  esac
}

# --- Storage upload -----------------------------------------------------------
# Cloudflare fronts Supabase and rejects any single request body over ~100 MB
# with HTTP 413, so files above the threshold are streamed via the TUS resumable
# endpoint in fixed 6 MiB chunks. Everything else uses the simpler single POST.
CHUNK_SIZE=$((6 * 1024 * 1024))            # 6 MiB — the only chunk size Supabase's TUS server accepts.
RESUMABLE_THRESHOLD=$((50 * 1024 * 1024))  # Anything above 50 MB goes resumable (well under the 100 MB cap).

b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

# Last HTTP status code from a curl `-D -` header dump (handles 100-continue etc.).
http_status_of() { awk 'toupper($1) ~ /^HTTP/ {c=$2} END{print c}'; }
# First value of a (case-insensitive) header from a curl `-D -` header dump.
header_value_of() { tr -d '\r' | awk -v k="$(printf '%s' "$1" | tr 'A-Z' 'a-z'):" 'tolower($1)==k {print $2; exit}'; }

# upload_single <file> <object_path> <content_type> — plain single-request upload.
upload_single() {
  local file="$1" object_path="$2" ctype="$3" code
  code="$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "$SUPABASE_URL/storage/v1/object/$BUCKET/$object_path" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
    -H "Content-Type: $ctype" \
    -H "x-upsert: true" \
    --data-binary "@$file")" || true
  if [[ "$code" != "200" ]]; then
    echo "ERROR: storage upload failed for $(basename "$object_path") (HTTP ${code:-none})" >&2
    return 1
  fi
}

# upload_resumable <file> <object_path> <content_type> — chunked TUS upload for large files.
upload_resumable() {
  local file="$1" object_path="$2" ctype="$3"
  local total; total="$(wc -c < "$file" | tr -d '[:space:]')"
  local meta="bucketName $(b64 "$BUCKET"),objectName $(b64 "$object_path"),contentType $(b64 "$ctype"),cacheControl $(b64 '3600')"

  # 1) Create the upload; capture its per-upload Location URL. x-upsert overwrites on re-runs.
  local create_hdrs
  create_hdrs="$(curl -sS -D - -o /dev/null \
    -X POST "$SUPABASE_URL/storage/v1/upload/resumable" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
    -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Length: $total" \
    -H "Upload-Metadata: $meta" \
    -H "x-upsert: true")" || true

  local code; code="$(printf '%s\n' "$create_hdrs" | http_status_of)"
  if [[ "$code" != "201" ]]; then
    echo "ERROR: resumable create failed for $object_path (HTTP ${code:-none})" >&2
    printf '%s\n' "$create_hdrs" >&2
    return 1
  fi

  local location; location="$(printf '%s\n' "$create_hdrs" | header_value_of Location)"
  case "$location" in
    http*) : ;;
    /*)    location="$SUPABASE_URL$location" ;;
    "")    echo "ERROR: resumable create for $object_path returned no Location header" >&2; return 1 ;;
    *)     location="$SUPABASE_URL/storage/v1/upload/resumable/$location" ;;
  esac

  # 2) PATCH successive 6 MiB chunks until the server-reported offset reaches EOF.
  # The server's Upload-Offset is the single source of truth for where to resume;
  # since every full chunk advances it by exactly CHUNK_SIZE it stays aligned.
  local chunk="$TMP_DIR/chunk.bin" offset=0
  while [[ "$offset" -lt "$total" ]]; do
    if (( offset % CHUNK_SIZE != 0 )); then
      echo "ERROR: resumable upload for $object_path got misaligned offset $offset; aborting" >&2
      return 1
    fi
    dd if="$file" of="$chunk" bs="$CHUNK_SIZE" skip=$(( offset / CHUNK_SIZE )) count=1 2>/dev/null

    local patch_hdrs
    patch_hdrs="$(curl -sS -D - -o /dev/null \
      -X PATCH "$location" \
      -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
      -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
      -H "Tus-Resumable: 1.0.0" \
      -H "Content-Type: application/offset+octet-stream" \
      -H "Upload-Offset: $offset" \
      --data-binary "@$chunk")" || true

    local pcode; pcode="$(printf '%s\n' "$patch_hdrs" | http_status_of)"
    if [[ "$pcode" != "204" ]]; then
      echo "ERROR: resumable chunk upload failed for $object_path at offset $offset (HTTP ${pcode:-none})" >&2
      printf '%s\n' "$patch_hdrs" >&2
      return 1
    fi

    local newoff; newoff="$(printf '%s\n' "$patch_hdrs" | header_value_of Upload-Offset)"
    if [[ -z "$newoff" || "$newoff" -le "$offset" ]]; then
      echo "ERROR: resumable upload for $object_path stalled at offset $offset (server offset='${newoff:-none}')" >&2
      return 1
    fi
    offset="$newoff"
  done
}

# prerelease = anything not on the stable channel, or a version with a pre-release suffix.
PRERELEASE="false"
if [[ "$CHANNEL" != "stable" || "$VERSION" == *-* ]]; then
  PRERELEASE="true"
fi

echo "Publishing $APP $VERSION (channel=$CHANNEL, prerelease=$PRERELEASE) to Supabase bucket '$BUCKET'"

assets_json="[]"
uploaded=0

shopt -s nullglob
for file in "$ASSET_DIR"/*.dmg "$ASSET_DIR"/*.msi "$ASSET_DIR"/*.deb "$ASSET_DIR"/*.rpm "$ASSET_DIR"/*.jar; do
  [[ -f "$file" ]] || continue
  name="$(basename "$file")"
  size="$(wc -c < "$file" | tr -d '[:space:]')"
  sha="$(sha256_of "$file")"
  ctype="$(content_type_of "$name")"
  object_path="$APP/$VERSION/$name"

  echo "  -> uploading $name ($size bytes, sha256=${sha:0:12}…)"
  # Files above the threshold would exceed Cloudflare's ~100 MB single-request
  # limit, so upload them in chunks via the resumable (TUS) endpoint instead.
  if [[ "$size" -gt "$RESUMABLE_THRESHOLD" ]]; then
    echo "     ($((size / 1024 / 1024)) MB > $((RESUMABLE_THRESHOLD / 1024 / 1024)) MB threshold — using resumable upload)"
    upload_resumable "$file" "$object_path" "$ctype" || exit 1
  else
    upload_single "$file" "$object_path" "$ctype" || exit 1
  fi

  public_url="$SUPABASE_URL/storage/v1/object/public/$BUCKET/$object_path"
  assets_json="$(jq -c \
    --arg name "$name" --arg url "$public_url" --argjson size "$size" --arg sha "$sha" \
    '. + [{name: $name, url: $url, size: $size, sha256: $sha}]' <<<"$assets_json")"
  uploaded=$((uploaded + 1))
done

if [[ "$uploaded" -eq 0 ]]; then
  echo "ERROR: no installer assets (.dmg/.msi/.deb/.rpm/.jar) found in '$ASSET_DIR'" >&2
  exit 1
fi

# Optional release notes from a file if present (the release workflow writes release-body.txt).
notes=""
if [[ -f "$ASSET_DIR/release-body.txt" ]]; then
  notes="$(cat "$ASSET_DIR/release-body.txt")"
fi

row="$(jq -nc \
  --arg app "$APP" --arg version "$VERSION" --arg channel "$CHANNEL" \
  --argjson prerelease "$PRERELEASE" --arg notes "$notes" --argjson assets "$assets_json" \
  '{app: $app, version: $version, channel: $channel, prerelease: $prerelease, release_notes: $notes, assets: $assets}')"

echo "Upserting app_releases row for $APP $VERSION ($uploaded asset(s))"
http_code="$(curl -sS -o /tmp/app_releases_resp.txt -w '%{http_code}' \
  -X POST "$SUPABASE_URL/rest/v1/app_releases" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
  -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Content-Type: application/json" \
  -H "Prefer: resolution=merge-duplicates,return=minimal" \
  --data "$row")"
if [[ "$http_code" != "200" && "$http_code" != "201" && "$http_code" != "204" ]]; then
  echo "ERROR: app_releases upsert failed (HTTP $http_code): $(cat /tmp/app_releases_resp.txt)" >&2
  exit 1
fi

echo "Published $APP $VERSION to Supabase (Realtime will notify connected clients)."
