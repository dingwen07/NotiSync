#!/bin/sh
# Xcode Cloud discovers this hook beside NotiSync.xcodeproj, before xcodebuild.
# https://developer.apple.com/documentation/xcode/writing-custom-build-scripts
set -eu

cd "${CI_PRIMARY_REPOSITORY_PATH:?Xcode Cloud must provide the repository path}"

# Restore the ignored Firebase resource before installing tools or compiling.
python3 ios/ci_scripts/prepare_firebase_config.py

# Homebrew is available in Xcode Cloud; installing the keg needs no sudo or
# system-wide Java symlink. Select the version required by the Gradle toolchain.
export HOMEBREW_NO_AUTO_UPDATE=1
export HOMEBREW_NO_INSTALL_CLEANUP=1
brew install openjdk@21
java_prefix=$(brew --prefix openjdk@21)
export JAVA_HOME="$java_prefix/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Java doesn't read Xcode Cloud's HTTP(S)_PROXY environment variables. Pass
# their host/port to every JVM, including the wrapper and Kotlin/Native tools.
proxy_options=$(python3 - <<'PY'
import os
import shlex
from urllib.parse import urlsplit

options = []
for protocol in ("http", "https"):
    variable = f"{protocol.upper()}_PROXY"
    value = os.environ.get(variable)
    if not value:
        continue
    try:
        proxy = urlsplit(value)
        port = proxy.port or 80
        if proxy.scheme != "http" or not proxy.hostname:
            raise ValueError("expected an HTTP forward proxy")
        if proxy.username is not None or proxy.password is not None:
            raise ValueError("authenticated proxies are not supported")
    except ValueError:
        # Never include the URL: workflow environment variables may be secret.
        raise SystemExit(f"{variable} must be an HTTP proxy URL without credentials")
    options.extend((
        f"-D{protocol}.proxyHost={proxy.hostname}",
        f"-D{protocol}.proxyPort={port}",
    ))

print(shlex.join(options))
PY
)
if [ -n "$proxy_options" ]; then
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }$proxy_options"
fi

# Configure only the shared module so an iOS-only build needs no Android SDK.
# The Xcode project references the release XCFramework for every configuration.
./gradlew --no-daemon --console=plain --configure-on-demand \
    :protocol:assembleNotiSyncProtocolReleaseXCFramework

framework_path=protocol/build/XCFrameworks/release/NotiSyncProtocol.xcframework
if [ ! -f "$framework_path/Info.plist" ]; then
    echo "error: Gradle did not produce $framework_path" >&2
    exit 1
fi
echo "Prepared $framework_path for Xcode."
