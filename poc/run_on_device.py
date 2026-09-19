#!/usr/bin/env python3
"""Run a shell script on the Android device through the DSH device-shell channel.

The channel auto-selects root / Shizuku / ADB. This script never assumes which
one it got: the probe script prints `id` first, so the transcript says for itself
which uid the device executed under.

Usage:
    python3 poc/run_on_device.py poc/01-probe.sh
    python3 poc/run_on_device.py --status          # is the channel up at all?
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

WRAPPER = "/root/dsh-bin/adb-shell"
FALLBACK = "/root/.dsh/adb-shell.py"

# The wrapper only executes recognised single commands; it is not a shell.
# So a multi-line script is sent as a single recognised command per line where
# possible, and refused lines are reported rather than silently dropped.


def wrapper_command() -> list[str]:
    if Path(WRAPPER).exists():
        return [WRAPPER]
    return ["python3", FALLBACK]


def run(command: str, timeout: int = 60) -> tuple[int, str]:
    proc = subprocess.run(
        [*wrapper_command(), command],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def channel_status() -> int:
    code, out = run("id", timeout=30)
    print(out.strip())
    if "CONNECT_FAIL" in out:
        print()
        print("CHANNEL DOWN — the device has no root / Shizuku / ADB channel open.")
        print("Enable 設定 → 開發人員選項 → 無線偵錯 (with pairing), then retry.")
        return 2
    return 0 if code == 0 else code


def run_script(path: Path) -> int:
    lines = [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    refused: list[str] = []
    for line in lines:
        code, out = run(line)
        print(f"$ {line}")
        print(out.rstrip())
        print()
        if "[POLICY_BLOCKED]" in out or "[EXIT=126]" in out:
            refused.append(line)
    if refused:
        print(f"NOTE: {len(refused)} line(s) were refused by the device policy:")
        for line in refused:
            print(f"  - {line}")
    return 0


def main() -> int:
    args = sys.argv[1:]
    if not args or args[0] == "--status":
        return channel_status()
    path = Path(args[0])
    if not path.is_file():
        print(f"no such script: {path}", file=sys.stderr)
        return 1
    if channel_status() != 0:
        return 2
    return run_script(path)


if __name__ == "__main__":
    sys.exit(main())
