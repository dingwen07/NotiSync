#!/bin/sh
# Validate either a runtime bundle or an installed NotiSync screen-release distribution.
set -eu

export LC_ALL=C
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$script_dir/runtime-versions.sh"

fail() {
    echo "screen-runtime validation: $*" >&2
    exit 3
}

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    fail "usage: $0 ROOT [--distribution]"
fi
root=$1
mode=${2:-}
[ -d "$root" ] || fail "root is not a directory: $root"

case "$mode" in
    "")
        helper="$root/bin/notisync-screen-helper"
        library_dir="$root/lib"
        compliance="$root/compliance"
        ;;
    --distribution)
        helper="$root/bin/notisync-screen-helper"
        library_dir="$root/lib"
        compliance="$root/licenses/screen-runtime"
        ;;
    *) fail "unknown mode: $mode" ;;
esac

host_system=$(uname -s)
host_machine=$(uname -m)
case "$host_system" in
    Darwin)
        target_os=macos
        required_libraries="libSDL3.0.dylib libavcodec.62.dylib libavutil.60.dylib libswscale.9.dylib"
        ;;
    Linux)
        target_os=linux
        required_libraries="libSDL3.so.0 libavcodec.so.62 libavutil.so.60 libswscale.so.9"
        ;;
    *) fail "validation supports only macOS and Linux" ;;
esac
case "$host_machine" in
    arm64|aarch64) target_arch=arm64 ;;
    x86_64|amd64) target_arch=x86_64 ;;
    *) fail "unsupported architecture: $host_machine" ;;
esac

[ -x "$helper" ] || fail "missing executable helper: $helper"
[ -d "$library_dir" ] || fail "missing runtime library directory"
[ -f "$compliance/RUNTIME-SHA256SUMS" ] || fail "missing runtime checksum manifest"
[ -f "$compliance/SOURCE-SHA256SUMS" ] || fail "missing source checksum manifest"
[ -f "$compliance/SOURCE_OFFER.md" ] || fail "missing corresponding-source offer"
[ -f "$compliance/BUILD-METADATA.txt" ] || fail "missing build metadata"
[ -f "$compliance/ffmpeg-configure.args" ] || fail "missing FFmpeg configure arguments"
[ -f "$compliance/sdl-cmake.args" ] || fail "missing SDL configure arguments"
[ -f "$compliance/licenses/SDL-zlib.txt" ] || fail "missing SDL license"
[ -f "$compliance/licenses/FFmpeg-LGPL-2.1.txt" ] || fail "missing FFmpeg LGPL 2.1 license"
[ -f "$compliance/licenses/FFmpeg-LGPL-3.0.txt" ] || fail "missing FFmpeg LGPL 3 license"
[ -f "$compliance/sources/$SDL_ARCHIVE" ] || fail "missing corresponding SDL source"
[ -f "$compliance/sources/$FFMPEG_ARCHIVE" ] || fail "missing corresponding FFmpeg source"

for library in $required_libraries; do
    [ -f "$library_dir/$library" ] || fail "missing exact runtime library: $library"
done
for candidate in "$library_dir"/libSDL3* "$library_dir"/libavcodec* "$library_dir"/libavutil* "$library_dir"/libswscale*; do
    [ -e "$candidate" ] || continue
    candidate_name=$(basename "$candidate")
    case " $required_libraries " in
        *" $candidate_name "*) ;;
        *) fail "unexpected or development runtime library: $candidate_name" ;;
    esac
done

metadata_value() {
    key=$1
    sed -n "s/^$key=//p" "$compliance/BUILD-METADATA.txt"
}
[ "$(metadata_value target-os)" = "$target_os" ] || fail "runtime OS metadata does not match host"
[ "$(metadata_value target-arch)" = "$target_arch" ] || fail "runtime architecture metadata does not match host"
[ "$(metadata_value sdl-version)" = "$SDL_VERSION" ] || fail "SDL version metadata mismatch"
[ "$(metadata_value ffmpeg-version)" = "$FFMPEG_VERSION" ] || fail "FFmpeg version metadata mismatch"
[ "$(metadata_value recipe)" = "$SCREEN_RUNTIME_RECIPE" ] || fail "runtime recipe metadata mismatch"

if command -v sha256sum >/dev/null 2>&1; then
    hash_file() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null 2>&1; then
    hash_file() { shasum -a 256 "$1" | awk '{print $1}'; }
else
    fail "sha256sum or shasum is required"
fi

