#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/notisync-ios-screen-tests.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -parse-as-library -module-cache-path "$test_dir/modules" \
    ios/NotiSyncKit/ProtocolModels.swift \
    ios/NotiSync/ScreenVirtualDisplayPolicy.swift ios/Tests/ScreenVirtualDisplayTests.swift \
    -o "$test_dir/screen-tests"
"$test_dir/screen-tests"
