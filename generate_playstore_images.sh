#!/usr/bin/env bash
# Generates the full set of Google Play Store listing images from the tablet
# connected over ADB: app screenshots in different real states of the agent,
# a composed feature graphic, and store-sized variants.
#
# Requirements: adb, ImageMagick (convert), a debug build installed on device.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")" && pwd)
OUT_DIR="$ROOT/playstore_release/images"
mkdir -p "$OUT_DIR"
# Clean previous (often broken/empty) artifacts
rm -f "$OUT_DIR"/ps_phone_*.png "$OUT_DIR"/ps_tablet_*.png "$OUT_DIR"/feature_graphic.png "$OUT_DIR"/shot_*.png

PKG="com.ai.harnessdroid"
DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
if [ -z "$DEVICE" ]; then
  echo "No device found. Connect the tablet and retry." >&2
  exit 1
fi
echo "Device: $DEVICE"

shot() {
  # shot <name> : capture the current screen into OUT_DIR/shot_<name>.png
  # A locked/asleep screen yields an all-black frame: verify brightness and
  # retry (wake-up) so we never keep a black capture.
  local name="$1"
  local remote="/sdcard/ps_${name}.png"
  local attempt
  for attempt in 1 2 3; do
    adb -s "$DEVICE" shell screencap -p "$remote"
    adb -s "$DEVICE" pull "$remote" "$OUT_DIR/shot_${name}.png" >/dev/null
    adb -s "$DEVICE" shell rm -f "$remote"
    local brightness
    brightness=$(convert "$OUT_DIR/shot_${name}.png" -colorspace Gray -format "%[fx:mean]" info: 2>/dev/null || echo "0")
    if awk -v b="$brightness" 'BEGIN { exit !(b > 0.04) }'; then
      echo "Captured shot_${name}.png (brightness=$brightness)"
      return 0
    fi
    echo "Black capture detected (brightness=$brightness), waking device and retrying ($attempt/3)..." >&2
    adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP
    adb -s "$DEVICE" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    sleep 3
  done
  echo "Warning: shot_${name}.png still dark after retries." >&2
  return 0
}

wait_task_end() {
  # Waits until the agent logs TASK_END (max 420s: the local LLM is slow)
  adb -s "$DEVICE" logcat -c
  for _ in $(seq 1 140); do
    if adb -s "$DEVICE" logcat -d 2>/dev/null | grep -q "TASK_END"; then
      sleep 2
      return 0
    fi
    sleep 3
  done
  echo "Warning: TASK_END not observed, capturing anyway." >&2
  return 0
}

