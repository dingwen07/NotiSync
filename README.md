# NotiSync

Secure, end-to-end-encrypted notification mirroring across your trusted devices. A device can
**capture** its notifications and forward them to your other devices, and **display** notifications
mirrored from them. A lightweight broker server only relays opaque ciphertext and coordinates push
delivery — it can never read your notifications.

> Status: **working vertical slice** (v1). The full pipeline — identity → QR pairing → capture →
> E2E-encrypt → broker → deliver → display → dismissal sync — is implemented, builds, and is tested.
> See [Status & scope](#status--scope) for what is complete vs. deferred.

## Architecture

A single Gradle build with shared protocol/crypto code consumed by **both** the Android client and
the Kotlin/Ktor broker, so the wire format and signature verification can never drift between them.

```
:protocol         Pure-Kotlin/JVM. @Serializable CBOR DTOs (cards, route claims, envelope,
                  captured notification, dismissal), the versioned cipher-suite tag, the
                  transport-neutral Transport interface, and the JSON control-plane DTOs.
:protocol-crypto  Pure-Kotlin/JVM. Tink-based envelope sealing/opening (random DEK → AES-256-GCM
                  body + HPKE per-recipient DEK), ECDSA-P256 signing/verification, client-id
                  derivation. Shared verbatim by client and server.
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
# Run locally. Play Integrity enforcement is on by default; for local-only protocol tests set
# NOTISYNC_PLAY_INTEGRITY_ENABLED=false — the single master switch turns off signed/JWT auth and
# attestation together, and the client tolerates it and works without a token.
./gradlew :server:run

# Or build a deployable fat jar and container:
./gradlew :server:buildFatJar
docker compose up --build           # serves on :8080, SQLite cache on a named volume
curl http://localhost:8080/healthz  # {"status":"ok","version":"0.1.0"}
```

Configuration (environment variables): `NOTISYNC_DB_PATH`, `NOTISYNC_FCM_ENABLED`,
`NOTISYNC_FCM_PROJECT_ID`, `NOTISYNC_INLINE_BUDGET`, `NOTISYNC_RELAY_TTL_MS`,
`NOTISYNC_ASSET_TTL_MS`, `NOTISYNC_PLAY_INTEGRITY_ENABLED` (master security switch),
`NOTISYNC_PLAY_INTEGRITY_PACKAGE`, `NOTISYNC_REQUIRE_APP_LICENSING`,
`NOTISYNC_REQUIRE_APP_RECOGNITION`, `NOTISYNC_REQUIRE_DEVICE_RECOGNITION`,
`NOTISYNC_ALLOW_DEVICE_ACTIVITY` (allow-list; default rejects only `LEVEL_4`),
`NOTISYNC_REQUIRE_PLAY_PROTECT`, `NOTISYNC_DEBUG_KEY`, `NOTISYNC_JWT_PRIVATE_KEY_PATH`,
`NOTISYNC_JWT_TTL_MS` (default 7 days), and `NOTISYNC_POW_DIFFICULTY` (leading-hex-zero
proof-of-work on `/v1/integrity/verify`, default 4). The broker exposes its JWT verification key at
`/.well-known/jwks.json`, and an unauthenticated `/v1/status` for clients to discover whether
attestation is required and whether their token is still valid. The security-sensitive switches
(`NOTISYNC_PLAY_INTEGRITY_ENABLED`, `NOTISYNC_DEBUG_KEY`) are read from the environment / system
properties only — never from `local.properties`.

To enable Play Integrity token decoding and real FCM, give the server Application Default Credentials:
`gcloud auth application-default login` (local), or mount a service-account key and set
`GOOGLE_APPLICATION_CREDENTIALS` + `NOTISYNC_FCM_ENABLED=true`.

### Android app

```bash
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/
```

`app/google-services.json` (for the `extrawdw-notifly` Firebase project) is already in place.
In **Settings → Broker URL**, point the app at your broker. From the Android emulator, use
`ws://10.0.2.2:8080` (host loopback); on a device, use your machine's LAN address.

## Pairing

Pairing is **mutual QR exchange of self-signed client cards**. The QR carries only public key
material, so the optical channel is the trust anchor (no relay can substitute keys) and the
`clientId` fingerprint is the human-verifiable safety number. The QR encodes a verified Android App
Link (`https://notisync.apps.extrawdw.net/pair?...`), so Camera/QR scanner apps can open NotiSync's
pairing screen directly and show a trust prompt with the device details. On each device:
**Devices → Pair a device**, show your code, then trust the other device's signed card. Both add each
other as trusted peers.

## Status & scope

**Complete & tested**
- Shared protocol + crypto with round-trip / determinism / tamper tests.
- Broker: signed card & route verification, store-and-forward, authenticated WebSocket relay,
  FCM adapter, recoverable SQLite cache, health endpoints — verified end-to-end (a recipient
  decrypts an E2E payload routed through the real server in an integration test).
- Android app builds to an APK: identity, HPKE keys, capture, mirror render + bidirectional
  dismissal sync, FCM service, WebSocket transport, QR pairing, Material 3 Expressive UI.

**v1 scope decisions** (see [docs/ADR.md](docs/ADR.md))
- Text + MessagingStyle notifications. Binary attachments (icons/big pictures) are modeled in the
  protocol but not yet transferred (text-first slice).
- Display + dismissal only — no remote reply/actions.
- Mutual in-person QR pairing — the SPAKE-style relay handshake for remote pairing is deferred.
- Manual DI + DataStore instead of Hilt/Room/KSP, to avoid the unsettled AGP-9 + KSP2 + Hilt
  annotation-processing toolchain on this Kotlin 2.2.10 build. (`coil` is likewise omitted in v1.)

**Next steps**
- Encrypted blob transfer for rich media; on-device validation across two emulators/devices.
- Foreground-service lifecycle hardening and WorkManager batching/retention jobs.
- Group membership cards + revocation distribution; route-repair sync flows.
