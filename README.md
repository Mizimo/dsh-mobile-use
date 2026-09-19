# dsh-mobile-use

**English** | [中文說明](README.zh.md)

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
| Capture the display's frames | ✅ | ImageReader → PNG, 155 KB, 1096×2560 (see below) |
| Closed loop: perceive → act → verify | ✅ | tap `7` on the display, re-capture, the display shows `7` |
| Main display stays untouched | ✅ | `Display #0` keeps `com.sonymobile.launcher` while `Display #15` runs the calculator |
| Clear the soft keyboard on the display | ⚠️ | `setDisplayImePolicy` call rejected (non-fatal) |

The captured frame is the virtual display's own content, not a mirror of the
phone: the pixel evidence is a Google Calculator at 1096×2560 with the phone's
home screen still on display 0.


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
  vd.py            the control bus: start | stop | status | launch | tap | swipe |
                   type | key | capture
  java/com/agent/AgentVd.java
                   the daemon: check | probe | create <w> <h> <dpi> [pkg] [own|mirror]
  direct_adb.py    minimal direct-ADB client: run one command, push, --hold a session
  build.sh         javac + d8 + push, one command per iteration
  01-probe.sh      read-only capability probe (device facts, no state change)
  probe.sh         runs the probe as one policy-compliant command per call
  run_on_device.py runs a script through the DSH device-shell channel and reports
                   which lines its policy refused
docs/
  POC-純ADB副屏.md  full analysis: what upstream needs, what shell can do, results
```

## Running it

### The CLI

```sh
python3 poc/vd.py start                     # create the display, print its id
python3 poc/vd.py status                    # id, geometry, frame counters, last capture
python3 poc/vd.py launch <pkg>/<activity>   # put an app on the display
python3 poc/vd.py tap <x> <y>
python3 poc/vd.py swipe <x1> <y1> <x2> <y2> [ms]
python3 poc/vd.py key <keycode>
python3 poc/vd.py capture out.png           # frame from the display's own surface
python3 poc/vd.py stop                      # release the display
```

`start` waits for a clean state before creating anything, and confirms the new
display against `dumpsys display` rather than trusting its own status file — a
single shared status path is exactly what makes a stale daemon look alive.

### The pieces

`vd.py` drives; these are what it calls, and they are useful on their own:

```sh
bash poc/build.sh                     # javac + d8 + push: one command per iteration
python3 poc/direct_adb.py 'id'        # run any single command over the paired link
python3 poc/direct_adb.py --hold '…'  # keep a shell session (and its daemon) alive
python3 poc/direct_adb.py --push <local> <remote>
```

The device must have wireless debugging enabled and paired; `adb-shell.py` records
the host and port under `/root/.dsh/adbkeys/`.

`<id>` increments every time a display is created, so read it from
`/data/local/tmp/vd_status.json` (or `vd.py status`) rather than assuming 9 or 10.


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
