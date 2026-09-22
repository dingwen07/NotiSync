# Development

[Documentation](README.md) · [Architecture](architecture.md) · [Self-hosting](self-hosting.md)

## Build setup

Clone the repository and run build commands from its root:

```bash
git clone https://github.com/dingwen07/NotiSync.git
cd NotiSync
```

Install JDK 21 and Android SDK Platform 37. Use the bundled Gradle wrapper; a separate Gradle
installation is unnecessary. Configure the SDK through Android Studio, `ANDROID_HOME`, or an
untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`. On Windows,
use forward slashes in that properties value, for example `sdk.dir=C:/Android/Sdk`.

> [!NOTE]
> The root build includes the Android module even when targeting the broker or desktop tools.
> Keep the Android SDK configured for those Gradle invocations too.

In PowerShell, replace `./gradlew` in the examples with `.\gradlew.bat`.

## Android

The app targets SDK 37 and supports Android API 34 and later. Before building:

1. Register the Android app in a Firebase project you control, matching the application ID
   `net.extrawdw.apps.notisync` (or your fork's ID).
2. Download its `google-services.json` into `app/`. This file is ignored by Git and is **not**
   included in a fresh checkout.
3. Use a broker configured for that Firebase project if you need FCM delivery. An integrity-enforcing
   broker must also accept your app's App Check configuration; debug builds use the debug provider.

```bash
./gradlew :app:assembleDebug
```

Find the APK under `app/build/outputs/apk/debug/`. For a release artifact, use `:app:assembleRelease`
or `:app:bundleRelease` and supply your own Android signing configuration. A debug build does not
inherit the published app's signing identity or attestation configuration.

## iOS

Use macOS and Xcode with SDK support for the project, plus JDK 21 for the shared protocol build.
The deployment target is iOS/iPadOS 18.6. The shared framework currently targets physical iOS
devices and Apple Silicon simulators; Intel simulator builds are not configured.

```bash
./gradlew :protocol:assembleNotiSyncProtocolReleaseXCFramework
open ios/NotiSync.xcodeproj
```

The Xcode project references `protocol/build/XCFrameworks/release/NotiSyncProtocol.xcframework`.
In Xcode, select the **NotiSync** scheme and configure your development team and provisioning for
the app, **NotificationService**, and **NotificationContent** targets.

For your own identifiers, update the bundle IDs and the matching App Group, shared Keychain,
associated-domain, and push entitlements consistently across the app and extensions. Add your own
`GoogleService-Info.plist` to the app target for Firebase; it is ignored by Git. APNs credentials and
the broker topic must match your app. See [APNs setup](self-hosting.md#ios-push-with-apns).

### Xcode Cloud

The executable [`ios/ci_scripts/ci_post_clone.sh`](../ios/ci_scripts/ci_post_clone.sh) hook lives
beside `NotiSync.xcodeproj`, following Apple's [custom build script conventions](https://developer.apple.com/documentation/xcode/writing-custom-build-scripts).
Xcode Cloud runs it automatically after cloning, before building the Xcode project; no target
membership or Xcode Run Script build phase is needed.

The hook installs JDK 21 through the runner's Homebrew, uses `CI_PRIMARY_REPOSITORY_PATH` to find
the checkout, and builds `:protocol:assembleNotiSyncProtocolReleaseXCFramework`. It maps the
runner's `HTTP_PROXY` and `HTTPS_PROXY` URLs to Java proxy settings for dependency downloads.
Gradle's `--configure-on-demand` flag limits configuration to the shared protocol module, so this
invocation does not require the Android SDK. A failed command or missing framework fails the hook.

Keep `protocol/build/` ignored: each cloud build generates the release XCFramework from the same
source revision as the Swift app, including the device and Apple Silicon simulator slices.
Firebase configuration and signing/provisioning still need to be supplied for your cloud workflow.

## Desktop

Use the [desktop installation guide](desktop.md#prerequisites) for platform dependencies and
current-user installation. To stage a distribution without installing it into your user directories:

```bash
./gradlew :notisyncd:installDist
```

The output is under `notisyncd/build/install/notisyncd/`. Linux and macOS include the native screen
helper, which needs SDL/FFmpeg development libraries. Windows omits the POSIX-only Run and screen
tools. Ordinary developer distributions use local multimedia libraries; distributable screen
release artifacts use the separate [pinned-runtime packaging workflow](../nsscreen/src/native/runtime/README.md).

## Local broker

Run a local server with FCM disabled when you do not have push credentials:

```bash
NOTISYNC_FCM_ENABLED=false ./gradlew :server:run
```

PowerShell:

```powershell
$env:NOTISYNC_FCM_ENABLED = 'false'
.\gradlew.bat :server:run
```

Use `http://localhost:8080` from the host computer, `http://10.0.2.2:8080` from the Android emulator,
or the host's LAN address from a physical device. Use an HTTPS endpoint where platform transport
policy requires it. Do not put credentials or private test data into the public repository.
See [self-hosting](self-hosting.md) for the container build and complete push setup.

## Tests

Run checks for the components you change. For example:

```bash
# Shared protocol and JVM peer behavior
./gradlew :protocol:jvmTest :protocol-crypto:test :peer-core:test

# Broker and desktop tools
./gradlew :server:test :notisyncd:test :notisync-gpg:test :notisync-ssh-agent:test

# Android unit tests
./gradlew :app:testDebugUnitTest

# Android instrumentation tests, with a device or emulator attached
./gradlew :app:connectedDebugAndroidTest
```

On Linux/macOS, `:nsscreen:check` also compiles and tests the native helper. It needs the native
dependencies described above and is separate from validating a packaged screen release.

## Contributing

Use [GitHub Issues](https://github.com/dingwen07/NotiSync/issues) for reproducible bugs and feature
requests. Include the affected platform, app/tool version, expected behavior, and minimal steps
to reproduce. Redact personal notification content and credentials from logs.

Keep pull requests focused, describe the user-visible change, and report the checks you ran.
Update the relevant guide when changing commands, configuration, or platform behavior. Protocol
changes need particular care across the JVM and iOS consumers; start with the [architecture](architecture.md)
and shared wire tests. Preserve applicable license and third-party notices.
