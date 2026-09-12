#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BUILD_GRADLE="$ROOT_DIR/app/build.gradle.kts"
CREDENTIALS_FILE="$ROOT_DIR/playstore_release/KEYSTORE_CREDENTIALS.txt"
KEYSTORE_PATH="$ROOT_DIR/release.keystore"
REMOTE_PATH="${1:-origin}"

if [[ ! -f "$APP_BUILD_GRADLE" ]]; then
  echo "Missing app build file: $APP_BUILD_GRADLE" >&2
  exit 1
fi

if [[ -f "$CREDENTIALS_FILE" ]]; then
  export MYAPP_RELEASE_STORE_PASSWORD="$(awk -F': ' '/Store Password:/{print $2}' "$CREDENTIALS_FILE" | head -n 1)"
  export MYAPP_RELEASE_KEY_ALIAS="$(awk -F': ' '/Key Alias:/{print $2}' "$CREDENTIALS_FILE" | head -n 1)"
  export MYAPP_RELEASE_KEY_PASSWORD="$(awk -F': ' '/Key Password:/{print $2}' "$CREDENTIALS_FILE" | head -n 1)"
fi

if [[ -z "${MYAPP_RELEASE_STORE_PASSWORD:-}" ]]; then
  export MYAPP_RELEASE_STORE_PASSWORD="password123"
fi
if [[ -z "${MYAPP_RELEASE_KEY_ALIAS:-}" ]]; then
  export MYAPP_RELEASE_KEY_ALIAS="key0"
fi
if [[ -z "${MYAPP_RELEASE_KEY_PASSWORD:-}" ]]; then
  export MYAPP_RELEASE_KEY_PASSWORD="password123"
fi

if [[ ! -f "$KEYSTORE_PATH" ]]; then
  if [[ -f "$ROOT_DIR/playstore_release/release.keystore" ]]; then
    cp "$ROOT_DIR/playstore_release/release.keystore" "$KEYSTORE_PATH"
  else
    echo "Missing keystore at $KEYSTORE_PATH" >&2
    exit 1
  fi
fi

extract_version_code() {
  grep -E "versionCode[[:space:]]*=" "$APP_BUILD_GRADLE" | head -n 1 | sed -E 's/.*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/'
}
extract_version_name() {
  grep -E "versionName[[:space:]]*=" "$APP_BUILD_GRADLE" | head -n 1 | sed -E 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/'
}

current_code="$(extract_version_code)"
current_name="$(extract_version_name)"
if [[ -z "$current_code" || -z "$current_name" ]]; then
  echo "Unable to read current version from app/build.gradle.kts" >&2
  exit 1
fi

IFS='.' read -r -a parts <<< "$current_name"
major="${parts[0]:-0}"
minor="${parts[1]:-0}"
patch="${parts[2]:-0}"
new_patch=$((patch + 1))
new_version_name="${major}.${minor}.${new_patch}"
new_version_code=$((current_code + 1))

python3 - "$APP_BUILD_GRADLE" "$new_version_code" "$new_version_name" <<'PY'
import re
import sys
from pathlib import Path
path = Path(sys.argv[1])
new_code = sys.argv[2]
new_name = sys.argv[3]
text = path.read_text()
text, count1 = re.subn(r'(versionCode\s*=\s*)(\d+)', rf'\g<1>{new_code}', text, count=1)
text, count2 = re.subn(r'(versionName\s*=\s*")([^"]+)(")', rf'\g<1>{new_name}\g<3>', text, count=1)
if count1 != 1 or count2 != 1:
    raise SystemExit('Failed to update version values in app/build.gradle.kts')
path.write_text(text)
PY

printf 'Version bumped: %s (%s) -> %s (%s)\n' "$current_name" "$current_code" "$new_version_name" "$new_version_code"

cd "$ROOT_DIR"
./gradlew bundleRelease --console=plain

if git rev-parse --git-dir >/dev/null 2>&1; then
  git config user.name "HarnessDroid" >/dev/null 2>&1 || true
  git config user.email "harnessdroid@local" >/dev/null 2>&1 || true
  git add app/build.gradle.kts
  git commit -m "Release v${new_version_name} for Google Play" || echo "No commit created (nothing changed or commit aborted)."
  if git remote get-url "$REMOTE_PATH" >/dev/null 2>&1; then
    git push "$REMOTE_PATH" HEAD
  else
    echo "No remote named '$REMOTE_PATH' configured; skipping push."
  fi
fi

AAB_PATH="$ROOT_DIR/app/build/outputs/bundle/release/app-release.aab"
if [[ -f "$AAB_PATH" ]]; then
  ls -l "$AAB_PATH"
else
  echo "Bundle not found at $AAB_PATH" >&2
  exit 1
fi

printf '\nPlay-ready artifact:\n  %s\n' "$AAB_PATH"
printf 'Next step: upload to Google Play Console using the release keystore from %s\n' "$KEYSTORE_PATH"
