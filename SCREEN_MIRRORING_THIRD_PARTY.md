# Screen mirroring third-party notices

The screen mirroring feature incorporates or links the following components:

| Component | Pinned version | Use | License |
|---|---:|---|---|
| scrcpy server | 4.1 / `2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0` | Android capture, MediaCodec streaming, input and clipboard protocol | Apache-2.0 |
| Shizuku API/provider | 13.1.5 | Bind the separately installed Shizuku manager and run the privileged UserService | MIT |
| Bouncy Castle TLS | 1.85 | TLS 1.3 external-PSK session transport | MIT |
| JmDNS | 3.6.3 | Desktop DNS-SD session advertisement | Apache-2.0 |
| SDL | 3.4.12 (SDL 2 source-compatible fallback) | Desktop window, input, and clipboard | zlib |
| FFmpeg | 8.1.2 | Software H.264, HEVC, and AV1 decoding and pixel conversion | LGPL-2.1-or-later |

The scrcpy sources are vendored in `:scrcpy-server`. NotiSync adds an app-owned file-descriptor
transport, a restricted control-message policy, and an explicit lifecycle backend. See
`scrcpy-server/upstream/README.md` and the adjacent upstream license.

Shizuku Manager itself is not bundled or redistributed. The Android package includes license texts
under `assets/licenses` for the linked components. The scrcpy-derived attach-only desktop helper is
under `nsscreen/src/native/helper`; it receives authenticated video/control streams over private Unix
sockets and never handles LAN session secrets.

The release desktop distributions stage pinned SDL 3.4.12 and FFmpeg 8.1.2 shared libraries, the
complete verified upstream source archives, replacement/relinking instructions, notices, exact
configure arguments, build metadata, scripts, and a file manifest. The helper and libraries use only
relative runpaths/install names. The helper additionally rejects FFmpeg libraries configured with GPL
or nonfree components at runtime.

Developer multimedia packages are used only by the ordinary helper compile/self-tests. They are
never copied by the release tasks. The release pipeline has no downloader and is intentionally not
part of `check`, `installDist`, or the developer install script. With an absolute source cache and
immutable runtime cache, produce and validate the host-native release via:

```shell
NOTISYNC_SCREEN_RUNTIME_CACHE=/absolute/path/to/runtime-cache \
NOTISYNC_SCREEN_SOURCE_DIR=/absolute/path/to/source-cache \
./gradlew :notisyncd:installScreenReleaseDist
```

See `nsscreen/src/native/runtime/README.md` for the source-cache names, platform prerequisites, ZIP
and tar tasks, and cache behavior. Missing inputs, mismatched source hashes, an invalid existing cache
entry, a non-relocatable dependency, absent corresponding source, unexpected ABI, failed self-test,
or GPL/nonfree FFmpeg configuration fails the release task closed.
