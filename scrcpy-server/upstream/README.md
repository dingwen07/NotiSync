# Vendored scrcpy server

This module contains the Android server sources from scrcpy 4.1, pinned to commit
`2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`.

Upstream: <https://github.com/Genymobile/scrcpy/tree/v4.1/server>

This is a security-minimized derivative, not a copy of the complete upstream server. The retained
source closure is limited to:

- primary-display capture, rotation/fold reset monitoring, and H.264/H.265/AV1 hardware encoding;
- resizable virtual-display capture, affine OpenGL scaling/rotation, and session-scoped wakefulness;
- bounded app/secondary-Home launch, an app-owned launcher PendingIntent, and a session-scoped local notification resolver;
- pinned scrcpy 4.1 video/session framing over an app-owned `ParcelFileDescriptor`;
- touch/multitouch, mouse, scroll, bounded text input, navigation keys, and explicit wake/back;
- bounded bidirectional plain-text clipboard synchronization; and
- the Android framework wrappers required by those operations.

`NotiSyncCaptureBackend` owns an explicit session lifecycle for a Shizuku UserService. Network
discovery, authentication, TLS, authorization, and foreground-service state stay in the normal app
process. The privileged module never receives LAN sockets or session keys.

The generic scrcpy CLI/options surface and the source paths for audio, camera, UHID,
app listing, panels, arbitrary display mutation, file scanning, settings/content-provider
access, codec option injection, VP8/VP9, adb/localabstract transport, and process/shell command
execution are deleted. Power operations are limited to NotiSync's authenticated, no-payload
primary-display toggle and waking/keeping active the authorized virtual session's power group.
They use the shell-authorized power binder and cannot bypass keyguard.
SurfaceControl fallback displays and virtual displays are always non-secure so Android continues to blank
`FLAG_SECURE` content. The OpenGL-free primary capture path scales the primary display directly
to the encoder surface and resets on display configuration changes.

`NewDisplayCapture` restores only the pinned 4.1 display flags and affine OpenGL dependency closure.
Dimensions and density are bounded and independent of the video size. The shell
must possess both trusted-display and always-unlocked-display permissions, and the created display's
flags are checked. Input uses its actual display ID; global power and notification-shade operations
are blocked as remote commands. Session startup and interaction wake the actual display power group,
and session-local activity keeps it awake. Some Android 17 builds ignore OWN_DISPLAY_GROUP, so this
intentionally wakes and keeps the physical screen on until disconnect, without dismissing keyguard.
Separate groups are woken through wakeUpWithDisplayId. Home-key control opens the secondary launcher
directly rather than entering the physical keyguard's Home-key policy. Display removal destroys its
tasks. There is no generic launch-control command: the
authorized request supplies either a package, the app-owned launcher, or a local owner-token-bound resolver
that returns a current activity PendingIntent after display creation. No client can supply an Intent
or arbitrary display ID. After a successful send, a completion callback lets the app apply the source
notification's auto-cancel policy to the unchanged notification. Authentication and the additional
per-peer grant remain app-owned.

The payload-free Launcher command (67) can only reopen the app-owned launcher in the current virtual
display, and requires control authorization. It carries no package, Intent, or display ID. App listing
and search remain in a normal non-exported NotiSync Activity, outside the privileged backend.

Resize command 68 carries only three unsigned big-endian 16-bit values: width, height, and DPI.
It requires a virtual session and control authorization, enforces the same size/density bounds as
creation, and resizes only that session's owned VirtualDisplay. Display configuration callbacks
restart the encoder and update touch mapping without recreating the display or relaunching apps.

See `LICENSE-scrcpy` for the Apache License 2.0 terms. Modified files retain their upstream headers.
