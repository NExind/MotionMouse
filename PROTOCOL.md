# Motion Mouse Communication Protocol
# Version 1.0

## Overview

All communication between the Android client and Windows host uses a lightweight binary protocol over a TCP socket (for both Wi-Fi and Bluetooth RFCOMM). UDP is used only for discovery — all motion data travels over TCP.

**Discovery**  →  UDP broadcast
**Everything else**  →  TCP socket

## Transport

| Connection | Mechanism |
|---|---|
| Wi-Fi | TCP socket over LAN / hotspot |
| Bluetooth | RFCOMM socket (emulates serial over BT) |
| USB (future) | ADB reverse tunnel → TCP socket (same protocol) |

Because USB will forward a TCP port via ADB, the protocol does not need to change when USB is added. Only the connection setup differs.

---

## Packet Structure

All packets are binary. Every packet begins with a 1-byte type identifier. Packets are length-prefixed for framing.

```
[ 2 bytes: total packet length (uint16, big-endian, includes these 2 bytes) ]
[ 1 byte:  packet type ]
[ N bytes: payload (varies by type) ]
```

- Minimum packet size: 3 bytes (length + type, no payload).
- Maximum packet size: 64 bytes (enforced — no large payloads in V1).
- All multi-byte integers are big-endian.
- All floats are IEEE 754 single-precision (4 bytes).

---

## Packet Types

| ID   | Name              | Direction         | Description                    |
|------|-------------------|-------------------|--------------------------------|
| 0x01 | HELLO             | Android → Windows | Initial handshake              |
| 0x02 | HELLO_ACK         | Windows → Android | Handshake acknowledgement      |
| 0x03 | MOTION            | Android → Windows | Cursor movement delta          |
| 0x04 | BUTTON            | Android → Windows | Mouse button event             |
| 0x05 | PING              | Android → Windows | Latency measurement request    |
| 0x06 | PONG              | Windows → Android | Latency measurement response   |
| 0x07 | SETTINGS_SYNC     | Windows → Android | Push settings to phone         |
| 0x08 | STATUS            | Android → Windows | Battery, lock state            |
| 0x09 | DISCONNECT        | Either            | Clean disconnection notice     |

---

## Packet Definitions

### 0x01 HELLO (Android → Windows)
Sent immediately after TCP connection is established. Windows will not accept motion packets until handshake is complete.
- `1 byte`:  protocol version (`0x01`)
- `1 byte`:  device name length (`N`)
- `N bytes`: device name (UTF-8, max 32 bytes)

### 0x02 HELLO_ACK (Windows → Android)
Sent by Windows in response to HELLO.
- `1 byte`:  protocol version (`0x01`)
- `1 byte`:  pc name length (`N`)
- `N bytes`: pc name (UTF-8, max 32 bytes)

### 0x03 MOTION
The most frequent packet. Sent at the sensor update rate.
- `4 bytes`: delta_x (float) — horizontal velocity, pixels/sec
- `4 bytes`: delta_y (float) — vertical velocity, pixels/sec
- `8 bytes`: timestamp (int64) — milliseconds since epoch

*Total packet size: 3 (header) + 16 (payload) = 19 bytes per motion update.*

### 0x04 BUTTON
- `1 byte`: button id (`0x01` = Left, `0x02` = Right)
- `1 byte`: action (`0x01` = Press, `0x02` = Release)

### 0x05 PING
- `8 bytes`: send_timestamp (int64, milliseconds since epoch)

### 0x06 PONG
- `8 bytes`: original send_timestamp (int64, echoed from PING)

### 0x07 SETTINGS_SYNC (Windows → Android)
Allows Windows settings UI to push configuration to the phone.
- `4 bytes`: sensitivity (float, range 0.1 – 5.0)
- `4 bytes`: smoothing_factor (float, range 0.0 – 1.0)
- `4 bytes`: dead_zone (float, range 0.0 – 0.1, in rad/s)
- `4 bytes`: acceleration_exponent (float, range 1.0 – 3.0)

### 0x08 STATUS (Android → Windows)
Sent every 5 seconds or on state change.
- `1 byte`: battery_level (0–100)
- `1 byte`: is_locked (`0x00` = active, `0x01` = locked)
- `1 byte`: connection_type (`0x01` = Wi-Fi, `0x02` = Bluetooth)

### 0x09 DISCONNECT
No payload. Sent by either side before intentionally closing the socket.

---

## Discovery Protocol (UDP)

Used for Wi-Fi only. Bluetooth discovery uses the system BT API.

**Broadcast (Windows → LAN)**
Windows sends a UDP broadcast to `255.255.255.255` on port `41234` every 2 seconds while waiting.
```json
{
  "type": "MOTION_MOUSE_ANNOUNCE",
  "version": 1,
  "pc_name": "<hostname>",
  "tcp_port": 41235
}
```

**Response (Android → Windows)**
Android sends a UDP unicast reply to the sender's IP on port `41234`.
```json
{
  "type": "MOTION_MOUSE_REPLY",
  "version": 1,
  "device_name": "<phone name>"
}
```

---

## Ports & Timing

| Port  | Protocol | Usage |
|-------|----------|-------|
| 41234 | UDP | Discovery (broadcast + reply) |
| 41235 | TCP | Data (Wi-Fi) |

- **Bluetooth UUID:** `8ce255c0-200a-11e0-ac64-0800200c9a66`
- **Motion target rate:** 100Hz (10ms interval) minimum, 200Hz preferred.
- **Ping interval:** every 2 seconds.
- **Status interval:** every 5 seconds.

---

## Version Negotiation & Security

If Windows receives a HELLO with an unsupported version, it sends HELLO_ACK with version = `0xFF` and closes the socket. The Android app displays "Version mismatch — please update."

V1 uses no encryption (local network only). Encryption can be added in a future version without changing the packet format by wrapping the TCP stream in TLS after the handshake.
