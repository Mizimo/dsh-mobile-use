#!/system/bin/sh
# Phase 0 probe — what this device actually offers for a headless virtual display,
# using ONLY `adb shell` privileges (uid 2000). No root, no LSPosed, no KSU module.
#
# Run through the DSH device shell wrapper:
#   /root/dsh-bin/adb-shell "$(cat poc/01-probe.sh)"
#
# Everything here is read-only: it inspects capabilities and prints facts.
# It creates no display and changes no state.

echo "===== 1. identity ====="
id

echo
echo "===== 2. device / build ====="
getprop ro.product.model
getprop ro.build.version.release
getprop ro.build.version.sdk
getprop ro.build.characteristics

echo
echo "===== 3. bootloader unlock state (informational; irrelevant if we stay ADB-only) ====="
getprop ro.boot.flash.locked
getprop ro.oem_unlock_supported
getprop ro.secure

echo
echo "===== 4. existing displays ====="
dumpsys display 2>/dev/null | grep -E "mDisplayId=|DisplayDeviceInfo\{|state=|uniqueId=" | head -30

echo
echo "===== 5. can shell create a virtual display via cmd? ====="
cmd display 2>&1 | head -20

echo
echo "===== 6. app_process availability ====="
ls -l /system/bin/app_process /system/bin/app_process64 /system/bin/dalvikvm 2>&1

echo
echo "===== 7. what java tools exist on device (dex/d8/dx/art) ====="
ls -l /system/bin/dex2oat /system/bin/dalvikvm /apex/com.android.art/bin/ 2>&1 | head -15

echo
echo "===== 8. is there a writable scratch dir for shell ====="
for d in /data/local/tmp /sdcard/Download; do
  if [ -d "$d" ]; then
    touch "$d/.dsh_probe" 2>/dev/null && echo "$d : writable" && rm -f "$d/.dsh_probe" || echo "$d : not writable"
  else
    echo "$d : missing"
  fi
done

echo
echo "===== 9. input supports -d (display target)? ====="
input 2>&1 | head -25

echo
echo "===== 10. screencap / screenrecord ====="
ls -l /system/bin/screencap /system/bin/screenrecord 2>&1

echo
echo "===== 11. uiautomator present? ====="
ls -l /system/bin/uiautomator 2>&1

echo
echo "===== 12. surfaceflinger display list (alternative view) ====="
dumpsys SurfaceFlinger --display-id 2>&1 | head -10

echo
echo "===== probe done ====="
