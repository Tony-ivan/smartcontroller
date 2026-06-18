"""
PS2Pad PC server.

Receives gamepad input from the Android app over UDP (your phone's hotspot
network) and feeds it into a virtual Xbox 360 controller via ViGEmBus.
PCSX2 (or any game/emulator) then sees a completely normal controller.

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

import socket
import struct
import sys
import time

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

    print("=" * 52)
    print("  PS2Pad server running")
    print(f"  {num_players} virtual Xbox 360 controller(s) now active.")
    print(f"  Listening on UDP port {PORT}")
    print("  This PC's IP address(es):")
    for ip in local_ips():
        print(f"      {ip}")
    print("  In each app: pick a player (P1/P2), tap Discover, Connect.")
    print("  In PCSX2: map controller port 1 to P1, port 2 to P2.")
    print("  Press Ctrl+C to quit.")
    print("=" * 52)

    last_log = 0.0
    # per-player packet counters, so the log shows both phones independently
    packets = [0] * num_players
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
            packets[player] += 1
            now = time.time()
            if now - last_log >= 2.0:
                counts = "  ".join(f"P{i + 1}:{c}" for i, c in enumerate(packets))
                print(f"[input] {addr[0]}  ({counts})")
                last_log = now


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nBye.")
