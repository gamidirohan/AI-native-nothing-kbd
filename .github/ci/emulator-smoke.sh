#!/usr/bin/env bash
# Runs on a booted Android emulator (started by android-emulator-runner).
#
# Installs the debug APK, makes the keyboard the active one, opens its AI Writing settings page,
# then types into a text field, opens the AI Writing panel, loads the model, runs "Fix all" and
# taps Replace. Every step saves a screenshot to ci-out/. Steps do not stop the script when they
# fail, so one problem does not hide the rest; summary.txt lists what passed.
#
# Usage: emulator-smoke.sh path/to/app.apk

set -u

APK="${1:?path to apk}"
OUT=ci-out
PKG=com.arunk140.inputmethod.latin
SETTINGS="$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity"
MODEL_FILE=Qwen3-0.6B.litertlm
MODEL_URL=https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/$MODEL_FILE
MODEL_SHA256=c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108
INPUT_TEXT="im on my way ill be there in ten minutes dont start without me"

mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
: > "$SUMMARY"

note() { echo "$*" | tee -a "$SUMMARY"; }
pass() { note "PASS  $*"; }
fail() { note "FAIL  $*"; }

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null; }

# Saves the UI tree (all windows, so the keyboard is included) to $OUT/$1.xml
dump() {
  adb shell uiautomator dump --windows /sdcard/ui.xml >/dev/null 2>&1 \
    || adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1
}

