# NotiSync

Secure, end-to-end-encrypted notification mirroring across your trusted devices. A device can
**capture** its notifications and forward them to your other devices, and **display** notifications
mirrored from them. A lightweight broker server only relays opaque ciphertext and coordinates push
delivery — it can never read your notifications.

## Architecture

A single Gradle build with shared protocol/crypto code consumed by **Android**, **iOS**, and the
Kotlin/Ktor broker, so the wire format and signature verification can never drift between them.

```
:protocol         Kotlin Multiplatform (JVM + Apple). @Serializable CBOR DTOs (cards, route
                  claims, envelope, captured notification, dismissal), the versioned cipher-suite
                  tag, the transport-neutral Transport interface, JSON control-plane DTOs, and the
                  iOS XCFramework codec facade.
:protocol-crypto  Pure-Kotlin/JVM. Tink-based envelope sealing/opening (random DEK → AES-256-GCM
                  body + HPKE per-recipient DEK), ECDSA-P256 signing/verification, client-id
                  derivation. Shared verbatim by client and server.
:peer-core        Shared JVM peer engine: secure channel, signed trust store and convergence,
                  pairing, key rotation, broker HTTP/WebSocket client, and platform ports.
:notisync-local-api
                  Versioned JSON DTOs for the process-scoped local Unix-socket API.
:notisyncd        JVM 21 Linux/macOS desktop distribution containing the `notisyncd` peer daemon,
                  the `notisync` peer-management CLI, and the `nsrun` command wrapper.
:server           Ktor CIO broker. Verifies signed cards/routes (never decrypts), store-and-forward
                  relay, authenticated WebSocket transport, FCM HTTP v1 adapter, Exposed/SQLite
                  recoverable cache. Containerized (distroless JRE 21).
:app              Jetpack Compose + Material 3 Expressive client. Hardware-backed identity
                  (Keystore P-256, StrongBox→TEE), HPKE keys, NotificationListenerService capture,
                  mirror rendering + dismissal, Ktor WebSocket transport, FCM, QR pairing.
```

```
 Android A (provider+consumer) ──► NotiSync broker ──► FCM / WebSocket ──► Android B
        ▲                          (broker is blind to content)                │
        └──── signed cards, route claims, encrypted envelopes, dismissals ──────┘
```

## Security model

- **End-to-end encryption.** Each notification body is sealed once with a random data key
  (AES-256-GCM); that key is HPKE-sealed (`DHKEM_X25519_HKDF_SHA256` via Google Tink) once per
  recipient. The broker fans out by recipient id and sees only ciphertext + routing metadata.
- **Hardware-backed identity.** A non-exportable EC P-256 key in the Android Keystore (StrongBox
  when available, else TEE) signs cards, route claims, and envelope authenticators. The `clientId`
  is `base32(SHA-256(public key))` — a reproducible fingerprint that doubles as the safety number.
- **Client authority.** Clients own identity, group membership, keys, and recoverable state. The
  broker is a disposable cache: if it loses all data, clients rebuild it through normal use.
- **Algorithm agility.** Every signed/encrypted structure carries the cipher-suite id (`NS1`), so
  algorithms can be upgraded (`NS2`, …) without breaking old data.

## Build & run

Prerequisites: JDK 21, Android SDK (platform 37), Gradle wrapper (bundled).

### Broker server

```bash
# Run locally. Signed/JWT enforcement is on by default; for local-only protocol tests set
# NOTISYNC_SECURITY_ENABLED=false — the master switch turns off signed/JWT auth (and attestation)
# together, and the client tolerates it and works without a token.
./gradlew :server:run

# Or build a deployable fat jar and container:
./gradlew :server:buildFatJar
docker compose up --build           # serves on :8080, SQLite cache on a named volume
curl http://localhost:8080/healthz  # {"status":"ok","version":"0.1.0"}
```

Configuration (environment variables): `NOTISYNC_DB_PATH`, `NOTISYNC_FCM_ENABLED`,
`NOTISYNC_FCM_PROJECT_ID`, `NOTISYNC_APNS_ENABLED`, `NOTISYNC_APNS_TEAM_ID`,
`NOTISYNC_APNS_KEY_ID`, `NOTISYNC_APNS_PRIVATE_KEY_PATH`, `NOTISYNC_APNS_TOPIC`,
`NOTISYNC_INLINE_BUDGET`, `NOTISYNC_RELAY_TTL_MS`, `NOTISYNC_ASSET_TTL_MS`,
`NOTISYNC_SECURITY_ENABLED` (master switch: enforce signed + JWT auth; default on),
`NOTISYNC_INTEGRITY_REQUIRED` (require a passing client-integrity attestation — App Check today — to
mint a bearer; default off, so a validly-signed client is still issued a bearer while a method is
rolled out), `NOTISYNC_APPCHECK_ENABLED`, `NOTISYNC_APPCHECK_PROJECT_NUMBER`,
`NOTISYNC_APPCHECK_APP_IDS`, `NOTISYNC_JWT_PRIVATE_KEY_PATH`, `NOTISYNC_JWT_TTL_MS` (default 7 days),
and `NOTISYNC_POW_DIFFICULTY` (leading-hex-zero proof-of-work on `/v2/integrity/verify`, default 4).
The broker exposes its JWT verification key at `/.well-known/jwks.json`, and an unauthenticated
`/v2/status` for clients to discover whether the broker is secured / requires integrity and whether
their token is still valid. The security-sensitive switches (`NOTISYNC_SECURITY_ENABLED`,
`NOTISYNC_INTEGRITY_REQUIRED`, `NOTISYNC_APPCHECK_ENABLED`) are read from the environment / system
properties only — never from `local.properties`.

