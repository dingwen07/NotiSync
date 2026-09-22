# Self-hosting the broker

[Documentation](README.md) · [Security model](security.md) · [Development](development.md)

The broker relays encrypted envelopes, stores undelivered ciphertext temporarily, and coordinates
WebSocket and platform push delivery. Devices own their identities, trust, and content keys.
Hosting a broker does not give its operator access to notification plaintext.

The current broker serves the **NS2** protocol under `/v2`. Legacy NS1 clients require their
corresponding legacy broker; this server does not provide a `/v1` compatibility path.

## Push delivery limitation

> [!IMPORTANT]
> FCM and APNs push delivery from a self-hosted broker will not work with the production apps
> distributed through Google Play or the App Store. Changing the broker URL does not change the
> app's push configuration.

Those builds use NotiSync's production Firebase project and Apple app identity; their push
credentials are not provided to self-hosted operators.

To use push delivery with your own broker, [build your own apps](development.md) with your own
Firebase configuration on Android and Apple app identity, signing, and push entitlements on iOS.
Then configure the broker with the matching FCM/APNs credentials described below.

WebSocket delivery can work while a client is connected, but it does not replace background push
delivery when the app is suspended or disconnected.

## Run with Docker Compose

Prepare the [build environment](development.md#build-setup), then run from the repository root:

```bash
./gradlew :server:buildFatJar
docker compose up --build -d
curl http://localhost:8080/healthz
```

In PowerShell, use `.\gradlew.bat` and `curl.exe` for the equivalent commands.
The health endpoint returns JSON with `status` and `version` fields. The container uses a
distroless JRE 21 image, exposes port 8080, and stores data in the `notisync-data` named volume.
The image copies the prebuilt `server/build/libs/server-all.jar`; Docker does not build the JAR.

The checked-in [Compose file](../docker-compose.yml) disables FCM and APNs. This is useful for
WebSocket-based local testing. Configure push providers below for background delivery to your own
app builds. For deployment, put the broker behind an HTTPS reverse proxy that supports WebSocket
upgrades and limit direct access to port 8080. The files under [deploy/](../deploy/) are reference
configurations; adapt their hostnames, certificate paths, and service paths to your environment.

For a Gradle development server without FCM credentials:

```bash
NOTISYNC_FCM_ENABLED=false ./gradlew :server:run
```

PowerShell:

```powershell
$env:NOTISYNC_FCM_ENABLED = 'false'
.\gradlew.bat :server:run
```

Signed requests and JWT authentication are enabled by default.

> [!CAUTION]
> `NOTISYNC_SECURITY_ENABLED=false` disables signed-request checks, JWT authentication, and
> attestation together. Use it only for isolated local protocol tests; keep security enabled
> on a deployed broker.

## Configuration

Set environment variables on the server process or in the Compose service's `environment` block.
A host shell variable is not automatically forwarded into the container. JVM system properties
take precedence over environment variables. Most settings also fall back to `local.properties`,
but the three security switches below are read only from the environment or system properties.

Defaults below come from [ServerConfig](../server/src/main/kotlin/net/extrawdw/notisync/server/Config.kt).
The Compose file overrides some of them.

### Storage and delivery

| Variable | Default | Purpose |
| --- | --- | --- |
| `NOTISYNC_DB_PATH` | `data/notisync.db` | SQLite cache; Compose uses `/data/notisync.db` |
| `NOTISYNC_INLINE_BUDGET` | `4096` | Operator ceiling for inline push ciphertext; transport limits still apply |
| `NOTISYNC_RELAY_TTL_MS` | `172800000` (48 hours) | Undelivered encrypted relay retention |
| `NOTISYNC_ASSET_TTL_MS` | `604800000` (7 days) | Private encrypted asset retention |
| `NOTISYNC_MAX_ASSET_BYTES` | `1048576` (1 MiB) | Maximum private asset upload size |

### Authentication and integrity

| Variable | Default | Purpose |
| --- | --- | --- |
| `NOTISYNC_SECURITY_ENABLED` | `true` | Enforce signed requests and JWT authentication; environment/system property only |
| `NOTISYNC_INTEGRITY_REQUIRED` | `false` | Require passing client attestation before issuing a bearer; environment/system property only |
| `NOTISYNC_APPCHECK_ENABLED` | `false` | Accept Firebase App Check attestation; environment/system property only |
| `NOTISYNC_APPCHECK_PROJECT_NUMBER` | Empty | Expected Firebase project number |
| `NOTISYNC_APPCHECK_APP_IDS` | Empty | Comma-separated allowlist of Firebase app IDs |
| `NOTISYNC_JWT_PRIVATE_KEY_PATH` | `jwt-es256-private.pem` beside the database | Broker JWT signing key |
| `NOTISYNC_JWT_ISSUER` | `notisync-broker` | JWT issuer |
| `NOTISYNC_JWT_TTL_MS` | `604800000` (7 days) | Bearer lifetime |
| `NOTISYNC_POW_DIFFICULTY` | `4` | Leading hexadecimal zeros required by `/v2/integrity/verify` proof of work; `0` disables it |
| `NOTISYNC_SIGNED_REQUEST_MAX_SKEW_MS` | `300000` (5 minutes) | Accepted signed-request clock skew |

The broker verifies Firebase App Check tokens locally against App Check's JWKS. This does not
require Google API credentials. Enable integrity enforcement only after the intended clients can
attest successfully. With integrity enforcement off, validly signed clients can still obtain a bearer.

### Android push with FCM

Set `NOTISYNC_FCM_ENABLED=true` and `NOTISYNC_FCM_PROJECT_ID` to the Firebase project used by your
Android build. The process default is FCM **enabled** with project `extrawdw-notifly`; a self-hosted
build should use its own matching project and credentials.

Provide Application Default Credentials. For local development, use
`gcloud auth application-default login`. For a container using a service-account file, mount it
read-only and set `GOOGLE_APPLICATION_CREDENTIALS` to its path **inside the container**. Keep
credentials out of the repository and container image. The service account must be able to send
messages for the Firebase project that issued the app's push tokens.

### iOS push with APNs

Mount your Apple Auth Key `.p8` file read-only and configure:

| Variable | Value |
| --- | --- |
| `NOTISYNC_APNS_ENABLED` | `true` (default: `false`) |
| `NOTISYNC_APNS_TEAM_ID` | Your Apple developer team ID |
| `NOTISYNC_APNS_KEY_ID` | APNs authentication key ID |
| `NOTISYNC_APNS_PRIVATE_KEY_PATH` | Mounted `.p8` path inside the server/container |
| `NOTISYNC_APNS_TOPIC` | Your iOS app bundle identifier; default: `net.extrawdw.apps.NotiSync` |
| `NOTISYNC_APNS_KEY_ID_SANDBOX` | Optional sandbox key ID |
| `NOTISYNC_APNS_PRIVATE_KEY_PATH_SANDBOX` | Optional sandbox `.p8` path |

The sandbox fields fall back to the primary key settings when empty. Ensure the selected key is
authorized for the target topic and APNs environment. A self-hosted operator needs APNs credentials
authorized for the installed iOS app; changing only the broker URL does not grant access to the
published app's push credentials.

## Connect clients and check status

Set the same broker base URL on each device using the [custom broker setup](getting-started.md#use-a-custom-broker).
The server exposes:

| Endpoint | Purpose |
| --- | --- |
| `/healthz` | Process health and broker version |
| `/v2/status` | Public security/integrity requirements and supplied token status |
| `/.well-known/jwks.json` | Public JWT verification key |

The SQLite store is a recoverable cache: clients can reannounce state through normal use after
cache loss. Undelivered ciphertext that existed only in the cache is still lost. Persist the data
volume and protect the broker JWT key and push credentials as server secrets.
