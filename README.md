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

Build the Linux/macOS distribution and put its three launchers on `PATH`:

```bash
./gradlew :notisyncd:installDist
export PATH="$PWD/notisyncd/build/install/notisyncd/bin:$PATH"

notisyncd start                 # detached; plain `notisyncd` stays in the foreground
notisync status
notisync config set device-name "Workstation"
notisync pair show              # pairing link plus a terminal QR code
notisync pair accept LINK       # add an own device; use --other for another user
notisync devices list

nsrun -- git commit             # preserves interactive terminal/PTY behavior
nsrun --update-interval 15s -- ./long-build
```

On-demand startup is persistent: when `notisync` or `nsrun` launches `notisyncd`, closing that
client does not stop the daemon or its WebSocket receive/reconnect loop. Use `notisyncd stop`
explicitly. Desktop process-title tools show the long-lived peer as `notisyncd` and the wrapper as
`nsrun`. On macOS the distribution uses universal native launchers that host the configured Java
21 runtime in-process, so Activity Monitor and `ps -o ucomm` no longer report them as `java`.

`nsrun` starts `notisyncd` on demand but always starts the child command even if reporting is
unavailable. It records a private, sequence-preserving NDJSON terminal log under
`~/.notisync/runs/`, detects progress and likely input waits, and sends completion status back to
trusted display-capable devices. Incoming notification dismissals do not signal the child; explicit
Interrupt and Terminate actions do.

Desktop configuration uses GnuPG-style, one-option-per-line files rather than JSON. The daemon reads
only `~/.notisync/notisyncd.conf`; `nsrun` reads only `~/.notisync/nsrun.conf`:

```text
# ~/.notisync/notisyncd.conf
broker-url "wss://notisync-api.extrawdw.net"
device-name "Workstation"
platform-name "Linux x86_64"
auto-apply-trusted-device-tables no
websocket-ping-seconds 30
```

```text
# ~/.notisync/nsrun.conf
update-interval-seconds 30
stuck-after-seconds 300
pty auto
log-retention-days 30
log-max-bytes 104857600
```

The daemon defaults `device-name` to the operating-system hostname. Use `notisync config get` and
`notisync config set device-name NAME` (or the broader `notisyncd config get/set` interface) for
daemon settings, and `nsrun config get/set` for Run settings.
`nsrun config` never contacts or starts the daemon. Optional OpenAI-compatible LLM settings, including
the literal API key, live only in `nsrun.conf` and are used only when `--llm` is passed. The initial
desktop key provider deliberately stores unencrypted key material under
`~/.notisync/private-keys-v1/`; key operations are behind a provider boundary for future OS-keychain
or GnuPG-backed implementations.

## Pairing

Pairing is **mutual QR exchange of self-signed client cards**. The QR carries only public key
material, so the optical channel is the trust anchor (no relay can substitute keys) and the
`clientId` fingerprint is the human-verifiable safety number. The QR encodes a verified Android App
Link (`https://notisync.apps.extrawdw.net/pair?...`), so Camera/QR scanner apps can open NotiSync's
pairing screen directly and show a trust prompt with the device details. On each device:
**Devices → Pair a device**, show your code, then trust the other device's signed card. Both add each
other as trusted peers.
