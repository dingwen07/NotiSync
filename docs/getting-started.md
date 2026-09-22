# Getting started

[Documentation](README.md) · [Troubleshooting](troubleshooting.md)

## Install

| Device | Install | Minimum version |
| --- | --- | --- |
| Android | [Google Play](https://play.google.com/store/apps/details?id=net.extrawdw.apps.notisync) | Android 14 |
| iPhone / iPad | [App Store](https://apps.apple.com/app/notisync/id6784461695) | iOS / iPadOS 18.6 |
| Computer | [Desktop installation](desktop.md) | JDK 21+ and platform build prerequisites |

To build the apps yourself, see [development](development.md).

## Grant permissions

Allow NotiSync to display notifications on devices where you want to receive mirrors. On an
Android device that will send notifications, also enable **Notification read, reply & control**
(called **notification access** on some devices). Onboarding takes you to the relevant system setting.

You can skip capture access on a device that will only receive notifications. The iOS app receives
mirrors; it does not directly read other apps' notifications.

## Device pairing

Pairing exchanges signed public device information. Check the device details and safety number
before accepting trust, and complete confirmation on both devices. QR codes, pairing links, and
NFC exchange public identity material, not private keys.

### QR codes and pairing links

1. Open **Devices → Pair a device** on both devices.
2. Show one device's code and scan it with the other, or exchange the pairing link.
3. Check the device details and safety number before accepting trust.
4. Complete the exchange in the other direction so both devices trust one another.

Pairing links use the NotiSync HTTPS app-link domain, so a supported camera or QR scanner can
open the app's pairing screen. If you share a link remotely, use a channel where you can verify
who sent it.

### NFC

For two compatible Android devices:

1. Install NotiSync on both devices, enable NFC, and unlock them.
2. Open **Devices → Pair a device** on the phone that will scan. On the other phone, keep NotiSync
   open on its normal screen, with the pairing screen **closed**.
3. Hold the devices' NFC antennas close together, usually by touching their backs. Keep them
   together until the exchange finishes.
4. Review the device details and safety number on both phones, then confirm trust. If a pairing
   notification appears, open it to finish the review.

Only the scanning Android phone should have its pairing screen open: that screen enables NFC
reader mode, which disables the same phone's card-emulation mode. The other phone provides the
pairing information through Host Card Emulation (HCE). The NFC exchange carries both devices'
pairing information, so a second tap in the opposite direction is unnecessary.

For iPhone-to-Android pairing, choose **Pair via NFC** on a supported iPhone and scan the unlocked
Android device, keeping the Android pairing screen closed. iOS acts only as the NFC reader;
two iPhones cannot pair with each other over NFC. Use QR codes or pairing links when NFC is
unavailable.

> [!TIP]
> On Chinese OEM phones, the manufacturer's wallet blocks other apps' HCE when it is set as the
> default wallet app. That prevents another device from reading NotiSync's pairing information
> from the affected phone over NFC. Use the affected phone to **scan another compatible Android
> device** instead: NFC reader mode still works. QR pairing is also available, including when
> pairing with an iPhone or when both Android phones have this restriction.

For a computer, follow [desktop pairing](desktop.md#pair-with-a-phone). Run and remote signing
use devices trusted as your **own devices**.

## Choose what to mirror

On the source Android device, open **Apps** and enable the apps you want to mirror. Capture is
opt-in: granting notification access alone does not enable every app. Generate a new notification
from one of the selected apps to check delivery.

Supported actions and inline replies travel back to the source device. Their availability depends
on what the original app exposes. Dismissals can synchronize across trusted devices.

## Forward notifications from an iPhone

Use the **iPhone** tab in the Android app to pair the iPhone over Bluetooth and grant the permissions
shown during setup, including Android's nearby-device permission and iPhone notification sharing.
The Android device receives notifications through Apple Notification Center Service (ANCS) and
forwards them to your trusted devices.

Keep the iPhone and Android bridge within Bluetooth range. The iOS NotiSync app is not required for
this bridge; install it when you also want the iPhone or iPad to receive mirrored notifications.

## Use a custom broker

> [!IMPORTANT]
> Self-hosted FCM/APNs push delivery is unavailable with the production Google Play and App Store
> builds. It requires your own app builds and matching push credentials. See the
> [push delivery limitation](self-hosting.md#push-delivery-limitation) before switching brokers.

The default broker is `https://notisync-api-v2.extrawdw.net`. To use your own, follow
[self-hosting](self-hosting.md), then set the same HTTP(S) base URL on each device. On Android,
use **Settings → Broker URL**. On desktop:

```bash
notisyncd config set broker-url "https://notisync.example.com"
```

Use the base URL without appending `/v2`; clients build their API paths and derive the WebSocket
URL automatically. Prefer HTTPS for a deployed broker. Local emulator and LAN addresses are
covered in the [development guide](development.md#local-broker).
