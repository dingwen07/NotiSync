#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/notisync-ios-relay-tests.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
for optimization in -Onone -O; do
    xcrun swiftc -swift-version 6 -strict-concurrency=complete -parse-as-library \
        "$optimization" -module-cache-path "$test_dir/modules" \
        ios/NotiSyncKit/ProtocolModels.swift ios/NotiSyncKit/Base64URL.swift \
        ios/Tests/ScreenRelayValidationTests.swift \
        -o "$test_dir/relay-tests"
    "$test_dir/relay-tests"
done
