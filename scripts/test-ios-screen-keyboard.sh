#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/notisync-ios-keyboard-tests.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -swift-version 6 -strict-concurrency=complete -parse-as-library \
    -module-cache-path "$test_dir/modules" \
    ios/NotiSync/ScreenKeyboardInput.swift ios/Tests/ScreenKeyboardInputTests.swift \
    -o "$test_dir/keyboard-tests"
"$test_dir/keyboard-tests"
