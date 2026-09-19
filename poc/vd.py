#!/usr/bin/env python3
"""vd — the virtual-display control bus, pure-ADB edition.

Mirrors the upstream `agent-mobile-use` CLI surface (`vd start|stop|status|launch|
tap|swipe|type|capture`) but runs everything over plain `adb shell` as uid 2000:
no root, no KernelSU, no LSPosed.

Differences from upstream that matter:

  * `start` keeps this process's ADB session open, because the daemon lives only
    as long as its shell session — the display disappears when that session ends.
    The session holder is backgrounded and its pid recorded under /tmp.
  * `capture` pulls a frame the daemon wrote from its own ImageReader surface.
    `screencap -d <id>` does not work here (it returns status -2 for a virtual
    display owned by another process).

Usage:
    python3 poc/vd.py start [--width W] [--height H] [--dpi D] [--mirror]
    python3 poc/vd.py status
    python3 poc/vd.py launch <package/activity>
    python3 poc/vd.py tap <x> <y>
    python3 poc/vd.py swipe <x1> <y1> <x2> <y2> [ms]
    python3 poc/vd.py type <text>
    python3 poc/vd.py key <keycode>
    python3 poc/vd.py capture [local.png]
    python3 poc/vd.py stop
"""
from __future__ import annotations

import base64
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import direct_adb  # noqa: E402  (path inserted above)

DEX = "/data/local/tmp/agent_vd2.dex"
STATUS = "/data/local/tmp/vd_status.json"
STOP = "/data/local/tmp/vd_stop"
TRIGGER = "/data/local/tmp/vd_capture"
SHOT = "/data/local/tmp/vd_screenshot.png"
HOLD_PID = Path("/tmp/vd_hold.pid")
DEFAULT_GEOMETRY = (1096, 2560, 420)


_DEVICE = None


def connection():
    """One ADB connection per CLI invocation.

    dialling per call made a start() poll cost two or three handshakes, which
    pushed a single start past a minute and looked like a hang.
    """
    global _DEVICE
    if _DEVICE is None:
        _DEVICE, _, _ = direct_adb.connect()
    return _DEVICE


def device_run(command: str, timeout_s: float = 60.0) -> str:
    out, _ = direct_adb.run_command(connection(), command, timeout_s=timeout_s)
    return out.strip()


def close_connection() -> None:
    global _DEVICE
    if _DEVICE is not None:
        try:
            _DEVICE.close()
        except Exception:  # noqa: BLE001
            pass
        _DEVICE = None


def status() -> dict:
    raw = device_run(f"cat {STATUS}")
    if not raw or not raw.startswith("{"):
        return {}
    fields: dict[str, object] = {}
    for part in raw.strip("{}").split(","):
        key, _, value = part.partition(":")
        # Both sides need unquoting: stripping only the value left keys like
        # '"status"', so every state.get("status") returned None and start()
        # never recognised a display that was in fact up.
        key = key.strip().strip('"')
        value = value.strip().strip('"')
        try:
            fields[key] = int(value)
        except ValueError:
            fields[key] = value
    return fields


def status_advancing() -> bool:
    """Whether a daemon is alive and rewriting the status file right now.

    The daemon refreshes the file once a second while it runs, so a changing
    acquire counter is a heartbeat. Reading /proc/<pid>/cmdline instead looked
    equivalent but was not: the NUL-separated cmdline does not survive the round
    trip cleanly, the check always failed, and start() then tore down a daemon
    that had actually come up.
    """
    first = status()
    if first.get("status") != "running":
        return False
    time.sleep(1.2)
    second = status()
    if second.get("status") != "running":
        return False
    return (
        first.get("acquire_attempts") != second.get("acquire_attempts")
        or first.get("frames") != second.get("frames")
        or first.get("pid") == second.get("pid")
    )


def display_ids() -> list[int]:
    """Live display ids from the framework (ground truth)."""
    out = device_run("dumpsys display", timeout_s=60.0)
    ids = []
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("Display Id="):
            try:
                ids.append(int(line.split("=", 1)[1]))
            except ValueError:
                pass
    return ids