while read -r expected relative_path; do
    [ -n "$expected" ] || continue
    case "$relative_path" in
        bin/*) actual_path="$root/$relative_path" ;;
        lib/*) actual_path="$root/$relative_path" ;;
        compliance/*)
            if [ "$mode" = --distribution ]; then
                actual_path="$compliance/${relative_path#compliance/}"
            else
                actual_path="$root/$relative_path"
            fi
            ;;
        *) fail "invalid path in runtime manifest: $relative_path" ;;
    esac
    [ -f "$actual_path" ] || fail "runtime manifest file is missing: $relative_path"
    [ "$(hash_file "$actual_path")" = "$expected" ] || fail "runtime checksum mismatch: $relative_path"
done < "$compliance/RUNTIME-SHA256SUMS"

listed_compliance=$(awk '$2 ~ /^compliance\// {print $2}' "$compliance/RUNTIME-SHA256SUMS" | LC_ALL=C sort)
actual_compliance=$(
    cd "$compliance"
    find . -type f ! -name RUNTIME-SHA256SUMS -print | sed 's#^\./#compliance/#' | LC_ALL=C sort
)
[ "$actual_compliance" = "$listed_compliance" ] || fail "compliance directory contains unmanifested or missing files"

while read -r expected relative_path; do
    [ -n "$expected" ] || continue
    [ -f "$compliance/$relative_path" ] || fail "corresponding source is missing: $relative_path"
    [ "$(hash_file "$compliance/$relative_path")" = "$expected" ] || fail "corresponding-source checksum mismatch: $relative_path"
done < "$compliance/SOURCE-SHA256SUMS"

grep -Fx -- "--enable-shared" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg shared build flag is missing"
grep -Fx -- "--disable-static" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg static build is not disabled"
grep -Fx -- "--disable-autodetect" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg autodetection is not disabled"
grep -Fx -- "--disable-everything" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg default components are not disabled"
grep -Fx -- "--disable-programs" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg programs are not disabled"
grep -Fx -- "--disable-doc" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg documentation build is not disabled"
grep -Fx -- "--disable-gpl" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg GPL code is not disabled"
grep -Fx -- "--disable-nonfree" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg nonfree code is not disabled"
grep -Fx -- "--disable-network" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg networking is not disabled"
grep -Fx -- "--disable-hwaccels" "$compliance/ffmpeg-configure.args" >/dev/null || fail "FFmpeg hardware acceleration is not disabled"
grep -Fx -- "--enable-decoder=h264,hevc,av1" "$compliance/ffmpeg-configure.args" >/dev/null || fail "exact decoder allowlist is missing"

if grep -Eiq -- '(^|[-_=])(enable-)?(gpl|nonfree)([-_=]|$)' "$compliance/ffmpeg-configure.args"; then
    # The required --disable flags match this expression too; reject only explicit enablement.
    grep -Eq -- '--enable-(gpl|nonfree)' "$compliance/ffmpeg-configure.args" && fail "forbidden FFmpeg license flag enabled"
fi
while IFS= read -r configure_argument; do
    case "$configure_argument" in
        --enable-shared|--enable-pic|--enable-pthreads|--enable-avcodec|--enable-avutil|--enable-swscale|--enable-decoder=h264,hevc,av1) ;;
        --enable-*) fail "unexpected FFmpeg component enabled: $configure_argument" ;;
    esac
done < "$compliance/ffmpeg-configure.args"

for sdl_argument in \
    -DSDL_SHARED=ON \
    -DSDL_STATIC=OFF \
    -DSDL_TEST_LIBRARY=OFF \
    -DSDL_TESTS=OFF \
    -DSDL_EXAMPLES=OFF \
    -DSDL_AUDIO=OFF \
    -DSDL_CAMERA=OFF \
    -DSDL_GPU=OFF \
    -DSDL_JOYSTICK=OFF \
    -DSDL_HAPTIC=OFF \
    -DSDL_HIDAPI=OFF \
    -DSDL_SENSOR=OFF \
    -DSDL_VULKAN=OFF
do
    grep -Fx -- "$sdl_argument" "$compliance/sdl-cmake.args" >/dev/null || fail "required SDL restriction is missing: $sdl_argument"
done
if [ "$target_os" = macos ]; then
    grep -Fx -- "-DSDL_COCOA=ON" "$compliance/sdl-cmake.args" >/dev/null || fail "SDL Cocoa backend is not enabled"
    grep -Fx -- "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0" "$compliance/sdl-cmake.args" >/dev/null || fail "macOS deployment target mismatch"
else
    if [ "$(metadata_value sdl-video-x11)" != true ] && [ "$(metadata_value sdl-video-wayland)" != true ]; then
        fail "SDL metadata has no Linux window-system backend"
    fi
fi

if [ "$target_os" = macos ]; then
    command -v otool >/dev/null 2>&1 || fail "otool is required"
    helper_rpaths=$(otool -l "$helper" | awk '/cmd LC_RPATH/{seen=1; next} seen && /path /{print $2; seen=0}')
    echo "$helper_rpaths" | grep -Fx '@loader_path/../lib' >/dev/null || fail "helper lacks relocatable macOS rpath"
    for binary in "$helper" "$library_dir/"*.dylib; do
        case "$binary" in
            "$helper") ;;
            *)
                binary_rpaths=$(otool -l "$binary" | awk '/cmd LC_RPATH/{seen=1; next} seen && /path /{print $2; seen=0}')
                echo "$binary_rpaths" | grep -Fx '@loader_path' >/dev/null || fail "$(basename "$binary") lacks @loader_path rpath"
                ;;
        esac
        if [ "$binary" != "$helper" ]; then
            [ "$(otool -D "$binary" | sed -n '2p')" = "@rpath/$(basename "$binary")" ] || fail "incorrect dylib install name: $(basename "$binary")"
        fi
        otool -L "$binary" | awk 'NR > 1 {print $1}' | while IFS= read -r dependency; do
            case "$dependency" in
                @rpath/libSDL3.0.dylib|@rpath/libavcodec.62.dylib|@rpath/libavutil.60.dylib|@rpath/libswscale.9.dylib|/usr/lib/*|/System/Library/*) ;;
                *) fail "non-system or non-relocatable dependency in $(basename "$binary"): $dependency" ;;
            esac
        done
    done
else
    command -v readelf >/dev/null 2>&1 || fail "readelf is required"
    helper_dynamic=$(readelf -d "$helper")
    echo "$helper_dynamic" | grep -F '$ORIGIN/../lib' >/dev/null || fail "helper lacks relocatable Linux runpath"
    echo "$helper_dynamic" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' | while IFS= read -r dependency; do
        case "$dependency" in
            libSDL3.so.0|libavcodec.so.62|libavutil.so.60|libswscale.so.9|libc.so.*|libm.so.*|libdl.so.*|libpthread.so.*|librt.so.*|libgcc_s.so.*|ld-linux*.so.*) ;;
            *) fail "unexpected Linux dependency in helper: $dependency" ;;
        esac
    done
    for library in "$library_dir/"*.so.*; do
        dynamic=$(readelf -d "$library")
        echo "$dynamic" | grep -F '$ORIGIN' >/dev/null || fail "$(basename "$library") lacks relocatable Linux runpath"
        soname=$(echo "$dynamic" | sed -n 's/.*Library soname: \[\([^]]*\)\].*/\1/p')
        [ "$soname" = "$(basename "$library")" ] || fail "incorrect Linux SONAME: $(basename "$library")"
        echo "$dynamic" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' | while IFS= read -r dependency; do
            case "$dependency" in
                libSDL3.so.0|libavcodec.so.62|libavutil.so.60|libswscale.so.9|libc.so.*|libm.so.*|libdl.so.*|libpthread.so.*|librt.so.*|libgcc_s.so.*|ld-linux*.so.*) ;;
                *) fail "unexpected Linux dependency in $(basename "$library"): $dependency" ;;
            esac
        done
    done
    if command -v ldd >/dev/null 2>&1 && ldd "$helper" | grep -q 'not found'; then
        fail "helper has unresolved Linux shared libraries"
    fi
