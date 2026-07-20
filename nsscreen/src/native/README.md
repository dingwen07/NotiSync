# Native screen helper

`notisync-screen-helper` is an attach-only SDL/FFmpeg viewer. Its scrcpy 4.1-derived
media/control protocol implementation connects only to private Unix sockets owned by
`nsscreen`; LAN endpoints and TLS keys remain in the Kotlin process.

The helper supports SDL 3 or SDL 2 for developer builds and dynamically linked FFmpeg H.264,
HEVC, and AV1 decoders. Release artifacts use only pinned SDL 3.4.12 and FFmpeg 8.1.2. The
authoritative, fail-closed build is in `../runtime`; it starts FFmpeg from this minimal surface:

```text
./configure \
  --enable-shared \
  --disable-static \
  --disable-programs \
  --disable-doc \
  --disable-network \
  --disable-everything \
  --enable-avcodec \
  --enable-avutil \
  --enable-swscale \
  --enable-decoder=h264,hevc,av1
```

Do not enable `--enable-gpl` or `--enable-nonfree`. The helper checks the linked
`libavcodec` configuration at runtime and refuses such builds. Package the corresponding
shared-library source offer or relinkable objects and license notices as required by the
FFmpeg LGPL. SDL's zlib notice and the scrcpy Apache-2.0 notice must also ship.

For a development build:

```shell
src/native/helper/build-helper.sh build/native/notisync-screen-helper
build/native/notisync-screen-helper --self-test
```

The build script selects SDL 3 first and falls back to SDL 2. `CC` and `PKG_CONFIG` may
point at a release sysroot/toolchain.

`./gradlew :nsscreen:checkScreenHelperRuntime` checks only the developer-linked helper. It is not a
release packaging gate, because developer Homebrew/Linux multimedia packages may be GPL-enabled.
The release-only `:notisyncd:installScreenReleaseDist` task builds or retrieves the immutable
runtime from explicit caches, validates it, stages its exact shared libraries and corresponding
source, and validates the installed result. See `../runtime/README.md` for the cache contract and
archive tasks.
