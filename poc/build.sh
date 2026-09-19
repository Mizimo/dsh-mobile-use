#!/usr/bin/env bash
# Build poc/java into a dex and push it to the device.
#
#   d8.jar  — extracted from Google's build-tools zip (android-15/lib/d8.jar).
#             Ubuntu's apt build-tools are older; the official jar is used so the
#             output targets API 34+ reliably.
#   javac   — JDK 21, compiling against no android.* symbol at all.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT_CLASSES="${OUT_CLASSES:-/tmp/agentvd/classes}"
OUT_DEX="${OUT_DEX:-/tmp/agentvd/dex}"
D8_JAR="${D8_JAR:-/root/android-tools/android-15/lib/d8.jar}"
REMOTE="${REMOTE:-/data/local/tmp/agent_vd2.dex}"
MIN_API="${MIN_API:-34}"

if [ ! -f "$D8_JAR" ]; then
  echo "d8.jar not found at $D8_JAR" >&2
  echo "download build-tools: https://dl.google.com/android/repository/build-tools_r35_linux.zip" >&2
  exit 1
fi

rm -rf "$OUT_CLASSES" "$OUT_DEX"
mkdir -p "$OUT_CLASSES" "$OUT_DEX"

echo "== javac =="
javac -d "$OUT_CLASSES" -encoding UTF-8 "$HERE/java/com/agent/AgentVd.java"

echo "== d8 =="
java -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api "$MIN_API" \
  --output "$OUT_DEX" \
  "$OUT_CLASSES/com/agent/AgentVd.class" "$OUT_CLASSES/com/agent/AgentVd\$1.class"

ls -l "$OUT_DEX/classes.dex"

if [ "${SKIP_PUSH:-0}" = "1" ]; then
  echo "SKIP_PUSH=1 — dex left at $OUT_DEX/classes.dex"
  exit 0
fi

echo "== push to device =="
python3 "$HERE/direct_adb.py" --push "$OUT_DEX/classes.dex" "$REMOTE"
