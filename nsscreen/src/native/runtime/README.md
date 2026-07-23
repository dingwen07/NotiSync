# Reproducible LGPL runtime packaging

The release pipeline does not download source code and is not part of `check`, `installDist`, or any
other developer task. It consumes two absolute directories:

- `NOTISYNC_SCREEN_RUNTIME_CACHE`: a persistent cache owned by the release builder;
- `NOTISYNC_SCREEN_SOURCE_DIR`: a source cache containing `SDL3-3.4.12.tar.gz` and
  `ffmpeg-8.1.2.tar.xz` (required only on an immutable runtime-cache miss).

Both archives are verified against `runtime-versions.sh`. A cache entry is accepted only after its
manifest, linked-library names, relative runpaths, runtime FFmpeg license configuration, bundled
sources, and helper self-test all pass. Invalid existing cache entries are never overwritten.

Build and install a host-native release distribution with:

```shell
NOTISYNC_SCREEN_RUNTIME_CACHE=/absolute/path/to/runtime-cache \
NOTISYNC_SCREEN_SOURCE_DIR=/absolute/path/to/source-cache \
./gradlew :notisyncd:installScreenReleaseDist
```

Archive tasks are `:notisyncd:screenReleaseDistZip` and
`:notisyncd:screenReleaseDistTar`. The release is host-native: build Linux artifacts on Linux and
macOS artifacts on macOS. Cross-compilation and universal macOS SDL/FFmpeg binaries are deliberately
not inferred by the script.

Build prerequisites are a C17 compiler, CMake, Make, pkg-config, and the platform SDK/development
headers. Linux additionally requires `patchelf` and X11 and/or Wayland development headers. x86-64
FFmpeg builds require NASM or Yasm for optimized decoding. macOS uses `install_name_tool` and targets
macOS 11 or later.
