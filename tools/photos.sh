#!/usr/bin/env bash
# Sends your photos to the TV for Free My TV's home screen and screensaver (Home Settings > Background > My Photos),
# like tools/photos.py but with only bash and adb (Git Bash on Windows, macOS, Linux): for a computer without Python.
#
#   tools/photos.sh 192.168.1.50 beach.jpg hills.jpg Holidays/
#   tools/photos.sh beach.jpg              # the TV adb is already connected to
#
# Same way in as photos.py: the app's own folder, or through the app itself where the TV keeps adb out of it
# (Android 11, Fire OS 8). The app imports them the next time the home screen, the screensaver or its settings open.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: leave the TV's paths alone

INBOX=/sdcard/Android/data/io.freemytv/files/photos
ADB="$(command -v adb || true)"
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
  [ -n "$ADB" ] && break
  for exe in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do [ -n "$sdk" ] && [ -x "$exe" ] && ADB="$exe" && break; done
done
[ -n "$ADB" ] || { echo "adb not found: install the Android platform tools (see the README)."; exit 1; }
[ $# -gt 0 ] || { sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

serial=()
if [ ! -e "$1" ] && [[ "$1" =~ ^[0-9] ]]; then
  target="$1"; shift
  [[ "$target" == *:* ]] || target="$target:5555"
  out="$("$ADB" connect "$target" 2>&1)"
  [[ "$out" == *connected* ]] || { echo "Could not connect to $target: $out"; exit 1; }
  serial=(-s "$target")
fi
adb() { "$ADB" "${serial[@]}" "$@"; }

found=()
for p in "$@"; do
  if [ -d "$p" ]; then
    while IFS= read -r f; do found+=("$f"); done < <(find "$p" -type f ! -name '.*' \
      \( -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.png' -o -iname '*.webp' -o -iname '*.heic' -o -iname '*.heif' \) | sort)
  elif [ -f "$p" ] && [[ "$(printf '%s' "$p" | tr '[:upper:]' '[:lower:]')" =~ \.(jpe?g|png|webp|heic|heif)$ ]]; then
    found+=("$p")
  else
    echo "Skipped $p: not a photo or a folder."
  fi
done
[ ${#found[@]} -gt 0 ] || { echo "No photos to send."; exit 1; }

listed="$(adb shell pm list packages io.freemytv 2>&1)" || { echo "No TV connected: give its IP address first."; exit 1; }
[[ "$listed" == *package:io.freemytv* ]] || { echo "Free My TV is not installed on this TV."; exit 1; }

direct=1
adb shell mkdir -p "$INBOX" >/dev/null 2>&1 || direct=0
if [ $direct = 0 ] && [[ "$(adb shell content query --uri content://io.freemytv.photos/ 2>&1)" == *"Could not find provider"* ]]; then
  echo "This TV keeps adb out of the app's folder, and the Free My TV on it is too old for the other way in:"
  echo "install the new FreeMyTV.apk (see Install), then send them again."
  exit 1
fi

i=0
for path in "${found[@]}"; do
  # Numbered so the app imports them in this order.
  base="$(basename "$path")"
  name="$(printf '%03d' $i)-${base// /_}"
  if [ $direct = 1 ]; then
    adb push "$path" "$INBOX/$name" >/dev/null || { echo "Could not send $path"; exit 1; }
  else
    # With its size, so the app drops a send cut short; the name is encoded like photos.py's.
    size="$(wc -c < "$path" | tr -d ' ')"
    uri="content://io.freemytv.photos/$(printf '%s' "$name" | sed 's/%/%25/g; s/#/%23/g; s/?/%3F/g')?size=$size"
    adb exec-in content write --uri "$uri" < "$path" || { echo "Could not send $path"; exit 1; }
  fi
  echo "Sent $path"
  i=$((i + 1))
done
echo "${#found[@]} photo(s) on the TV. Open the home screen or Free My TV's settings to import them."
