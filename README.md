# Motion Mouse

Motion Mouse is a local Windows desktop and Android app pair that turns your Android phone into a premium PC mouse — no touchpad, no swiping. The phone itself is the mouse. Physical rotation and tilt control the cursor with sub-pixel precision and ultra-low latency.

## Latest Stable Version

**Motion Mouse v1.0**

Motion Mouse v1.0 is the stable release checkpoint. It includes the fully optimized sensor fusion motion engine, native .NET 9 desktop client, adaptive acceleration, customizable sensitivity/smoothing, and a low-latency TCP/RFCOMM protocol for both Wi-Fi and Bluetooth.

## Download

[Download Motion Mouse v1.0 (Windows & Android) on the Releases Page](https://github.com/NExind/MotionMouse/releases/latest)

For normal users, the pre-compiled `.exe` installer and Android `.apk` are the recommended install methods.

## Main Features

- **Gyro-Motion Control:** Physical rotation and tilt map 1:1 with cursor movement.
- **Adaptive Acceleration:** Slow movements are precise, fast movements traverse the screen quickly.
- **Sub-Pixel Smoothing:** Exponential moving average (EMA) filter reduces jitter without adding lag.
- **Dual Connectivity:** Connect instantly over Wi-Fi (TCP) or Bluetooth (RFCOMM).
- **Settings Sync:** Adjust Sensitivity, Smoothing, and Dead Zones on your PC and they sync to the phone in real-time.
- **Hardware Agnostic:** Works gracefully even on devices without gyroscopes (fallback to accelerometer).
- **Background Service:** The Android app runs silently in the background while locked to save battery.

---

## How It Works

| Phone Motion | Cursor Action |
|---|---|
| Rotate phone clockwise (flat on desk) | Move cursor right |
| Rotate phone counter-clockwise | Move cursor left |
| Lift front edge of phone | Move cursor up |
| Lift back edge of phone | Move cursor down |
| Tap Left button | Left click |
| Tap Right button | Right click |
| Tap lock icon | Freeze cursor |

Movement speed depends on how fast you move the phone, not how far — exactly like a real physical mouse.

---

## Requirements

### Android
- Android 8.0 (API 26) or later
- Gyroscope sensor (required for optimal experience)
- Accelerometer sensor (recommended — improves pitch stability)

### Windows
- Windows 10 version 1809 or later, or Windows 11
- .NET 9 runtime (bundled in the installer — not required separately)
- Bluetooth adapter (optional — for Bluetooth connection)

---

## Installation

### Windows
1. Download `MotionMouseSetup.exe` from the releases page.
2. Run the installer — no administrator rights required.
3. Motion Mouse starts automatically and silently in the system tray.

### Android
1. Install `app-debug.apk` from the releases page (or build from source).
2. Grant permissions when prompted (Bluetooth, Notifications for the background service).

---

## Connecting

### Wi-Fi (recommended)
1. Connect both PC and phone to the same Wi-Fi network (or enable Mobile Hotspot on the phone and connect the PC to it).
2. Open Motion Mouse on Windows — it starts listening automatically.
3. Open Motion Mouse on Android — your PC appears in the list.
4. Tap Connect.

### Bluetooth
1. Pair your phone with your PC via Windows Bluetooth settings.
2. Open Motion Mouse on Windows.
3. Open Motion Mouse on Android — your PC appears in the list.
4. Tap Connect.

*Connection priority: Bluetooth is preferred over Wi-Fi if both are available. You can override this in Android Settings.*

---

## Architecture & Protocol

Motion Mouse relies on a highly optimized motion pipeline:
1. **Complementary filter** — fuses gyroscope + accelerometer for drift-free pitch estimation.
2. **Dead zone** — soft dead zone with smooth re-entry eliminates drift from micro-vibrations.
3. **Exponential smoothing** — EMA filter reduces jitter while preserving responsiveness.
4. **Adaptive acceleration** — non-linear power curve for sub-linear precision and super-linear speed.
5. **Sub-pixel accumulation** — fractional pixel remainders accumulate between frames so precision is never lost at low speeds.

The motion engine is completely decoupled from the transport. Both platforms share an identical 19-byte binary protocol over TCP (Wi-Fi) or RFCOMM (Bluetooth). All motion intelligence is done on the Android device; Windows simply applies the calculated delta to the cursor via `SendInput`.

See `PROTOCOL.md` for the complete binary packet specification.

---

## Troubleshooting

- **Phone not appearing in the list (Wi-Fi):** Ensure both devices are on the same network and Windows Firewall is not blocking port 41234 (UDP) and 41235 (TCP).
- **Phone not appearing in the list (Bluetooth):** Ensure the phone is paired with the PC and Bluetooth is enabled on both devices.
- **Cursor feels jerky:** Increase Smoothing in settings or ensure the phone's gyroscope is not obstructed.
- **Cursor drifts slowly when phone is still:** Run Calibration on the phone (Settings → Calibrate) and increase the Dead Zone slightly.
- **High latency on Wi-Fi:** Switch to Bluetooth or use a Mobile Hotspot instead of a crowded home router.

---

## Building From Source

### Android (Android Studio / Gradle)
```bash
cd android
./gradlew assembleDebug
```
Output: `android/app/build/outputs/apk/debug/app-debug.apk`

### Windows (Visual Studio / .NET 9)
```bash
cd windows/MotionMouse
dotnet publish -c Release -r win-x64 --self-contained true
```
Output: `windows/MotionMouse/bin/Release/net9.0-windows/win-x64/publish/MotionMouse.exe`

---

## Licence

MIT Licence — see `LICENCE` file.
