#!/bin/sh
# Builds the attach-only scrcpy-derived helper against an LGPL-compatible
# system or distribution-provided FFmpeg and SDL. The release packager is
# responsible for staging the matching shared libraries and notices.
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 OUTPUT" >&2
    exit 2
fi

output=$1
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compiler=${CC:-cc}
pkg_config=${PKG_CONFIG:-pkg-config}

if "$pkg_config" --exists sdl3 libavcodec libavutil libswscale; then
    sdl_package=sdl3
    sdl_define=
elif "$pkg_config" --exists sdl2 libavcodec libavutil libswscale; then
    sdl_package=sdl2
    sdl_define=-DNS_FORCE_SDL2
else
    echo "SDL 2/3 and FFmpeg libavcodec/libavutil/libswscale development files are required" >&2
    exit 3
fi

cflags=$($pkg_config --cflags "$sdl_package" libavcodec libavutil libswscale)
libraries=$($pkg_config --libs "$sdl_package" libavcodec libavutil libswscale)
mkdir -p "$(dirname -- "$output")"

# pkg-config intentionally returns shell-separated compiler arguments.
# shellcheck disable=SC2086
"$compiler" -std=c17 -O2 -Wall -Wextra -Werror $sdl_define $cflags \
    "$source_dir/notisync_screen_helper.c" -o "$output" \
    $libraries -lpthread -lm
chmod 0755 "$output"
