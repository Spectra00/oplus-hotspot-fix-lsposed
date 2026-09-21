#!/data/data/com.termux/files/usr/bin/python3
"""
Loopback-only listener that the HotspotHook Xposed module talks to.
Runs entirely inside Termux's own sandboxed root grant - SystemUI never
touches su directly.

Start this via Termux:Boot (see termux/boot/hotspot-listener.sh) so it
survives reboots without manually opening Termux. Run with `python3 -u`
(or PYTHONUNBUFFERED=1) if you're watching output live in a foreground
session - Python block-buffers stdout when it isn't a terminal, so
without -u, prints can sit unflushed for a long time even though the
listener is working correctly.
"""

import socket
import subprocess

# Must match HotspotHook.java's TOKEN exactly.
TOKEN = "fa764b123c5fe97c48f0ddd97cff1e30"
HOST = "127.0.0.1"
PORT = 47291

# Edit these to match your device's actual interfaces/subnet/credentials.
# Verify with `ip link` while a hotspot is manually running if these ever
# stop matching after a firmware update.
SSID = "MyOnePlusHotspot"
SECURITY_TYPE = "wpa2"
PASSPHRASE = "MyPassword123"
WIFI_INTERFACE = "wlan2"
UPSTREAM_INTERFACE = "rmnet_data0"
SUBNET = "192.168.43.0/24"

START_CMD = (
    f'cmd wifi start-softap "{SSID}" {SECURITY_TYPE} "{PASSPHRASE}" && '
    f"ndc nat enable {WIFI_INTERFACE} {UPSTREAM_INTERFACE} 1 {SUBNET}"
)
STOP_CMD = f"ndc nat disable {WIFI_INTERFACE} {UPSTREAM_INTERFACE} ; cmd wifi stop-softap"


def run_as_root(shell_command: str) -> None:
    print(f"hotspot_listener: running as root: {shell_command}")
    result = subprocess.run(
        ["su", "-c", shell_command],
        check=False,
        capture_output=True,
        text=True,
    )
    print(f"hotspot_listener: exit code {result.returncode}")
    if result.stdout.strip():
        print(f"hotspot_listener: stdout: {result.stdout.strip()}")
    if result.stderr.strip():
        print(f"hotspot_listener: stderr: {result.stderr.strip()}")


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(5)
    print(f"hotspot_listener: listening on {HOST}:{PORT}")

    while True:
        conn, addr = srv.accept()
        print(f"hotspot_listener: connection from {addr}")
        try:
            data = conn.recv(256).decode("utf-8", errors="ignore").strip()
            print(f"hotspot_listener: received {data!r}")
            token, _, command = data.partition(" ")
            if token != TOKEN:
                print("hotspot_listener: token mismatch, ignoring")
                continue
            if command == "on":
                run_as_root(START_CMD)
            elif command == "off":
                run_as_root(STOP_CMD)
            else:
                print(f"hotspot_listener: unknown command {command!r}")
        except Exception as exc:  # noqa: BLE001 - keep the listener alive no matter what
            print(f"hotspot_listener: error handling request: {exc}")
        finally:
            conn.close()


if __name__ == "__main__":
    main()
