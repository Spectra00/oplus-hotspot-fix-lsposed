#!/data/data/com.termux/files/usr/bin/python3
"""
Loopback-only listener that the HotspotHook Xposed module talks to.
Runs entirely inside Termux's own sandboxed root grant - SystemUI never
touches su directly.

Start this via Termux:Boot (see termux/boot/hotspot-listener.sh) so it
survives reboots without manually opening Termux.
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
    subprocess.run(["su", "-c", shell_command], check=False)


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(5)
    print(f"hotspot_listener: listening on {HOST}:{PORT}")

    while True:
        conn, _addr = srv.accept()
        try:
            data = conn.recv(256).decode("utf-8", errors="ignore").strip()
            token, _, command = data.partition(" ")
            if token != TOKEN:
                continue
            if command == "on":
                run_as_root(START_CMD)
            elif command == "off":
                run_as_root(STOP_CMD)
        except Exception as exc:  # noqa: BLE001 - keep the listener alive no matter what
            print(f"hotspot_listener: error handling request: {exc}")
        finally:
            conn.close()


if __name__ == "__main__":
    main()