Client integrity is verified via Firebase App Check (the broker validates the App Check token locally
against the App Check JWKS — no Google API credentials needed for it). For real FCM, give the server
Application Default Credentials: `gcloud auth application-default login` (local), or mount a
service-account key and set `GOOGLE_APPLICATION_CREDENTIALS` + `NOTISYNC_FCM_ENABLED=true`.

To enable APNs delivery for the iOS client, mount the Apple Auth Key `.p8` file and set
`NOTISYNC_APNS_ENABLED=true`, `NOTISYNC_APNS_TEAM_ID`, `NOTISYNC_APNS_KEY_ID`,
`NOTISYNC_APNS_PRIVATE_KEY_PATH`, and `NOTISYNC_APNS_TOPIC` to the iOS app bundle identifier.

### Android app

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
```

`app/google-services.json` (for the `extrawdw-notifly` Firebase project) is already in place.
In **Settings → Broker URL**, point the app at your broker. From the Android emulator, use
`ws://10.0.2.2:8080` (host loopback); on a device, use your machine's LAN address.

### Desktop daemon and NotiSync Run

NotiSync Desktop supports Linux and macOS and requires JDK 21. On macOS, install Xcode Command Line
Tools as well. Install the `notisyncd`, `notisync`, and `nsrun` commands for the current user:

```bash
git clone https://github.com/dingwen07/NotiSync.git
cd NotiSync
./scripts/install-desktop.sh
export PATH="$HOME/.local/bin:$PATH"
```

The default installation is under `~/.local/share/notisync`. Add the `PATH` export to the shell's
startup file. Operational `notisync` commands and `nsrun` start the daemon automatically when
needed:

```bash
notisync config set device-name "Workstation"
notisync status
```

Use `notisync daemon start|stop|restart` for explicit lifecycle control. `notisync daemon` and
`notisync daemon status` only report status and do not autostart; `notisync status` is an alias with
the same behavior. The daemon executable itself provides the lower-level
`notisyncd start|stop|restart|status` commands. Its `status` command writes JSON to stdout when the
daemon is running and a concise error to stderr when it is not.

The desktop defaults to `wss://notisync-api.extrawdw.net`. For a custom broker, configure the same
WebSocket URL in the Android app and on the desktop:

```bash
notisyncd config set broker-url "wss://notisync.example.com"
```

Pairing is mutual. Run `notisync devices pair show`, then scan the terminal QR code from **Devices →
Pair a device** on Android. Copy the Android pairing link or payload back to the desktop and accept
it as an own device:

```bash
notisync devices pair inspect 'ANDROID_PAIRING_LINK_OR_PAYLOAD'
notisync devices pair accept --own 'ANDROID_PAIRING_LINK_OR_PAYLOAD'
notisync devices list
```

Device trust actions take the action first and the device ID second. To approve every currently
pending device, use the explicit `--all` form:

```bash
notisync devices action approve DEVICE_ID
notisync devices action approve --all
```

Run traffic is restricted to trusted own devices. Prefix a command with `nsrun --` to send encrypted
progress, input-wait, and completion updates to Android while the command runs normally:

```bash
nsrun -- git commit
nsrun --update-interval 15s -- ./long-build
```

The Android **Run** tab and ongoing notification show the terminal tail and offer prompt input,
Interrupt, Terminate, Kill, and signal controls. Dismissing a notification does not signal the
process. `nsrun` preserves interactive terminal behavior, starts the daemon on demand, and still
runs the child if reporting is unavailable. Private Run logs are stored under `~/.notisync/runs/`.

```bash
nsrun config get
nsrun config set updateInterval 30s
nsrun config set stuckAfter 5m       # or: off
nsrun config set pty auto            # auto, always, or never
notisync daemon stop
```

Configuration and private daemon data live in `~/.notisync/`. Daemon logs use the platform's user log
location: `$XDG_STATE_HOME/notisync/log/notisyncd.log` on Linux, falling back to
`~/.local/state/notisync/log/notisyncd.log`. Log lines include an ISO-8601
timestamp, severity, and thread name; the default level is `WARN` and can be changed with
`notisyncd config set log-level info`. Rerun `./scripts/install-desktop.sh` to update the installed
commands; if the daemon is running, the installer stops it before replacing the installation and starts
the updated daemon afterward. The current desktop key provider stores unencrypted key material in the
private `~/.notisync/private-keys-v1/` directory.

## Pairing

Pairing is **mutual QR exchange of self-signed client cards**. The QR carries only public key
material, so the optical channel is the trust anchor (no relay can substitute keys) and the
`clientId` fingerprint is the human-verifiable safety number. The QR encodes a verified Android App
Link (`https://notisync.apps.extrawdw.net/pair?...`), so Camera/QR scanner apps can open NotiSync's
pairing screen directly and show a trust prompt with the device details. On each device:
**Devices → Pair a device**, show your code, then trust the other device's signed card. Both add each
other as trusted peers.
