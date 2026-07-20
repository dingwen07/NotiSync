# Vendored scrcpy server

This module contains the Android server sources from scrcpy 4.1, pinned to commit
`2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0`.

Upstream: <https://github.com/Genymobile/scrcpy/tree/v4.1/server>

This is a security-minimized derivative, not a copy of the complete upstream server. The retained
source closure is limited to:

- primary-display capture, rotation/fold reset monitoring, and H.264/H.265/AV1 hardware encoding;
- pinned scrcpy 4.1 video/session framing over an app-owned `ParcelFileDescriptor`;
- touch/multitouch, mouse, scroll, bounded text input, navigation keys, and explicit wake/back;
- bounded bidirectional plain-text clipboard synchronization; and
- the Android framework wrappers required by those operations.

`NotiSyncCaptureBackend` owns an explicit session lifecycle for a Shizuku UserService. Network
discovery, authentication, TLS, authorization, and foreground-service state stay in the normal app
process. The privileged module never receives LAN sockets or session keys.

The generic scrcpy CLI/options surface and the source paths for audio, camera, new displays, UHID,
app launch/listing, panels, display-power mutation, file scanning, settings/content-provider access,
codec option injection, VP8/VP9, adb/localabstract transport, and process/shell command execution are
deleted. SurfaceControl fallback displays are always non-secure so Android continues to blank
`FLAG_SECURE` content. The remaining OpenGL-free capture path scales the primary display directly
to the encoder surface and resets on display configuration changes.

See `LICENSE-scrcpy` for the Apache License 2.0 terms. Modified files retain their upstream headers.
