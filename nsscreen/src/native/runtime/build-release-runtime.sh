#!/bin/sh
# Build or materialize the pinned, host-native SDL/FFmpeg screen runtime.
# This script intentionally has no downloader. Source and runtime caches are explicit inputs.
set -eu

umask 022
export LC_ALL=C
export TZ=UTC
export SOURCE_DATE_EPOCH=1782864000
export ZERO_AR_DATE=1

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$script_dir/runtime-versions.sh"

fail() {
    echo "screen-runtime: $*" >&2
    exit 2
}

if [ "$#" -ne 1 ]; then
    fail "usage: $0 OUTPUT_DIRECTORY"
fi

output_dir=$1
runtime_cache=${NOTISYNC_SCREEN_RUNTIME_CACHE:-}
source_cache=${NOTISYNC_SCREEN_SOURCE_DIR:-}

case "$output_dir" in
    /*) ;;
    *) fail "OUTPUT_DIRECTORY must be absolute" ;;
esac
case "$runtime_cache" in
    /*) ;;
    *) fail "NOTISYNC_SCREEN_RUNTIME_CACHE must be an absolute directory" ;;
esac
if [ -e "$output_dir" ] && [ "$(find "$output_dir" -mindepth 1 -maxdepth 1 2>/dev/null | head -n 1)" ]; then
    fail "output directory is not empty; run the Gradle cleanScreenReleaseRuntime task first"
fi

host_system=$(uname -s)
host_machine=$(uname -m)
case "$host_system" in
    Darwin) target_os=macos ;;
    Linux) target_os=linux ;;
    *) fail "only macOS and Linux release runtimes are supported (found $host_system)" ;;
esac
case "$host_machine" in
    arm64|aarch64) target_arch=arm64 ;;
    x86_64|amd64) target_arch=x86_64 ;;
    *) fail "unsupported release architecture: $host_machine" ;;
esac
if [ "$target_os" = macos ]; then
    # build-helper.sh intentionally remains the developer entry point; clang honors this standard
    # environment variable without requiring release-only flags in that script.
    export MACOSX_DEPLOYMENT_TARGET=11.0
fi

if command -v sha256sum >/dev/null 2>&1; then
    hash_file() { sha256sum "$1" | awk '{print $1}'; }
    hash_stream() { sha256sum | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
    hash_file() { shasum -a 256 "$1" | awk '{print $1}'; }
    hash_stream() { shasum -a 256 | awk '{print $1}'; }
else
    fail "sha256sum or shasum is required"
fi
recipe_fingerprint=$(
    for recipe_file in \
        "$script_dir/runtime-versions.sh" \
        "$script_dir/build-release-runtime.sh" \
        "$script_dir/validate-release-runtime.sh" \
        "$script_dir/SOURCE_OFFER.md" \
        "$script_dir/../helper/build-helper.sh" \
        "$script_dir/../helper/notisync_screen_helper.c"
    do
        printf '%s  %s\n' "$(hash_file "$recipe_file")" "$(basename "$recipe_file")"
    done | hash_stream
)
cache_key="$target_os-$target_arch-sdl-$SDL_VERSION-ffmpeg-$FFMPEG_VERSION-r$SCREEN_RUNTIME_RECIPE-$(printf '%.16s' "$recipe_fingerprint")"
cache_bundle="$runtime_cache/$cache_key"
validator="$script_dir/validate-release-runtime.sh"

copy_bundle() {
    source_bundle=$1
    mkdir -p "$output_dir"
    cp -R "$source_bundle/." "$output_dir/"
    "$validator" "$output_dir"
}

if [ -d "$cache_bundle" ]; then
    echo "screen-runtime: validating cached $cache_key"
    "$validator" "$cache_bundle"
    copy_bundle "$cache_bundle"
    exit 0
fi
if [ -e "$cache_bundle" ]; then
    fail "runtime cache entry exists but is not a directory: $cache_bundle"
fi

case "$source_cache" in
    /*) ;;
    *) fail "cache miss requires absolute NOTISYNC_SCREEN_SOURCE_DIR" ;;
esac
sdl_archive="$source_cache/$SDL_ARCHIVE"
ffmpeg_archive="$source_cache/$FFMPEG_ARCHIVE"
[ -f "$sdl_archive" ] || fail "missing pinned source archive: $sdl_archive"
[ -f "$ffmpeg_archive" ] || fail "missing pinned source archive: $ffmpeg_archive"

[ "$(hash_file "$sdl_archive")" = "$SDL_SHA256" ] || fail "$SDL_ARCHIVE SHA-256 mismatch"
[ "$(hash_file "$ffmpeg_archive")" = "$FFMPEG_SHA256" ] || fail "$FFMPEG_ARCHIVE SHA-256 mismatch"

for tool in cmake make pkg-config tar; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is required to build a cache miss"
done
compiler=${CC:-cc}
command -v "$compiler" >/dev/null 2>&1 || fail "$compiler is required to build a cache miss"
if [ "$target_os" = linux ]; then
    command -v "${PATCHELF:-patchelf}" >/dev/null 2>&1 || fail "patchelf is required on Linux"
else
    command -v install_name_tool >/dev/null 2>&1 || fail "install_name_tool is required on macOS"
    command -v otool >/dev/null 2>&1 || fail "otool is required on macOS"
fi
if [ "$target_arch" = x86_64 ] && ! command -v nasm >/dev/null 2>&1 && ! command -v yasm >/dev/null 2>&1; then
    fail "NASM or Yasm is required for the optimized x86-64 FFmpeg release build"
fi

jobs=${NOTISYNC_SCREEN_JOBS:-}
if [ -z "$jobs" ]; then
    if command -v getconf >/dev/null 2>&1; then
        jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)
    fi
    if [ -z "$jobs" ] && command -v sysctl >/dev/null 2>&1; then
        jobs=$(sysctl -n hw.ncpu 2>/dev/null || true)
    fi
    jobs=${jobs:-1}
fi
case "$jobs" in
    *[!0-9]*|0) fail "NOTISYNC_SCREEN_JOBS must be a positive integer" ;;
esac

mkdir -p "$runtime_cache"
work_dir=$(mktemp -d "$runtime_cache/.notisync-screen-runtime.XXXXXX")
cleanup() {
    rm -rf -- "$work_dir"
}
trap cleanup EXIT HUP INT TERM

mkdir -p "$work_dir/src" "$work_dir/build" "$work_dir/prefix" "$work_dir/bundle/bin" \
    "$work_dir/bundle/lib" "$work_dir/bundle/compliance/licenses" \
    "$work_dir/bundle/compliance/sources" "$work_dir/bundle/compliance/build-scripts/runtime" \
    "$work_dir/bundle/compliance/build-scripts/helper"
tar -xzf "$sdl_archive" -C "$work_dir/src"
tar -xJf "$ffmpeg_archive" -C "$work_dir/src"
sdl_source="$work_dir/src/SDL3-$SDL_VERSION"
ffmpeg_source="$work_dir/src/ffmpeg-$FFMPEG_VERSION"
[ -d "$sdl_source" ] || fail "unexpected SDL archive root"
[ -d "$ffmpeg_source" ] || fail "unexpected FFmpeg archive root"

common_cflags="-O2 -fPIC -ffile-prefix-map=$work_dir=/usr/src/notisync-screen"
if [ "$target_os" = macos ]; then
    common_cflags="$common_cflags -mmacosx-version-min=11.0"
fi
export CFLAGS="$common_cflags"
export CPPFLAGS=${CPPFLAGS:-}

# SDL is intentionally constrained to the window/input/clipboard/rendering surface used by the
# helper. Platform video backends remain dynamically loaded where SDL supports that behavior.
set -- \
    -S "$sdl_source" \
    -B "$work_dir/build/sdl" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$work_dir/prefix" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_SKIP_BUILD_RPATH=OFF \
    -DCMAKE_BUILD_WITH_INSTALL_RPATH=OFF \
    -DCMAKE_INSTALL_RPATH_USE_LINK_PATH=OFF \
    -DSDL_SHARED=ON \
    -DSDL_STATIC=OFF \
    -DSDL_TEST_LIBRARY=OFF \
    -DSDL_TESTS=OFF \
    -DSDL_INSTALL_TESTS=OFF \
    -DSDL_EXAMPLES=OFF \
    -DSDL_INSTALL_DOCS=OFF \
    -DSDL_UNINSTALL=OFF \
    -DSDL_AUDIO=OFF \
    -DSDL_CAMERA=OFF \
    -DSDL_GPU=OFF \
    -DSDL_JOYSTICK=OFF \
    -DSDL_HAPTIC=OFF \
    -DSDL_HIDAPI=OFF \
    -DSDL_POWER=OFF \
    -DSDL_SENSOR=OFF \
    -DSDL_DIALOG=OFF \
    -DSDL_TRAY=OFF \
    -DSDL_DBUS=OFF \
    -DSDL_IBUS=OFF \
    -DSDL_LIBURING=OFF \
    -DSDL_LIBUDEV=OFF \
    -DSDL_FRIBIDI=OFF \
    -DSDL_LIBTHAI=OFF \
    -DSDL_OPENVR=OFF \
    -DSDL_KMSDRM=OFF \
    -DSDL_RPI=OFF \
    -DSDL_ROCKCHIP=OFF \
    -DSDL_VULKAN=OFF \
    -DSDL_RENDER_VULKAN=OFF \
    -DSDL_RENDER_GPU=OFF \
    -DSDL_VENDOR_INFO=NotiSync-screen-runtime

if [ "$target_os" = macos ]; then
    set -- "$@" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 \
        -DCMAKE_INSTALL_NAME_DIR=@rpath \
        -DCMAKE_INSTALL_RPATH=@loader_path \
        -DSDL_COCOA=ON \
        -DSDL_METAL=ON \
        -DSDL_RENDER_METAL=ON \
        -DSDL_X11=OFF \
        -DSDL_WAYLAND=OFF
else
    set -- "$@" \
        -DCMAKE_INSTALL_RPATH=\$ORIGIN \
        -DSDL_COCOA=OFF \
        -DSDL_METAL=OFF \
        -DSDL_RENDER_METAL=OFF \
        -DSDL_X11=ON \
        -DSDL_X11_SHARED=ON \
        -DSDL_WAYLAND=ON \
        -DSDL_WAYLAND_SHARED=ON \
        -DSDL_WAYLAND_LIBDECOR_SHARED=ON \
        -DSDL_DEPS_SHARED=ON
fi
printf '%s\n' "$@" | sed "s#$work_dir#/usr/src/notisync-screen-build#g" > "$work_dir/sdl-cmake.args"
cmake "$@"
cmake --build "$work_dir/build/sdl" --parallel "$jobs"
cmake --install "$work_dir/build/sdl"

if [ "$target_os" = linux ]; then
    sdl_build_config="$work_dir/build/sdl/include-config-release/build_config/SDL_build_config.h"
    if [ ! -f "$sdl_build_config" ]; then
        sdl_build_config=$(find "$work_dir/build/sdl" -name SDL_build_config.h -type f | head -n 1)
    fi
    [ -f "$sdl_build_config" ] || fail "could not inspect the SDL Linux build configuration"
    if ! grep -Eq 'SDL_VIDEO_DRIVER_(X11|WAYLAND)[[:space:]]+1' "$sdl_build_config"; then
        fail "SDL was built without an X11 or Wayland video backend"
    fi
fi

# FFmpeg remains LGPL and contains only the native software decoders used by the MVP.
set -- \
    --prefix="$work_dir/prefix" \
    --libdir="$work_dir/prefix/lib" \
    --shlibdir="$work_dir/prefix/lib" \
    --cc="$compiler" \
    --enable-shared \
    --disable-static \
    --enable-pic \
    --disable-autodetect \
    --disable-everything \
    --disable-programs \
    --disable-doc \
    --disable-debug \
    --disable-network \
    --disable-gpl \
    --disable-version3 \
    --disable-nonfree \
    --disable-hwaccels \
    --enable-pthreads \
    --enable-avcodec \
    --enable-avutil \
    --enable-swscale \
    --enable-decoder=h264,hevc,av1 \
    --extra-cflags="$common_cflags"
if [ "$target_os" = macos ]; then
    set -- "$@" --install-name-dir=@rpath
fi
printf '%s\n' "$@" | sed "s#$work_dir#/usr/src/notisync-screen-build#g" > "$work_dir/ffmpeg-configure.args"
mkdir -p "$work_dir/build/ffmpeg"
(
    cd "$work_dir/build/ffmpeg"
    "$ffmpeg_source/configure" "$@"
    make -j "$jobs"
    make install
)

export PKG_CONFIG_PATH="$work_dir/prefix/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$work_dir/prefix/lib/pkgconfig"
PKG_CONFIG=pkg-config CC="$compiler" \
    "$script_dir/../helper/build-helper.sh" "$work_dir/bundle/bin/notisync-screen-helper"

copy_runtime_library() {
    installed_name=$1
    bundled_name=$2
    [ -e "$work_dir/prefix/lib/$installed_name" ] || fail "built runtime is missing $installed_name"
    cp -L "$work_dir/prefix/lib/$installed_name" "$work_dir/bundle/lib/$bundled_name"
    chmod 0755 "$work_dir/bundle/lib/$bundled_name"
}

if [ "$target_os" = macos ]; then
    copy_runtime_library libSDL3.0.dylib libSDL3.0.dylib
    copy_runtime_library libavcodec.62.dylib libavcodec.62.dylib
    copy_runtime_library libavutil.60.dylib libavutil.60.dylib
    copy_runtime_library libswscale.9.dylib libswscale.9.dylib

    for library in "$work_dir/bundle/lib/"*.dylib; do
        base_name=$(basename "$library")
        install_name_tool -id "@rpath/$base_name" "$library"
        # The first otool entry for a dylib is LC_ID_DYLIB, already set above; only rewrite
        # subsequent LC_LOAD_DYLIB entries.
        otool -L "$library" | awk 'NR > 2 {print $1}' | while IFS= read -r dependency; do
            case "$dependency" in
                "$work_dir/prefix/lib/"*|@rpath/libSDL3*|@rpath/libavcodec*|@rpath/libavutil*|@rpath/libswscale*)
                    dependency_base=$(basename "$dependency")
                    case "$dependency_base" in
                        libSDL3*) replacement=@rpath/libSDL3.0.dylib ;;
                        libavcodec*) replacement=@rpath/libavcodec.62.dylib ;;
                        libavutil*) replacement=@rpath/libavutil.60.dylib ;;
                        libswscale*) replacement=@rpath/libswscale.9.dylib ;;
                        *) continue ;;
                    esac
                    install_name_tool -change "$dependency" "$replacement" "$library"
                    ;;
            esac
        done
        install_name_tool -add_rpath @loader_path "$library" 2>/dev/null || true
    done
    otool -L "$work_dir/bundle/bin/notisync-screen-helper" | awk 'NR > 1 {print $1}' | while IFS= read -r dependency; do
        case "$dependency" in
            "$work_dir/prefix/lib/"*|@rpath/libSDL3*|@rpath/libavcodec*|@rpath/libavutil*|@rpath/libswscale*)
                dependency_base=$(basename "$dependency")
                case "$dependency_base" in
                    libSDL3*) replacement=@rpath/libSDL3.0.dylib ;;
                    libavcodec*) replacement=@rpath/libavcodec.62.dylib ;;
                    libavutil*) replacement=@rpath/libavutil.60.dylib ;;
                    libswscale*) replacement=@rpath/libswscale.9.dylib ;;
                    *) continue ;;
                esac
                install_name_tool -change "$dependency" "$replacement" "$work_dir/bundle/bin/notisync-screen-helper"
                ;;
        esac
    done
    install_name_tool -add_rpath @loader_path/../lib "$work_dir/bundle/bin/notisync-screen-helper" 2>/dev/null || true
else
    copy_runtime_library libSDL3.so.0 libSDL3.so.0
    copy_runtime_library libavcodec.so.62 libavcodec.so.62
    copy_runtime_library libavutil.so.60 libavutil.so.60
    copy_runtime_library libswscale.so.9 libswscale.so.9
    patchelf_command=${PATCHELF:-patchelf}
    "$patchelf_command" --set-rpath '$ORIGIN/../lib' "$work_dir/bundle/bin/notisync-screen-helper"
    for library in "$work_dir/bundle/lib/"*.so.*; do
        "$patchelf_command" --set-rpath '$ORIGIN' "$library"
    done
fi

cp "$sdl_archive" "$work_dir/bundle/compliance/sources/$SDL_ARCHIVE"
cp "$ffmpeg_archive" "$work_dir/bundle/compliance/sources/$FFMPEG_ARCHIVE"
cp "$sdl_source/LICENSE.txt" "$work_dir/bundle/compliance/licenses/SDL-zlib.txt"
cp "$ffmpeg_source/COPYING.LGPLv2.1" "$work_dir/bundle/compliance/licenses/FFmpeg-LGPL-2.1.txt"
cp "$ffmpeg_source/COPYING.LGPLv3" "$work_dir/bundle/compliance/licenses/FFmpeg-LGPL-3.0.txt"
cp "$ffmpeg_source/LICENSE.md" "$work_dir/bundle/compliance/licenses/FFmpeg-LICENSE.md"
cp "$script_dir/SOURCE_OFFER.md" "$work_dir/bundle/compliance/SOURCE_OFFER.md"
cp "$script_dir/runtime-versions.sh" "$work_dir/bundle/compliance/build-scripts/runtime/runtime-versions.sh"
cp "$script_dir/build-release-runtime.sh" "$work_dir/bundle/compliance/build-scripts/runtime/build-release-runtime.sh"
cp "$script_dir/validate-release-runtime.sh" "$work_dir/bundle/compliance/build-scripts/runtime/validate-release-runtime.sh"
cp "$script_dir/SOURCE_OFFER.md" "$work_dir/bundle/compliance/build-scripts/runtime/SOURCE_OFFER.md"
cp "$script_dir/../helper/build-helper.sh" "$work_dir/bundle/compliance/build-scripts/helper/build-helper.sh"
cp "$script_dir/../helper/notisync_screen_helper.c" "$work_dir/bundle/compliance/build-scripts/helper/notisync_screen_helper.c"
cp "$work_dir/sdl-cmake.args" "$work_dir/bundle/compliance/sdl-cmake.args"
cp "$work_dir/ffmpeg-configure.args" "$work_dir/bundle/compliance/ffmpeg-configure.args"

{
    echo "$SDL_SHA256  sources/$SDL_ARCHIVE"
    echo "$FFMPEG_SHA256  sources/$FFMPEG_ARCHIVE"
} > "$work_dir/bundle/compliance/SOURCE-SHA256SUMS"
{
    echo "cache-key=$cache_key"
    echo "target-os=$target_os"
    echo "target-arch=$target_arch"
    echo "sdl-version=$SDL_VERSION"
    echo "ffmpeg-version=$FFMPEG_VERSION"
    echo "recipe=$SCREEN_RUNTIME_RECIPE"
    echo "recipe-fingerprint=$recipe_fingerprint"
    echo "source-date-epoch=$SOURCE_DATE_EPOCH"
    printf 'compiler='
    "$compiler" --version | sed -n '1p'
    printf 'cmake='
    cmake --version | sed -n '1p'
    if [ "$target_os" = linux ]; then
        if grep -Eq 'SDL_VIDEO_DRIVER_X11[[:space:]]+1' "$sdl_build_config"; then echo "sdl-video-x11=true"; else echo "sdl-video-x11=false"; fi
        if grep -Eq 'SDL_VIDEO_DRIVER_WAYLAND[[:space:]]+1' "$sdl_build_config"; then echo "sdl-video-wayland=true"; else echo "sdl-video-wayland=false"; fi
    else
        echo "sdl-video-cocoa=true"
        echo "macos-deployment-target=11.0"
    fi
} > "$work_dir/bundle/compliance/BUILD-METADATA.txt"
{
    echo "$SDL_ARCHIVE $SDL_SOURCE_URL"
    echo "$FFMPEG_ARCHIVE $FFMPEG_SOURCE_URL"
} > "$work_dir/bundle/compliance/SOURCES.txt"

(
    cd "$work_dir/bundle"
    find bin lib compliance -type f ! -name RUNTIME-SHA256SUMS -print | LC_ALL=C sort | while IFS= read -r bundled_file; do
        echo "$(hash_file "$bundled_file")  $bundled_file"
    done > compliance/RUNTIME-SHA256SUMS
)

"$validator" "$work_dir/bundle"
if [ -e "$cache_bundle" ]; then
    fail "runtime cache entry appeared concurrently; refusing to overwrite $cache_bundle"
fi
mv "$work_dir/bundle" "$cache_bundle"
copy_bundle "$cache_bundle"
echo "screen-runtime: materialized $cache_key at $output_dir"
