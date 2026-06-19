"""
PS2Pad PC server.

Receives gamepad input from the Android app and feeds it into a virtual
Xbox 360 controller via ViGEmBus. PCSX2 (or any game/emulator) then sees a
completely normal controller.

Two transports carry the exact same packets:
  * Wi-Fi  — the phone sends UDP to this PC over the hotspot subnet.
  * USB    — the phone is plugged in with USB debugging authorized; the app
             opens a TCP socket to 127.0.0.1:9999 on the phone and `adb
             reverse` tunnels it over the cable to this server's TCP listener.
             We auto-run `adb reverse` for every connected device, and even
             auto-download adb (platform-tools) if it isn't already installed.
`adb` only tunnels TCP (not UDP), which is why the USB path is TCP.

Multiple phones can play at once: each phone tags its packets with a player
number (set in the app), and we route each player to its own virtual Xbox 360
controller. So two phones on the PC's hotspot show up in PCSX2 as two separate
controllers — map port 1 to player 1 and port 2 to player 2.

Protocol
--------
Input packet (binary, big-endian, 14 bytes):
    [0]      magic byte 0xA5
    [1]      player   uint8   (0 = P1, 1 = P2, …)
    [2..3]   buttons  uint16  (bitmask, see BUTTON_BITS)
    [4..5]   LX       int16   (-32768..32767, +X = right)
    [6..7]   LY       int16   (-32768..32767, +Y = up)
    [8..9]   RX       int16
    [10..11] RY       int16
    [12]     L2       uint8   (0..255 analog trigger)
    [13]     R2       uint8

Discovery: the phone broadcasts the ASCII text "PS2PAD_DISCOVER" to the
subnet; we reply "PS2PAD_HERE" so the app learns this PC's IP automatically.

Usage:  python server.py [num_players]   (default 2)
"""

import os
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile

try:
    import vgamepad as vg
except ImportError:
    print("ERROR: 'vgamepad' is not installed.")
    print("Run:  pip install -r requirements.txt")
    print("(On Windows this also installs the ViGEmBus driver the first time.)")
    sys.exit(1)

PORT = 9999
MAGIC = 0xA5
PACKET_FMT = ">BBHhhhhBB"         # B magic, B player, H buttons, 4x h sticks, B B triggers
PACKET_SIZE = struct.calcsize(PACKET_FMT)   # = 14
DISCOVER_MSG = b"PS2PAD_DISCOVER"
DISCOVER_REPLY = b"PS2PAD_HERE"
DEFAULT_PLAYERS = 2
MAX_PLAYERS = 4                   # ViGEmBus / XInput tops out at 4 controllers

# Official Google platform-tools bundle (contains adb.exe + its Windows DLLs).
# Fetched on demand when adb isn't already on PATH. Windows-only, like the rest
# of the server (ViGEmBus is Windows-only).
ADB_DOWNLOAD_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

# bit index in the buttons field  ->  vgamepad Xbox button
BUTTON_BITS = {
    0:  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
    1:  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
    2:  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
    3:  vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    4:  vg.XUSB_BUTTON.XUSB_GAMEPAD_START,          # PS2 Start
    5:  vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,           # PS2 Select
    6:  vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,     # L3
    7:  vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,    # R3
    8:  vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,  # L1
    9:  vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER, # R1
    10: vg.XUSB_BUTTON.XUSB_GAMEPAD_A,              # Cross
    11: vg.XUSB_BUTTON.XUSB_GAMEPAD_B,              # Circle
    12: vg.XUSB_BUTTON.XUSB_GAMEPAD_X,              # Square
    13: vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,              # Triangle
    14: vg.XUSB_BUTTON.XUSB_GAMEPAD_GUIDE,          # Analog/mode button -> Guide
}


def local_ips():
    """Best-effort list of this machine's IPv4 addresses (for the banner)."""
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    # The address the OS would use to reach the outside world / the phone.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips)