def wait_until_empty(timeout_s: float = 20.0) -> bool:
    """Wait for every virtual display to be gone, so a start begins from clean."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if display_ids() in ([0], []):
            return True
        time.sleep(1.0)
    return False


def require_display_id() -> int:
    state = status()
    if state.get("status") != "running" or "display_id" not in state:
        print("no running display; run `vd.py start` first", file=sys.stderr)
        sys.exit(2)
    return int(state["display_id"])


def start(width: int, height: int, dpi: int, mirror: bool) -> int:
    device_run(f"touch {STOP}")
    if not wait_until_empty(25.0):
        print("could not reach a clean state: a virtual display is still alive", file=sys.stderr)
        return 1
    device_run(f"rm -f {STOP} {TRIGGER} {STATUS}")
    flags = "mirror" if mirror else "own"
    command = (
        f"env CLASSPATH={DEX} app_process /system/bin com.agent.AgentVd "
        f"create {width} {height} {dpi} com.android.shell {flags}"
    )
    # Keep the holder's output: a silent DEVNULL holder made "display did not
    # come up" undiagnosable.
    log_path = Path("/tmp/vd_hold.log")
    log = log_path.open("wb")
    # -u: without unbuffered stdout the holder's diagnostics sit in a pipe buffer
    # and a failed start looks identical to a silent one.
    holder = subprocess.Popen(
        [sys.executable, "-u", str(HERE / "direct_adb.py"), "--hold", command],
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    HOLD_PID.write_text(str(holder.pid))
    last_state: dict = {}
    last_ids: list[int] = []
    for _ in range(40):
        time.sleep(0.5)
        state = status()
        last_state = state
        last_ids = display_ids()
        if state.get("status") == "running" and status_advancing():
            display_id = int(state.get("display_id", -1))
            if display_id in last_ids:
                print(
                    f"display {display_id} running "
                    f"{state.get('width')}x{state.get('height')}@{state.get('dpi')} "
                    f"pid {state.get('pid')}"
                )
                return 0
    print(f"start diagnostics: last_state={last_state} last_ids={last_ids}", file=sys.stderr)
    holder.terminate()
    print("display did not come up; holder log:", file=sys.stderr)
    try:
        print(log_path.read_text()[-2000:], file=sys.stderr)
    except OSError:
        pass
    return 1


def stop() -> int:
    device_run(f"touch {STOP}")
    wait_until_empty(20.0)
    if HOLD_PID.is_file():
        try:
            HOLD_PID.read_text().strip()
        except OSError:
            pass
    for _ in range(20):
        time.sleep(0.5)
        if status().get("status") == "stopped":
            break
    if HOLD_PID.is_file():
        try:
            pid = int(HOLD_PID.read_text().strip())
            subprocess.run(["kill", str(pid)], check=False, capture_output=True)
        except (OSError, ValueError):
            pass
        HOLD_PID.unlink(missing_ok=True)
    device_run(f"rm -f {STOP} {TRIGGER}")
    print("stopped")
    return 0


def launch(component: str) -> int:
    display_id = require_display_id()
    print(device_run(f"am start --display {display_id} -n {component}"))
    return 0


def tap(x: int, y: int) -> int:
    display_id = require_display_id()
    device_run(f"input -d {display_id} tap {x} {y}")
    print(f"tap {x},{y} on display {display_id}")
    return 0


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int) -> int:
    display_id = require_display_id()
    device_run(f"input -d {display_id} swipe {x1} {y1} {x2} {y2} {ms}")
    print(f"swipe {x1},{y1} -> {x2},{y2} in {ms}ms on display {display_id}")
    return 0


def send_text(text: str) -> int:
    display_id = require_display_id()
    # `input text` cannot carry spaces or non-ASCII; the daemon-free path is a
    # keyevent-based one, so this reports what it cannot do instead of silently
    # typing the wrong thing.
    if any(ch.isspace() for ch in text) or not text.isascii():
        print(
            "input text handles ASCII without spaces only; for other text use the "
            "3090 bridge (/app/ui/input) or a clipboard paste",
            file=sys.stderr,
        )
        return 2
    device_run(f"input -d {display_id} text {text}")
    print(f"typed {text!r} on display {display_id}")
    return 0


def key(keycode: int) -> int:
    display_id = require_display_id()
    device_run(f"input -d {display_id} keyevent {keycode}")
    print(f"key {keycode} on display {display_id}")
    return 0


def capture(local: Path) -> int:
    display_id = require_display_id()
    device_run(f"rm -f {TRIGGER} {SHOT}")
    device_run(f"touch {TRIGGER}")
    for _ in range(30):
        time.sleep(0.5)
        listing = device_run(f"ls -l {SHOT}")
        if "No such file" not in listing and listing.strip():
            break
    else:
        state = status()
        print(
            f"capture failed; daemon reports frames={state.get('frames')} "
            f"last_capture={state.get('last_capture')}",
            file=sys.stderr,
        )
        return 1
    payload = device_run(f"base64 {SHOT}", timeout_s=120.0)
    encoded = "".join(line for line in payload.splitlines() if not line.startswith("["))
    local.write_bytes(base64.b64decode(encoded))
    print(f"captured display {display_id} -> {local} ({local.stat().st_size} bytes)")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    command, rest = args[0], args[1:]

    if command == "start":
        width, height, dpi = DEFAULT_GEOMETRY
        mirror = "--mirror" in rest
        rest = [a for a in rest if a != "--mirror"]
        for flag, index in (("--width", 0), ("--height", 1), ("--dpi", 2)):
            if flag in rest:
                value = int(rest[rest.index(flag) + 1])
                if index == 0:
                    width = value
                elif index == 1:
                    height = value
                else:
                    dpi = value
        return start(width, height, dpi, mirror)
    if command == "status":
        state = status()
        if not state:
            print("no display state on device")
            return 1
        for key in ("status", "display_id", "width", "height", "dpi", "frames", "last_capture"):
            if key in state:
                print(f"{key}: {state[key]}")
        return 0
    if command == "stop":
        return stop()
    if command == "launch":
        return launch(rest[0])
    if command == "tap":
        return tap(int(rest[0]), int(rest[1]))
    if command == "swipe":
        return swipe(int(rest[0]), int(rest[1]), int(rest[2]), int(rest[3]),
                     int(rest[4]) if len(rest) > 4 else 300)
    if command == "type":
        return send_text(rest[0])
    if command == "key":
        return key(int(rest[0]))
    if command == "capture":
        return capture(Path(rest[0]) if rest else Path("/tmp/vd_screenshot.png"))
    print(f"unknown command: {command}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
