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

If a trusted own device connects before you grant that permission, the Android device shows a
notification. Tap it to open the requesting device's details, or tap **Allow** to enable screen
control directly. Then retry the connection from the requesting device. This saves the same
permission as **Allow screen control** in Device Details.

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

## Virtual Display

Compatible Android sources can run apps on a separate virtual display while the phone stays
locked or its physical screen is off. On the source phone, open the requesting device's details,
enable **Allow screen control**, then **Allow virtual display access**. Each new virtual
display grant requires a strong biometric or the phone's PIN, pattern, or password. Canceling
authentication leaves the permission off. Revoking access requires no authentication and ends any
active virtual-display session. Existing screen-control grants do not include this permission.

On an Android viewer, open the source's details and tap the **Virtual Display** icon (TV Displays)
to the right of **Mirror and control screen** to connect immediately using the viewer's current
window size and density. The virtual display follows window resizing, rotation, and transitions
between full screen, split screen, and freeform windows. Long-press the icon to choose an optional
DPI override; leaving the field empty uses the viewer's density. Custom DPI applies to the session
and is not saved as a preference. The device-list shortcut uses this same button for peers advertising
Virtual Display support; other peers retain the regular mirror shortcut. Connecting opens
NotiSync's launcher inside the source's virtual display. Search by app name or package name and
tap an app to open it on that display. The **Launcher** control (Apps icon) returns to this app list;
Home remains the system's secondary Home action. Virtual displays have their own saved control
visibility, order, and toolbar edge, separate from regular screen sharing. Both modes use the Grid
View icon for Recents. Tapping a compatible mirrored notification opens its
original activity destination in a virtual display sized for the viewer. It does not also send
the ordinary tap action to the physical phone. The source still checks the separate access grant.

On Linux/macOS:

```bash
nsscreen connect DEVICE_ID --new-display 1080x1920/320
nsscreen connect DEVICE_ID --new-display 1080x1920/320 --start-app com.example.app
```

`--notification KEY` can select an active source notification instead of `--start-app`. Notification
launch requires an activity content intent; stale, canceled, broadcast, and service intents cannot
use this path. After the content intent is sent successfully, an unchanged auto-cancel notification
is dismissed on the source and its mirrors, matching an ordinary tap. Failed sends retain it;
ongoing foreground-service notifications and newer replacements are not auto-canceled. An app may
redirect its activity, and a successful send alone does not confirm placement on the requested display.

Width and height must be 240–4096 pixels, with at most 8,388,608 pixels total; DPI must be 120–640.
The encoder's dimension limit scales the video independently of the Android display resolution.
Android window changes are debounced before resizing the existing display; encoder and input mapping
updates keep the same session and apps. Picture-in-picture and backgrounding retain the last display
size. `nsscreen` uses its explicitly configured size. This mode is currently available from Android
and `nsscreen` viewers; iOS continues to use physical-screen sharing.

Availability requires a successful shell display-creation probe. Unsupported requests fail without
switching to physical-screen mirroring. If regular screen control is allowed but the separate virtual
display grant is missing, the source returns `VIRTUAL_DISPLAY_UNAUTHORIZED`. Android and `nsscreen`
viewers close that request and retry once using a fresh version 1 physical-screen request, with new
session secrets and the same transport selection. The source checks regular authorization again.
Android then uses the regular screen-sharing controls and preferences. Other failures and revocation
during an active virtual session do not trigger this fallback. Power and notification-shade commands
are disabled for virtual displays. Home opens the secondary launcher directly, avoiding the physical keyguard's
HOME-key policy. Keep-active calls are sent only after verifying that Android assigned a power group
to the virtual display; requesting the separate-group flag alone is insufficient. Starting a session
wakes its assigned power group before launching the target. Touch, navigation, scrolling, or typing
wakes it again if necessary. On ROMs such as the tested Pixel 10 Pro Android 17 build that assign the
virtual display to the phone's power group, this intentionally turns on the physical screen and
keeps it awake for the session. It does not dismiss the phone's lock screen. Disconnecting stops
keep-active calls and lets the normal screen timeout apply. ROMs that honor a separate power group
wake only that group. Pixel Launcher may hide its secondary taskbar while the phone is locked.
App tasks may move instead of being duplicated; closing the session destroys its display and tasks.
App authentication, protected-content blanking, and the first unlock after reboot still apply.
Launcher behavior, notification placement, keyboard support, and locked-screen behavior vary by ROM.

The launcher is a non-exported NotiSync Activity opened through a session-scoped immutable
PendingIntent. Its owner token and assigned display are rechecked on entry and app launch. The
launcher intent is canceled during teardown. App queries use the existing launcher-intent visibility
scope; no installed-app list or arbitrary launch Intent is added to the wire protocol. The payload-free
Launcher command (67) requires both an virtual-display session and control permission.

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