def apply_packet(pads, data):
    """Route one packet to the virtual pad for its player. Returns the player
    index on success, or None if the packet was invalid / out of range."""
    magic, player, buttons, lx, ly, rx, ry, l2, r2 = struct.unpack(PACKET_FMT, data)
    if magic != MAGIC or not (0 <= player < len(pads)):
        return None
    pad = pads[player]
    pad.reset()
    for bit, btn in BUTTON_BITS.items():
        if buttons & (1 << bit):
            pad.press_button(button=btn)
    pad.left_joystick(x_value=lx, y_value=ly)
    pad.right_joystick(x_value=rx, y_value=ry)
    pad.left_trigger(value=l2)
    pad.right_trigger(value=r2)
    pad.update()
    return player


def num_players_from_args():
    if len(sys.argv) > 1:
        try:
            n = int(sys.argv[1])
        except ValueError:
            print(f"Ignoring '{sys.argv[1]}' (not a number); using {DEFAULT_PLAYERS}.")
            return DEFAULT_PLAYERS
        return max(1, min(n, MAX_PLAYERS))
    return DEFAULT_PLAYERS


# Shared, throttled input logger. Both transports (UDP loop + TCP threads) call
# this, so the [input] line shows a unified per-player count no matter how the
# packets arrived. A lock keeps the throttle/print atomic across threads.
_log_lock = threading.Lock()
_last_log = [0.0]


def note_input(packets, player, source):
    packets[player] += 1
    now = time.time()
    with _log_lock:
        if now - _last_log[0] >= 2.0:
            counts = "  ".join(f"P{i + 1}:{c}" for i, c in enumerate(packets))
            print(f"[input] {source} via {source_label(source)}  ({counts})")
            _last_log[0] = now


def source_label(source):
    # adb-reverse connections arrive from loopback; everything else is Wi-Fi.
    return "USB" if source.startswith("127.") else "Wi-Fi"


def handle_tcp_conn(conn, addr, pads, packets):
    """Read a stream of fixed-size packets from one USB (adb-reverse) client.

    TCP has no message boundaries, so we buffer bytes and pull off PACKET_SIZE
    frames. We stay aligned by checking the MAGIC byte: if the buffer doesn't
    start with it, drop one byte and resync. Each good frame goes through the
    same apply_packet() the UDP path uses."""
    src = addr[0]
    buf = bytearray()
    with conn:
        while True:
            try:
                chunk = conn.recv(256)
            except OSError:
                break
            if not chunk:
                break          # client closed
            buf.extend(chunk)
            while len(buf) >= PACKET_SIZE:
                if buf[0] != MAGIC:
                    del buf[0]          # resync to the next possible frame
                    continue
                frame = bytes(buf[:PACKET_SIZE])
                del buf[:PACKET_SIZE]
                player = apply_packet(pads, frame)
                if player is not None:
                    note_input(packets, player, src)


def tcp_listener(pads, packets):
    """Accept USB clients on loopback (adb reverse forwards the cable here).

    Bound to 127.0.0.1 on purpose: adb connects to the host's loopback, and we
    don't want this TCP port exposed to the network — Wi-Fi already has its own
    UDP listener."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(MAX_PLAYERS)
    while True:
        try:
            conn, addr = srv.accept()
        except OSError:
            break
        threading.Thread(
            target=handle_tcp_conn,
            args=(conn, addr, pads, packets),
            daemon=True,
        ).start()


def adb_path():
    """Path to an adb executable, downloading platform-tools on first need.

    Preference order: an adb already on PATH wins (respects a user's existing
    install); then a copy we downloaded earlier under .platform-tools/;
    otherwise fetch Google's official bundle (Windows only). Returns None if no
    adb is available and we couldn't obtain one — USB mode just stays off."""
    onpath = shutil.which("adb")
    if onpath:
        return onpath

    cached = os.path.join(_script_dir(), ".platform-tools", "platform-tools", "adb.exe")
    if os.path.isfile(cached):
        return cached

    # Auto-download is Windows-only — that's the only platform the server runs on.
    if not sys.platform.startswith("win"):
        print("[usb] adb not found — install Android platform-tools to use USB mode.")
        return None

    return download_adb(cached)


