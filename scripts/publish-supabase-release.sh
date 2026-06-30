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
  # x-upsert lets re-runs overwrite an existing object.
  http_code="$(curl -sS -o /dev/null -w '%{http_code}' \
    -X POST "$SUPABASE_URL/storage/v1/object/$BUCKET/$object_path" \
    -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
    -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
    -H "Content-Type: $ctype" \
    -H "x-upsert: true" \
    --data-binary "@$file")"
  if [[ "$http_code" != "200" ]]; then
    echo "ERROR: storage upload failed for $name (HTTP $http_code)" >&2
    exit 1
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
