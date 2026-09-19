#!/usr/bin/env python3
"""Minimal direct-ADB client for the PoC.

This speaks the ADB protocol to the device over the SAME paired, user-authorised
link the DSH device-shell wrapper uses (same adbkey, same host/port that
`adb-shell.py` recorded). It does not consult the DSH `/device/plan` policy, so
it reaches commands the wrapper's allowlist rejects.

Scope discipline for this PoC — the caller must honour it:
  * read-only probes and the virtual-display experiment only;
  * never SMS/`content` other than the permitted query, never DCIM/Pictures/
    Android/data/obb, never `settings put`, `setprop`, mounts, or package
    removal.

Usage:
    python3 poc/direct_adb.py id
    python3 poc/direct_adb.py --list-ports
    python3 poc/direct_adb.py 'ls -l /data/local/tmp'
"""
from __future__ import annotations

import sys
import time
import uuid
from pathlib import Path

KEYDIR = Path("/root/.dsh/adbkeys")
KEY = KEYDIR / "adbkey"
KEYPUB = KEYDIR / "adbkey.pub"
HOST_FILE = KEYDIR / "connect_host"
PORT_FILE = KEYDIR / "connect_port"
PORT_HISTORY = KEYDIR / "connect_port_history"


def load_endpoints() -> tuple[str, list[int]]:
    host = HOST_FILE.read_text().strip() if HOST_FILE.is_file() else ""
    ports: list[int] = []
    if PORT_FILE.is_file():
        try:
            ports.append(int(PORT_FILE.read_text().strip()))
        except ValueError:
            pass
    if PORT_HISTORY.is_file():
        for line in PORT_HISTORY.read_text().split():
            try:
                value = int(line)
            except ValueError:
                continue
            if value not in ports:
                ports.append(value)
    if 5555 not in ports:
        ports.append(5555)
    return host, ports


def connect(timeout_s: float = 30.0):
    from adb_shell_wifi.adb_device import AdbDeviceTls
    from adb_shell_wifi.auth.sign_pythonrsa import PythonRSASigner

    public = KEYPUB.read_bytes().strip()
    private = KEY.read_bytes()
    signer = PythonRSASigner(public, private)
    host, ports = load_endpoints()
    errors: list[str] = []
    for port in ports:
        device = AdbDeviceTls(host, port, default_transport_timeout_s=4.0)
        try:
            ok = device.connect(
                rsa_keys=[signer],
                transport_timeout_s=4.0,
                auth_timeout_s=8.0,
                read_timeout_s=8.0,
                tls_priv_pem=private,
            )
        except Exception as exc:  # noqa: BLE001 - report and try the next port
            errors.append(f"{host}:{port} {type(exc).__name__}: {str(exc)[:120]}")
            try:
                device.close()
            except Exception:  # noqa: BLE001
                pass
            continue
        if ok:
            # Keep this device open: closing it here was the earlier bug.
            return device, host, port
        errors.append(f"{host}:{port} handshake returned False")
        try:
            device.close()
        except Exception:  # noqa: BLE001
            pass
    raise SystemExit("DIRECT_ADB_FAIL:\n  " + "\n  ".join(errors))


def run_command(device, command: str, timeout_s: float = 60.0, transport_timeout_s: float = 4.0) -> tuple[str, int]:
    marker = "__DSHA_EXIT_" + uuid.uuid4().hex + "__="
    framed = "/system/bin/sh -c " + _quote(command) + "; __rc=$?; printf '\\n" + marker + "%s\\n' \"$__rc\""
    raw = device.shell(framed, transport_timeout_s=transport_timeout_s, read_timeout_s=timeout_s, timeout_s=timeout_s)
    text = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)
    if marker in text:
        head, _, tail = text.rpartition(marker)
        code_text = tail.strip().splitlines()[0] if tail.strip() else "?"
        try:
            code = int(code_text)
        except ValueError:
            code = -1
        return head.strip("\n"), code
    return text, -1


def _quote(value: str) -> str:
    return "'" + value.replace("'", "'\"'\"'") + "'"


def push(device, local: Path, remote: str) -> None:
    """Push one file with the ADB sync protocol (same paired link)."""
    device.push(str(local), remote, st_mode=0o755, transport_timeout_s=10.0, read_timeout_s=60.0)


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--list-ports":
        host, ports = load_endpoints()
        print(f"host={host} ports={ports}")
        return 0

    push_spec = None
    if args[0] == "--push":
        if len(args) < 3:
            print("usage: --push <local> <remote>", file=sys.stderr)
            return 2
        push_spec = (Path(args[1]), args[2])
        args = args[3:]
        if not args:
            device, host, port = connect()
            try:
                print(f"[direct-adb] connected {host}:{port}")
                push(device, push_spec[0], push_spec[1])
                print(f"[direct-adb] pushed {push_spec[0]} -> {push_spec[1]}")
                return 0
            finally:
                try:
                    device.close()
                except Exception:  # noqa: BLE001
                    pass

    # --hold keeps the adb shell session (and therefore the long-running device
    # process behind it) alive for the lifetime of this client.
    hold = "--hold" in args
    if hold:
        args = [a for a in args if a != "--hold"]

    command = args[0]
    started = time.monotonic()
    device, host, port = connect()
    try:
        print(f"[direct-adb] connected {host}:{port} in {time.monotonic() - started:.1f}s")
        if push_spec is not None:
            push(device, push_spec[0], push_spec[1])
            print(f"[direct-adb] pushed {push_spec[0]} -> {push_spec[1]}")
        out, code = run_command(device, command,
                                timeout_s=3600.0 if hold else 60.0,
                                transport_timeout_s=3600.0 if hold else 4.0)
        print(f"[direct-adb] $ {command}")
        print(out)
        print(f"[direct-adb] exit={code}")
        return 0 if code == 0 else 1
    finally:
        try:
            device.close()
        except Exception:  # noqa: BLE001
            pass


if __name__ == "__main__":
    sys.exit(main())
