# Desktop screen-runtime corresponding source

This directory accompanies the dynamically linked NotiSync desktop screen viewer. It contains the
complete, exact upstream source archives used for the bundled SDL and FFmpeg shared libraries,
their SHA-256 checksums, the complete configure arguments, the scrcpy-derived helper source, and
the build/validation scripts in their original relative layout.

The helper loads the replaceable libraries from the distribution's `lib` directory:

- macOS: `libSDL3.0.dylib`, `libavcodec.62.dylib`, `libavutil.60.dylib`, and
  `libswscale.9.dylib`;
- Linux: `libSDL3.so.0`, `libavcodec.so.62`, `libavutil.so.60`, and `libswscale.so.9`.

You may rebuild compatible modified libraries from the included sources and replace those files.
The helper has no embedded copy of FFmpeg or SDL. The dynamic-loader search path is relative to the
helper and libraries, so replacement does not require modifying the NotiSync executable.

FFmpeg is configured as shared LGPL software with only its native H.264, HEVC, and AV1 decoders,
`libavcodec`, `libavutil`, and `libswscale`. GPL and nonfree code, programs, devices, formats,
network protocols, external codec libraries, hardware acceleration, and static libraries are
disabled. `ffmpeg-configure.args` is the authoritative argument list.

SDL is built as a shared zlib-licensed library. Subsystems unrelated to the viewer are disabled;
`sdl-cmake.args` is the authoritative cache argument list. On Linux the build requires at least one
of the X11 or Wayland video backends to be present and records the selected backend in
`BUILD-METADATA.txt`.

The upstream archives may also be obtained from the URLs recorded in `SOURCES.txt`. Always verify
their hashes against `SOURCE-SHA256SUMS` before building.