tap_text() {
  # tap_text <text> : taps the center of the first UI node containing <text>
  local text="$1"
  adb -s "$DEVICE" shell uiautomator dump /sdcard/ps_ui_dump.xml >/dev/null 2>&1
  local dump
  dump=$(adb -s "$DEVICE" shell cat /sdcard/ps_ui_dump.xml 2>/dev/null || true)
  adb -s "$DEVICE" shell rm -f /sdcard/ps_ui_dump.xml
  local bounds
  bounds=$(echo "$dump" | tr '>' '\n' | grep -F "text=\"$text\"" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
  if [ -z "$bounds" ]; then
    echo "Warning: '$text' not found on screen." >&2
    return 0
  fi
  local x1 y1 x2 y2
  x1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1/')
  y1=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\2/')
  x2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3/')
  y2=$(echo "$bounds" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\4/')
  adb -s "$DEVICE" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
  sleep 2
}

run_prompt() {
  # run_prompt <prompt> : start a task and wait for the agent to finish.
  # Inner single quotes are required: adb shell does not protect spaces, so a
  # bare multi-word prompt reaches the activity truncated at its first word.
  adb -s "$DEVICE" shell am force-stop "$PKG"
  sleep 1
  adb -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" --es prompt "'$1'" >/dev/null
  wait_task_end
}

echo "1/6 Reset app state (fresh chat, default local LLM config)..."
# Keep the screen on and force portrait for consistent store screenshots.
adb -s "$DEVICE" shell svc power stayon true
adb -s "$DEVICE" shell input keyevent KEYCODE_WAKEUP
adb -s "$DEVICE" shell wm dismiss-keyguard >/dev/null 2>&1 || true
adb -s "$DEVICE" shell settings put system accelerometer_rotation 0
adb -s "$DEVICE" shell settings put system user_rotation 0
sleep 1
# Always (re)install the debug build: the release build is not debuggable, so
# `run-as` (used below to wipe stored sessions) would fail on it.
(cd "$ROOT" && ./gradlew :app:installDebug)
adb -s "$DEVICE" shell am force-stop "$PKG"
adb -s "$DEVICE" shell "run-as $PKG sh -c 'rm -f files/sessions/*.jsonl.gz'"
adb -s "$DEVICE" shell "run-as $PKG sh -c 'ls files/sessions/'" 2>&1 | grep -q . \
  && { echo "Warning: sessions still present after wipe." >&2; } || true

echo "2/6 Launch app for hero shot..."
adb -s "$DEVICE" shell am start -n "$PKG/.ui.MainActivity" >/dev/null
sleep 4
shot 01_home

echo "3/6 Run a real task: OS info..."
run_prompt "What is the OS info of this device?"
shot 02_os_info_task

echo "4/6 Run a real task: launch Gmail (intent discovery)..."
run_prompt "Launch the Gmail app"
shot 03_launch_gmail

echo "5/6 Open the Available Tools dialog..."
tap_text "List Tools"
shot 04_tools_dialog
tap_text "Close" || true
sleep 1

echo "6/6 Open the Execution Plan view..."
tap_text "View Plan"
shot 05_plan_view
tap_text "Close" || true

echo "Generating Play Store sized assets..."
# Flatten onto black (-alpha remove -alpha off): screencap PNGs carry an alpha
# channel that some viewers render as a fully black image.
i=0
for f in "$OUT_DIR"/shot_*.png; do
  i=$((i + 1))
  # Fit inside a square (ratio preserved, landscape or portrait):
  # phone 1800px max side, tablet 2667px — both keep min side >= 1080.
  convert "$f" -background "#000000" -alpha remove -alpha off -resize 1800x1800 "$OUT_DIR/ps_phone_${i}.png"
  convert "$f" -background "#000000" -alpha remove -alpha off -resize 2667x2667 "$OUT_DIR/ps_tablet_10in_${i}.png"
done

# Feature graphic 1024x500: composed layout (gradient background + rounded icon + title),
# not a crop of a screenshot. The rounded mask must carry an alpha channel (png32),
# otherwise DstIn flattens the corners onto black.
convert "$ROOT/play_store_icon.png" -resize 300x300 png32:/tmp/ps_icon_base.png
convert -size 300x300 xc:none -fill white -draw 'roundrectangle 0,0 299,299 70,70' png32:/tmp/ps_icon_mask.png
convert /tmp/ps_icon_base.png /tmp/ps_icon_mask.png -compose DstIn -composite -depth 8 "$OUT_DIR/_icon_round.png"
convert -size 1024x500 gradient:"#0d1b0d"-"#1e3a1e" \
  "$OUT_DIR/_icon_round.png" -geometry +60+100 -composite \
  -font DejaVu-Sans-Bold -pointsize 72 -fill "#4CAF50" -annotate +400+250 "Harness Droid" \
  -font DejaVu-Sans -pointsize 34 -fill "#e0e0e0" -annotate +404+320 "Your on-device AI agent for Android" \
  -background "#000000" -alpha remove -alpha off -depth 8 "$OUT_DIR/feature_graphic.png"
rm -f "$OUT_DIR/_icon_round.png"

# App icon 512 (Play Store): flatten as well, then verify nothing is black.
convert "$ROOT/play_store_icon.png" -resize 512x512 -background "#000000" -alpha remove -alpha off "$OUT_DIR/app_icon_512.png"

echo "Alpha-channel check (must all say 'TrueColor', not 'TrueColorAlpha'):"
identify "$OUT_DIR"/ps_phone_*.png "$OUT_DIR"/ps_tablet_10in_*.png "$OUT_DIR"/feature_graphic.png "$OUT_DIR"/app_icon_512.png | awk '{print "  " $1 " " $3}'
echo "Brightness check (must all be > 0.04):"
for f in "$OUT_DIR"/ps_phone_*.png "$OUT_DIR"/ps_tablet_10in_*.png "$OUT_DIR"/feature_graphic.png; do
  b=$(convert "$f" -colorspace Gray -format "%[fx:mean]" info:)
  echo "  $(basename "$f") brightness=$b"
done

echo "Play Store images in $OUT_DIR:"
identify "$OUT_DIR"/ps_phone_*.png "$OUT_DIR"/ps_tablet_10in_*.png "$OUT_DIR"/feature_graphic.png | awk '{print "  " $1 " " $3}'

# Raw captures keep screencap's alpha channel: flatten them too (png24 = RGB,
# no alpha) so every PNG in this folder renders identically in all viewers.
for f in "$OUT_DIR"/shot_*.png; do
  convert "$f" -background "#000000" -alpha remove -alpha off "png24:$f"
done

echo "Packaging images into playstore_release/playstore_images.zip"
cd "$ROOT/playstore_release"
rm -f playstore_images.zip
zip -r playstore_images.zip images >/dev/null
cd "$ROOT"
echo "Created playstore_release/playstore_images.zip"
echo "Done."