def download_adb(cached):
    """Fetch + unzip Google's platform-tools next to this script. Returns the
    adb.exe path, or None on any failure (network, bad zip, missing exe)."""
    dest_root = os.path.join(_script_dir(), ".platform-tools")
    zip_path = os.path.join(dest_root, "platform-tools.zip")
    try:
        os.makedirs(dest_root, exist_ok=True)
        print("[usb] adb not found — downloading Android platform-tools (~10 MB)…")
        print(f"      {ADB_DOWNLOAD_URL}")
        urllib.request.urlretrieve(ADB_DOWNLOAD_URL, zip_path)
        with zipfile.ZipFile(zip_path) as zf:
            zf.extractall(dest_root)
    except (OSError, urllib.error.URLError, zipfile.BadZipFile) as e:
        print(f"[usb] adb download failed: {e}")
        print("      Install Android platform-tools manually to use USB mode.")
        return None
    finally:
        try:
            os.remove(zip_path)
        except OSError:
            pass

    if os.path.isfile(cached):
        print("[usb] platform-tools ready.")
        return cached
    print("[usb] download finished but adb.exe was missing from the archive.")
    return None


def _script_dir():
    return os.path.dirname(os.path.abspath(__file__))


def adb_reverse_loop():
    """Keep `adb reverse tcp:9999 -> tcp:9999` set on every connected device.

    Polls every few seconds so it survives unplug/replug and devices that are
    only authorized after the server starts. Per-device `-s` means two plugged
    phones both work (and we avoid adb's 'more than one device' error). If adb
    isn't available (and can't be downloaded) we give up — Wi-Fi still works."""
    adb = adb_path()
    if adb is None:
        print("[usb] USB mode disabled (no adb available).")
        return

    known = set()
    while True:
        devices = adb_devices(adb)
        appeared = devices - known
        gone = known - devices
        for serial in sorted(appeared):
            res = subprocess.run(
                [adb, "-s", serial, "reverse", f"tcp:{PORT}", f"tcp:{PORT}"],
                capture_output=True, text=True,
            )
            if res.returncode == 0:
                print(f"[usb] {serial} ready — tap USB in the app.")
            else:
                print(f"[usb] {serial} reverse failed: {res.stderr.strip()}")
        for serial in sorted(gone):
            print(f"[usb] {serial} disconnected.")
        known = devices
        time.sleep(3.0)


def adb_devices(adb):
    """Set of serials that are fully authorized ('device' state)."""
    try:
        out = subprocess.run(
            [adb, "devices"], capture_output=True, text=True, timeout=5,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return set()
    serials = set()
    for line in out.splitlines()[1:]:          # skip the "List of devices" header
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.add(parts[0])
    return serials


def main():
    num_players = num_players_from_args()
    # Pre-create one virtual Xbox 360 pad per player. Creating them up front (in
    # order) keeps the XInput slot assignment stable, so player 1 in the app is
    # always the first controller PCSX2 sees, player 2 the second, etc.
    pads = []
    for _ in range(num_players):
        pad = vg.VX360Gamepad()
        pad.update()          # nudge so Windows enumerates it immediately
        pads.append(pad)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", PORT))

    # per-player packet counters, shared by both transports so the log shows
    # each phone independently no matter how it connected.
    packets = [0] * num_players

    # USB transport: a loopback TCP listener plus a thread that keeps `adb
    # reverse` set on connected devices. Both are daemons so Ctrl+C still quits.
    threading.Thread(target=tcp_listener, args=(pads, packets), daemon=True).start()
    threading.Thread(target=adb_reverse_loop, daemon=True).start()

    print("=" * 52)
    print("  PS2Pad server running")
    print(f"  {num_players} virtual Xbox 360 controller(s) now active.")
    print(f"  Listening on UDP port {PORT} (Wi-Fi) + TCP {PORT} (USB)")
    print("  This PC's IP address(es):")
    for ip in local_ips():
        print(f"      {ip}")
    print("  Wi-Fi: in each app pick a player (P1/P2), tap Discover, Connect.")
    print("  USB:   plug the phone in, allow USB debugging, tap USB in the app.")
    print("  In PCSX2: map controller port 1 to P1, port 2 to P2.")
    print("  Press Ctrl+C to quit.")
    print("=" * 52)

    while True:
        try:
            data, addr = sock.recvfrom(64)
        except KeyboardInterrupt:
            break

        if data.startswith(DISCOVER_MSG):
            sock.sendto(DISCOVER_REPLY, addr)
            print(f"[discovery] {addr[0]} found us")
            continue

        if len(data) != PACKET_SIZE:
            continue
        player = apply_packet(pads, data)
        if player is not None:
            note_input(packets, player, addr[0])


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nBye.")
