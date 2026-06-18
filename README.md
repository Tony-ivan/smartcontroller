# PS2Pad — phone as a PS2/PC gamepad over hotspot

Turn an Android phone into a full PlayStation-2-style controller that plugs
into your PC over your **phone's hotspot**. The PC sees a normal Xbox 360
controller, so PCSX2 (or any game) just works — it feels exactly like you
inserted a real pad.

```
┌─────────────┐   Wi-Fi / hotspot   ┌──────────────────┐   ViGEmBus driver  ┌────────┐
│ Android app │ ──UDP packets────►  │ Python PC server │ ──virtual pad────► │ PCSX2  │
│ (gamepad UI)│   port 9999         │  (vgamepad)      │  (fake Xbox 360)   │ /games │
└─────────────┘                     └──────────────────┘                    └────────┘
```

## Why it works this way

A phone can't physically present itself as a USB controller to the PC. So
instead, the PC runs a tiny program that creates a **virtual Xbox 360
controller** (via the free ViGEmBus driver). The phone streams button/stick
data over UDP, and the PC program pushes that into the virtual pad. Windows and
PCSX2 never know the phone exists.

## Project layout

```
pc-server/      Python UDP server + virtual controller   (run on the PC)
  server.py
  requirements.txt
  README.md     ← PC setup details & troubleshooting
android/        Native Kotlin Android app                 (build the APK)
  app/src/main/java/com/ps2pad/gamepad/
    MainActivity.kt    activity, connect/discover UI
    GamepadView.kt     multitouch canvas: dpad, sticks, buttons, L/R, L3/R3
    NetworkClient.kt   UDP send + auto-discovery
    InputState.kt      13-byte packet format
```

## Quick start

### 1. PC

```
cd pc-server
pip install -r requirements.txt      # also installs ViGEmBus the first time
python server.py                     # prints this PC's IP, leave running
```

### 2. Build the APK (Android Studio — easiest)

1. Open the `android/` folder in Android Studio → let it Gradle-sync.
2. Plug in your phone (USB debugging on) and press **Run**, **or**
   **Build → Build Bundle(s)/APK(s) → Build APK(s)** and install the file from
   `android/app/build/outputs/apk/debug/`.

Command line (if you have the Android SDK + Gradle):

```
cd android
gradle assembleDebug
```

### 3. Play

1. Phone hotspot **ON**; connect the PC to it.
2. Open **PS2Pad** → **Discover** (or type the IP the server printed) → **Connect**.
3. PCSX2 → *Settings → Controllers* → map the Xbox 360 pad. Done.

Tap **Hide** to clear the connection bar; tap the **top-left corner** to bring
it back.

### Two players (two phones)

Both phones connect to the **same PC hotspot** and the same PC IP — the server
tells them apart by player number, so you don't run anything twice.

1. Start the server with one pad per player (default is 2):

   ```
   python server.py          # 2 controllers
   python server.py 4        # up to 4
   ```

2. On **each** phone, tap the **P1 / P2** button in the bar to pick its player,
   then Discover/Connect as usual. Phone A → **P1**, phone B → **P2**.
3. PCSX2 → *Settings → Controllers*: two "Xbox 360 Controller" devices appear.
   Map **Controller Port 1** to the first and **Port 2** to the second. The
   server creates them in player order, so the first is always P1.

The server log shows per-player packet counts (`P1:… P2:…`) so you can confirm
both phones are getting through.

## Protocol (for reference)

14-byte UDP frame, big-endian: `magic(0xA5) | player(u8) | buttons(u16) | LX LY
RX RY(i16 each) | L2 R2(u8 each)`. The `player` byte (0 = P1, 1 = P2, …) routes
the packet to that player's virtual pad. Button bit order is defined identically
in `InputState.kt` and `server.py`.

## Notes / known limits

- **Latency** is whatever your hotspot Wi-Fi gives you — usually fine for most
  games; rhythm/fighting games may feel the lag.
- **Pressure-sensitive PS2 buttons** aren't emulated (Xbox 360 buttons are
  digital); the two analog sticks and L2/R2 triggers *are* analog.
- If Discover finds nothing, your hotspot may block broadcast — just type the
  PC IP shown by the server.
- Firewall: allow `python.exe` on Private networks (UDP 9999) if input doesn't
  register.
