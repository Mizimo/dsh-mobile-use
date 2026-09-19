# dsh-mobile-use

Pure-ADB Android device control for DeepSeek Harness — a re-implementation of the
[`agent-mobile-use`](https://github.com/AcidGr/agent-mobile-use) virtual-display
architecture **without root, without an unlocked bootloader, without KernelSU and
without LSPosed**.

Upstream requires a KernelSU module plus LSPosed hooks in `system_server`. This
project asks whether the same capability can be had from plain `adb shell`
(uid 2000) on a retail, locked device. On the test device it can — for the two
capabilities that matter most.

## Status (measured on the test device, not inferred)

Test device: **Sony SO-51D (Xperia 5 V, docomo) / Android 15 (SDK 35)**,
`ro.boot.flash.locked=1` (bootloader permanently locked, no root, no LSPosed).

| Capability | Result | Evidence |
| --- | --- | --- |
| Run our own code as `app_process` under shell | ✅ | `CLASSPATH=… app_process /system/bin com.agent.AgentVd check` |
| Create a headless virtual display | ✅ | `DisplayDeviceInfo{"AgentVirtualDisplay" … type VIRTUAL, owner com.android.shell (uid 2000)}` |
| Launch an app **onto** that display | ✅ | `am start --display 10 -n …Calculator` → `topResumedActivity` on `Display #10` |
| Inject touch into that display | ✅ | `input -d 10 tap …` exit 0 |
| Main display stays untouched | ✅ | `Display #0` keeps its own activity stack |
| Capture the virtual display's frames | ❌ | `screencap -d` → `Status: -2`; our ImageReader receives no frames |
| Clear the soft keyboard on the display | ⚠️ | `setDisplayImePolicy` call rejected (non-fatal) |

Everything in that table is reproducible with the scripts in `poc/` — see below.

## Why upstream's daemon does not work here, and what changed

Two independent blockers were found by running it:

1. **`packageName must match the calling uid`.** Upstream `agent_vd.dex` hard-codes
   the display name and lets the framework derive the package name, which under
   `adb shell` resolves to a name uid 2000 does not own.
   `DisplayManagerService.validatePackageName` rejects it. uid 2000 owns exactly
   one package — `com.android.shell` — so the display must be created through a
   `Context` that reports that name:
   `systemContext.createPackageContext("com.android.shell", 0)`.
   Upstream never hits this because a root-owned process owns a matching package.

2. **Hard-coded ColorOS `BOOTCLASSPATH`.** `run_daemon.sh` lists
   `oplus-framework.jar`, `WfmCommon.jar` and friends. Those are absent on a Sony
   device and `app_process` aborts before `main`. The fix is to stop overriding
   `BOOTCLASSPATH` and inherit the device's own — which on this Xperia already
   includes `QPerformance.jar`, `UxPerformance.jar`, `WfdCommon.jar` and
   `qcom.fmradio.jar`.

The upstream LSPosed hooks (`canHostTasks`, `isCallerAllowedToLaunchOnDisplay`,
`validatePackageName`, …) turned out **not** to be needed for launching an app:
`am start --display <id>` from uid 2000 is accepted.

## Layout

```
poc/
  01-probe.sh      read-only capability probe (device facts, no state change)
  probe.sh         runs the probe as one policy-compliant command per call
  direct_adb.py    minimal direct-ADB client (pushes files, runs commands)
  build.sh         javac + d8 + push, one command per iteration
  java/com/agent/AgentVd.java
                   our daemon: check | probe | create <w> <h> <dpi> [pkg] [own|mirror]
docs/
  POC-純ADB副屏.md  full analysis: what upstream needs, what shell can do, results
```

## Running it

The device must have wireless debugging enabled and paired; `adb-shell.py` records
the host and port under `/root/.dsh/adbkeys/`.

```sh
# 1. build our daemon into a dex and push it
bash poc/build.sh

# 2. what does this device actually offer?
python3 poc/direct_adb.py 'env CLASSPATH=/data/local/tmp/agent_vd2.dex \
  app_process /system/bin com.agent.AgentVd check'

# 3. create the display (hold the connection open so the daemon survives)
python3 poc/direct_adb.py --hold 'env CLASSPATH=/data/local/tmp/agent_vd2.dex \
  app_process /system/bin com.agent.AgentVd create 1096 2560 420 com.android.shell'

# 4. put an app on it, and drive it
python3 poc/direct_adb.py 'am start --display <id> -n com.google.android.calculator/com.android.calculator2.Calculator'
python3 poc/direct_adb.py 'input -d <id> tap 500 1200'

# 5. tear down
python3 poc/direct_adb.py 'touch /data/local/tmp/vd_stop'
```

`<id>` is whatever `dumpsys display` reports; it increments every time a display
is created, so read it from `/data/local/tmp/vd_status.json` rather than assuming.

## Scope discipline

`direct_adb.py` talks to the same paired link the DSH device-shell wrapper uses,
but does not consult that wrapper's `/device/plan` allowlist. It exists for this
PoC and is restricted to read-only probes plus the virtual-display experiment:

- never SMS or `content` beyond the permitted query;
- never DCIM / Pictures / Android / data / obb;
- never `settings put`, `setprop`, mounts, package removal, or `su`.

## Licensing

MIT. Derived from and indebted to
[AcidGr/agent-mobile-use](https://github.com/AcidGr/agent-mobile-use) (MIT); the
upstream copyright notice is retained in `LICENSE`.
