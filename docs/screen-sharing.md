# Screen sharing

[Documentation](README.md) · [Desktop setup](desktop.md) · [Troubleshooting](troubleshooting.md)

Screen sharing is experimental. It lets an authorized device view and control a trusted Android
device. Viewers are available on Android, iOS, and Linux/macOS through `nsscreen`. The Windows
desktop distribution does not include the viewer.

## Requirements

On the Android device being shared:

1. Install and start Shizuku Manager in **ADB mode**. Root-backed Shizuku is not supported.
2. Grant NotiSync access to Shizuku and enable screen sharing in NotiSync's settings.
3. Pair the requesting device as your own trusted device and enable **Allow screen control**
   for that device.

Shizuku Manager is installed separately. It is not bundled with NotiSync. The capture/input
service runs for the screen session; codec and control availability depend on the device.

## Desktop viewer

After [installing and pairing NotiSync Desktop](desktop.md), list eligible devices and connect:

```bash
nsscreen devices
nsscreen connect DEVICE_ID
```

For a view-only session without clipboard synchronization:

```bash
nsscreen connect DEVICE_ID --no-control --no-clipboard
```

Use `nsscreen --help` for codec, frame-rate, dimension, and bitrate options. The native viewer's
function bar provides Back, Home, Recents, and a primary-display Power toggle. Escape or right-click
sends Back; F12 toggles Power.

## Transport

Android-to-Android sharing prefers direct LAN or Wi-Fi Aware. During connection, or after direct
connection failure, the requester can manually replace the attempt with **Relay**.

Relay uses separate TCP/WebSocket channels so control input does not queue behind video.
Control stays inside an end-to-end PSK-TLS stream. Video uses end-to-end AES-GCM records with
authenticated frame metadata, bounded delivery feedback, and broker-side stale-delta dropping.
It does not depend on QUIC.

For implementation and distribution details, see the [native helper](../nsscreen/src/native/README.md),
[runtime packaging](../nsscreen/src/native/runtime/README.md), and
[third-party notices](../SCREEN_MIRRORING_THIRD_PARTY.md).
