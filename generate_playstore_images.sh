#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
OUT_DIR="$ROOT/playstore_release/images"
mkdir -p "$OUT_DIR"

echo "Using adb to find a connected device/emulator..."
DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
  echo "No device found. Start an emulator and retry." >&2
  exit 1
fi

echo "Device: $DEVICE"

echo "Generating icons..."
chmod +x "$ROOT/generate_icons.sh" || true
"$ROOT/generate_icons.sh"

echo "Building and installing debug APK..."
./gradlew :app:installDebug --no-daemon

echo "Launching app main activity..."
adb -s "$DEVICE" shell am start -n com.ai.harnessdroid/.ui.MainActivity || true
sleep 2

echo "Capturing screenshots on device..."
COUNT=6
for i in $(seq 1 $COUNT); do
  REMOTE_PATH="/sdcard/ps_ss_${i}.png"
  adb -s "$DEVICE" shell screencap -p "$REMOTE_PATH"
  LOCAL_PATH="$OUT_DIR/phone_ss_${i}.png"
  adb -s "$DEVICE" pull "$REMOTE_PATH" "$LOCAL_PATH" >/dev/null
  adb -s "$DEVICE" shell rm "$REMOTE_PATH" || true
  echo "Saved $LOCAL_PATH"
  sleep 1
done

echo "Generating Play Store sized assets..."
# Phone (Portrait) 1080x1920
for f in "$OUT_DIR"/phone_ss_*.png; do
  base=$(basename "$f")
  convert "$f" -resize 1080x1920^ -gravity center -extent 1080x1920 "$OUT_DIR/ps_phone_${base}"
done

# Feature Graphic 1024x500 - use first screenshot
convert "$OUT_DIR/phone_ss_1.png" -resize 1024x500^ -gravity center -extent 1024x500 "$OUT_DIR/feature_graphic.png"

# 10-inch tablet (1920x1200)
convert "$OUT_DIR/phone_ss_1.png" -resize 1920x1200^ -gravity center -extent 1920x1200 "$OUT_DIR/ps_tablet_10in.png"

# 7-inch tablet (1024x600)
convert "$OUT_DIR/phone_ss_1.png" -resize 1024x600^ -gravity center -extent 1024x600 "$OUT_DIR/ps_tablet_7in.png"

echo "All images generated in $OUT_DIR"
echo "Play Store images:"
ls -l "$OUT_DIR"

echo "Packaging images into playstore_release.zip"
cd "$ROOT/playstore_release"
zip -r playstore_images.zip images >/dev/null
cd "$ROOT"
echo "Created playstore_release/playstore_images.zip"

echo "Done."