# Prints "x y" for the first node whose text or content-desc matches the regex, or fails.
# $1 = regex, $2 = optional class name to require (e.g. android.widget.EditText)
find_node() {
  python3 - "$1" "${2:-}" "$OUT/ui-find.xml" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
pattern, cls, path = re.compile(sys.argv[1]), sys.argv[2], sys.argv[3]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(1)
for n in root.iter('node'):
    if cls and n.get('class') != cls:
        continue
    label = (n.get('text') or '') + '|' + (n.get('content-desc') or '')
    if pattern.search(label):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds') or '')
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# Taps the first node matching the regex. Retries for up to $3 seconds (default 15).
tap() {
  local pattern="$1" cls="${2:-}" wait="${3:-15}" i xy
  for ((i = 0; i < wait; i++)); do
    dump ui-find
    if xy=$(find_node "$pattern" "$cls"); then
      adb shell input tap $xy
      return 0
    fi
    sleep 1
  done
  return 1
}

# True if the current UI contains text matching the regex. Retries for up to $2 seconds.
ui_has() {
  local pattern="$1" wait="${2:-15}" i
  for ((i = 0; i < wait; i++)); do
    dump ui-find
    find_node "$pattern" >/dev/null && return 0
    sleep 1
  done
  return 1
}

note "emulator: $(adb shell getprop ro.product.model | tr -d '\r') api $(adb shell getprop ro.build.version.sdk | tr -d '\r') abi $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
note "free memory: $(adb shell grep -E 'MemAvailable' /proc/meminfo | tr -d '\r')"

# 1. Install -------------------------------------------------------------------------------
if adb install -r -g "$APK" > "$OUT/install.txt" 2>&1; then pass "APK installs"; else fail "APK installs: $(cat "$OUT/install.txt")"; exit 0; fi

# 2. Make the keyboard the active one -----------------------------------------------------
IME=$(adb shell ime list -a -s | tr -d '\r' | grep "$PKG" | head -1)
if [ -z "$IME" ]; then fail "keyboard is registered as an input method"; adb shell ime list -a > "$OUT/ime-list.txt"; exit 0; fi
pass "keyboard is registered as an input method ($IME)"
adb shell ime enable "$IME" >/dev/null 2>&1
adb shell ime set "$IME" >/dev/null 2>&1
if adb shell settings get secure default_input_method | tr -d '\r' | grep -q "$PKG"; then pass "keyboard set as the default"; else fail "keyboard set as the default"; fi

# 3. Settings page for AI Writing ---------------------------------------------------------
adb logcat -c
adb shell am start -n "$SETTINGS" --es navDest aiWriting >/dev/null 2>&1
if ui_has "AI Writing" 25; then pass "AI Writing settings page opens"; else fail "AI Writing settings page opens"; fi
sleep 2; shot 01-ai-writing-settings; dump 01-ai-writing-settings
ui_has "Download model" 5 && pass "settings page shows the download option" || fail "settings page shows the download option"

adb shell input keyevent KEYCODE_BACK
sleep 2; shot 02-settings-home
ui_has "AI Writing" 5 && pass "settings home lists AI Writing" || fail "settings home lists AI Writing"

# 4. Text field with the keyboard -----------------------------------------------------------
adb shell am start -n "$SETTINGS" --es navDest devtextedit >/dev/null 2>&1
sleep 6
shot 03-text-fields
if tap "" android.widget.EditText 25; then
  sleep 3
  adb shell input text "$(echo "$INPUT_TEXT" | sed 's/ /%s/g')"
  sleep 2
  shot 04-keyboard-with-text
  dump 04-keyboard-with-text
  pass "text field focused and typed into"
else
  fail "text field focused"
fi

# 5. Open the AI Writing panel (no model yet: expect the download card) --------------------
open_panel() {
  # The toolbar icon has no text, only a content description, which shows up as "|AI Writing".
  if tap '\|AI Writing$' "" 8; then return 0; fi
  # Not in the visible toolbar: open the full actions list first.
  tap '\|Open Actions$' "" 5 && sleep 2 && tap '\|AI Writing$' "" 10
}
if open_panel; then
  sleep 3; shot 05-panel-without-model; dump 05-panel-without-model
  if ui_has "Download the AI model|Download \(" 5; then pass "panel shows the download card when no model is installed"; else fail "panel shows the download card when no model is installed"; fi
else
  fail "AI Writing button found in the keyboard toolbar"
  shot 05-no-ai-button; dump 05-no-ai-button
fi

# 6. Install the model (verifies the pinned hash) and reopen the panel -----------------------
if curl -sSL --retry 3 -o "$MODEL_FILE" "$MODEL_URL"; then
  ACTUAL=$(sha256sum "$MODEL_FILE" | cut -d' ' -f1)
  if [ "$ACTUAL" = "$MODEL_SHA256" ]; then pass "model file matches the hash pinned in the app"; else fail "model file matches the pinned hash (got $ACTUAL)"; fi
  adb push "$MODEL_FILE" /data/local/tmp/ >/dev/null 2>&1
  adb shell "run-as $PKG sh -c 'mkdir -p files/llm-models && cp /data/local/tmp/$MODEL_FILE files/llm-models/$MODEL_FILE'" 2>"$OUT/model-copy.txt"
  adb shell rm -f "/data/local/tmp/$MODEL_FILE"
  rm -f "$MODEL_FILE"
  SIZE=$(adb shell "run-as $PKG stat -c %s files/llm-models/$MODEL_FILE" 2>/dev/null | tr -d '\r')
  if [ "$SIZE" = "613722264" ]; then pass "model copied into the app ($SIZE bytes)"; else fail "model copied into the app (size '$SIZE')"; fi
else
  fail "model downloaded from Hugging Face"
fi

# Close the panel and open it again, now that a model is installed.
tap '\|Close$' "" 5 >/dev/null 2>&1 || adb shell input keyevent KEYCODE_BACK
sleep 2
if open_panel; then
  sleep 3; shot 06-panel-with-model; dump 06-panel-with-model
  if ui_has "Fix all" 10; then pass "panel shows the style buttons once a model is installed"; else fail "panel shows the style buttons once a model is installed"; fi

  # 7. Run "Fix all" ------------------------------------------------------------------------
  if tap "^Fix all" "" 10; then
    sleep 5; shot 07-working
    # The corrected text ends sentences with a period, which the typed text does not have.
    if ui_has "way\." 150; then
      pass "Fix all produced a result"
      sleep 1; shot 08-result; dump 08-result
      # 8. Replace ----------------------------------------------------------------------------
      if tap "^Replace" "" 10; then
        sleep 3; shot 09-after-replace; dump 09-after-replace
        if ui_has "way\." 10; then pass "Replace put the corrected text into the field"; else fail "Replace put the corrected text into the field"; fi
      else
        fail "Replace button found"
      fi
    else
      fail "Fix all produced a result within 150 s"
      shot 08-no-result; dump 08-no-result
    fi
  else
    fail "Fix all button found"
  fi
else
  fail "panel opens again after installing the model"
  shot 06-panel-failed; dump 06-panel-failed
fi

# Logs ---------------------------------------------------------------------------------------
adb logcat -d -v time > "$OUT/logcat-full.txt" 2>&1
grep -E "AndroidRuntime|FATAL|ANR in|LiteRtRewriteEngine|LlmModel|ModelDownload|lowmemorykiller|Process .*$PKG.* has died" "$OUT/logcat-full.txt" | tail -150 > "$OUT/logcat-important.txt"
rm -f "$OUT/logcat-full.txt" "$OUT/ui-find.xml"
if grep -q "FATAL EXCEPTION" "$OUT/logcat-important.txt"; then fail "no crashes in the log"; else pass "no crashes in the log"; fi

note ""
note "$(grep -c '^PASS' "$SUMMARY") passed, $(grep -c '^FAIL' "$SUMMARY") failed"
exit 0
