#!/bin/sh
# Pinned inputs for the redistributable desktop screen runtime.
# Keep this file POSIX-sh compatible: it is sourced by the build and validator.

SDL_VERSION=3.4.12
SDL_ARCHIVE=SDL3-3.4.12.tar.gz
SDL_SHA256=f07b958a9ac5020fb7a44cadb957f658b2149c3c8abb4f63145fac9303249db7
SDL_SOURCE_URL=https://github.com/libsdl-org/SDL/releases/download/release-3.4.12/SDL3-3.4.12.tar.gz

FFMPEG_VERSION=8.1.2
FFMPEG_ARCHIVE=ffmpeg-8.1.2.tar.xz
FFMPEG_SHA256=464beb5e7bf0c311e68b45ae2f04e9cc2af88851abb4082231742a74d97b524c
FFMPEG_SOURCE_URL=https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.xz

# Increment this when the build recipe, ABI allowlist, or bundle layout changes.
SCREEN_RUNTIME_RECIPE=1
