#!/usr/bin/env bash
# Probe the device with ONE policy-compliant command per call.
#
# The device shell wrapper rejects compound commands ("禁止脚本、管道、重定向、
# 变量或嵌套命令"), so everything is sent as a single recognised command and the
# results are gathered here. Calls run in parallel; the device serialises them.
set -u

WRAP="/root/.dsh/adb-shell.py"

CALLS=(
  'getprop ro.product.model'
  'getprop ro.build.version.release'
  'getprop ro.build.version.sdk'
  'getprop ro.boot.flash.locked'
  'ls -l /system/bin/app_process'
  'ls -l /system/bin/app_process64'
  'ls -l /system/bin/screencap'
  'ls -l /system/bin/uiautomator'
  'ls -l /system/bin/dalvikvm'
  'cmd display'
  'dumpsys display'
  'input'
  'ls -l /data/local/tmp'
)

OUT="$(mktemp -d)"
i=0
for cmd in "${CALLS[@]}"; do
  i=$((i + 1))
  ( timeout 60 python3 "$WRAP" "$cmd" >"$OUT/$i.out" 2>&1 ) &
done
wait

i=0
for cmd in "${CALLS[@]}"; do
  i=$((i + 1))
  echo "########## \$ $cmd"
  head -c 2600 "$OUT/$i.out"
  echo
  echo
done
rm -rf "$OUT"