fi

command -v file >/dev/null 2>&1 || fail "file is required for architecture validation"
binary_description=$(file "$helper")
case "$target_os-$target_arch" in
    macos-arm64) echo "$binary_description" | grep -Eq 'arm64|universal' || fail "helper is not arm64" ;;
    macos-x86_64) echo "$binary_description" | grep -Eq 'x86_64|universal' || fail "helper is not x86_64" ;;
    linux-arm64) echo "$binary_description" | grep -Eiq 'aarch64|ARM aarch64' || fail "helper is not Linux arm64" ;;
    linux-x86_64) echo "$binary_description" | grep -Eiq 'x86-64|x86_64' || fail "helper is not Linux x86-64" ;;
esac

case "$target_os" in
    macos) DYLD_LIBRARY_PATH="$library_dir" "$helper" --self-test >/dev/null ;;
    linux) LD_LIBRARY_PATH="$library_dir" "$helper" --self-test >/dev/null ;;
esac
case "$target_os" in
    macos) DYLD_LIBRARY_PATH="$library_dir" "$helper" --check-runtime >/dev/null ;;
    linux) LD_LIBRARY_PATH="$library_dir" "$helper" --check-runtime >/dev/null ;;
esac

echo "screen-runtime validation: OK ($target_os-$target_arch, SDL $SDL_VERSION, FFmpeg $FFMPEG_VERSION)"
