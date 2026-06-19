# PS2Pad — PC server

Turns input from the Android app into a **virtual Xbox 360 controller**
using [ViGEmBus](https://github.com/ViGEm/ViGEmBus). PCSX2 (and Windows) see a
normal controller — it feels exactly like you plugged a pad in.

The app can reach this server two ways, both carrying the same packets:
**Wi-Fi** (UDP over the phone's hotspot) or **USB** (TCP over the cable, tunnelled
by `adb reverse`). The server listens for both at once on port 9999.

## Setup (Windows)

1. Install Python 3.9+ (you have 3.12 ✔).
2. Install the dependency (this also installs the ViGEmBus driver the first
   time — accept the driver prompt):

   ```
   pip install -r requirements.txt
   ```

3. Run the server:

   ```
   python server.py          # 2 virtual controllers (default)
   python server.py 1        # single player
   python server.py 4        # up to 4
   ```

   It prints this PC's IP address(es). Leave it running. One virtual Xbox 360
   controller is created per player, in order, so player 1 in the app is always
   the first controller PCSX2 sees.

## Connecting

1. Turn your **phone's hotspot ON** and connect this PC to it.
2. Open the **PS2Pad** app on the phone.
3. Tap **Discover** (auto-finds the PC) — or type one of the IPs the server
   printed — then **Connect**.
4. Open PCSX2 → *Settings → Controllers*. The "Xbox 360 Controller" appears.
   Map it (the default PS2→Xbox mapping below already lines up).

### Over USB (no hotspot)

1. **adb is handled for you.** If `adb` isn't already on your PATH, the server
   downloads Google's official Android platform-tools (~8 MB) into
   `pc-server/.platform-tools/` the first time it needs it and uses that copy.
   An existing `adb` on your PATH is always preferred. (No internet / download
   blocked? Install platform-tools manually and put `adb` on PATH.)
2. On the phone, turn on **Developer options → USB debugging**, then plug it in
   and tap **Allow** on the *Allow USB debugging?* prompt.
3. With `server.py` running, it auto-runs `adb reverse tcp:9999 tcp:9999` for
   the device and logs `[usb] <serial> ready — tap USB in the app`.
4. In the app, tap **USB**. The `[input]` log will show the source as `USB`.

If you plugged in before starting the server it'll catch up within a few
seconds (it re-checks devices every 3s). Manual fallback:
`adb reverse tcp:9999 tcp:9999`.

### Two phones = two controllers

Run the server normally (it defaults to 2 pads). On each phone, tap the
**P1 / P2** button in the connection bar to choose its player, then
Discover/Connect — both phones use the **same** PC IP. In PCSX2, two Xbox 360
controllers show up: map **Port 1** to the first (P1) and **Port 2** to the
second (P2). The server log prints per-player packet counts so you can verify
each phone is coming through.

## Button mapping (PS2 → Xbox/PCSX2)

| PS2      | Xbox          |
|----------|---------------|
| Cross ✕  | A             |
| Circle ○ | B             |
| Square □ | X             |
| Triangle△| Y             |
| L1 / R1  | LB / RB       |
| L2 / R2  | LT / RT (analog)|
| L3 / R3  | Left/Right stick click |
| Start    | Start         |
| Select   | Back          |
| D-pad    | D-pad         |
| Sticks   | Left/Right analog |

## Troubleshooting

- **App can't find the PC / no input** → Windows Firewall is blocking UDP.
  Allow `python.exe` on Private networks, or run once to get the firewall
  prompt and click *Allow*. Port is **9999/UDP**.
- **No virtual controller appears** → ViGEmBus didn't install. Re-run
  `pip install vgamepad`, or grab the installer from the ViGEmBus releases page.
- **Discover finds nothing** → some phones block broadcast on hotspot; just
  type the PC IP shown by the server and tap Connect.
- **USB: `adb download failed`** → the server couldn't fetch platform-tools
  (no internet, proxy, or firewall). Install Android platform-tools manually,
  put `adb` on your PATH, and restart the server.
- **USB: nothing happens after tapping USB** → run `adb devices`. If the phone
  shows as `unauthorized`, unlock it and accept the *Allow USB debugging?*
  prompt; if it's missing entirely, check the cable (must support data) and that
  USB debugging is on. The TCP port is **9999/TCP** on loopback.
