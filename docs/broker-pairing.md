# Secure Exchange

Secure Exchange uses the configured broker for a single-use rendezvous. Start it with
`notisync devices pair` on desktop or open **Devices → Device Pairing** in the app. The host
shows a Secure Exchange QR containing a fresh secret and its identity fingerprint, without the full CARD. The
joining device scans it and automatically exchanges authenticated, encrypted CARD deliveries.
Both devices then use their existing trust approval paths. No manual code entry or comparison is
required. CARD QR pairing is available under `notisync devices pair show [--payload]`.

`host` and `client` are protocol roles, independent of operating system. The host displays the QR;
the client scans it. Android and iOS implement both roles using the same wire format.

## Hosting in the app

Opening **Device Pairing** starts a host session automatically. **Secure Exchange** names the
broker-assisted method within that screen and its approval sheet. The QR rotates 30 seconds before
the three-minute session expires; the previous session stays alive until its original deadline
so a scan already in progress can finish. At most two sessions are active, including connections
being closed. Each new session generates a fresh secret and signed CARD. Failed sessions are
replaced with bounded retry backoff, and unusable QR codes are removed from display.

The first authenticated CARD closes all remaining sessions before opening the existing trust
review. Leaving the screen or starting a scan cancels the host sessions; canceling the scanner
starts a fresh host session. On Android and iOS, **Share** sends the full CARD link
(`?payload=`), independently of the rotating Secure Exchange QR. The **QR Code** icon immediately left
of **Share** opens the CARD QR. Both remain available without the broker.

Android and iOS implement both roles. iOS also cancels exchanges when the app enters the
background and starts a fresh host session when the pairing screen becomes active again.
The desktop CLI currently hosts a single session without automatic rotation.

## Rendezvous link

```
https://notisync.apps.extrawdw.net/pair?pair=1.<session-id>[&b=<percent-encoded-broker-url>]#i=<host-id>&k=<secret>
```

- `pair`: version `1` followed by a period and the 16-byte broker-generated session ID encoded as
  unpadded Base64URL (22 characters).
- `i`: the full host ClientId / Verification Number: 32 lowercase Base32 characters encoding the
  first 20 bytes of SHA-256 of its identity public key in X.509 SubjectPublicKeyInfo form. This is
  an identity-key fingerprint, not the shorter display fingerprint or the public key itself.
- `k`: 32 fresh bytes generated locally by the host using the platform's secure random generator, encoded as canonical
  unpadded Base64URL (43 ASCII characters). This is a secret PSK, not a public nonce or session ID.
- `b`: the host's normalized broker URL, omitted only when it matches `BrokerPairing.DEFAULT_BROKER`,
  which also supplies the existing client defaults. The implicit v1 address must stay stable;
  a future default migration must retain v1 decoding or explicitly include `b`.

The default link is 150 ASCII characters. The CARD and PAKE messages are absent. Both `i` and `k`
are required in the fragment; only `pair` and optional `b` are allowed in the query. Duplicates,
unknown fields, unsupported versions, malformed secrets, and earlier draft broker links are
rejected. There is no fallback to an unauthenticated broker exchange. Custom-scheme
`notisync://pair?pair=1.<session-id>[&b=...]#i=<host-id>&k=<secret>` launchers use the same rules.
`?payload=` CARD links remain a separate optical pairing flow.

The joining device uses the advertised broker only for this exchange and preserves its saved
setting. Custom brokers require HTTPS/WSS, except HTTP/WS on loopback for development. Base paths
and ports are retained; credentials, query strings, and fragments within a broker URL are rejected.

The secret must never be sent to the broker, used as a session identifier, logged, or stored as a
public verifier. A URL fragment is excluded from HTTP requests; the session marker can appear in
Pages request logs, but the PSK does not. The Pages handoff retains the secret only in the native
app URL fragment and does not ask for codes. This requires trusted Pages JavaScript: it can read
fragments, so an attacker controlling that page could steal the PSK. Direct in-app scanning avoids
the web handoff. The complete QR/link is sensitive and must be shown only to the intended peer.

IVs and KDF parameters are public and could be encoded in the QR, but v1 does not need separate
fields for them. The version selects J-PAKE, HKDF-SHA256, and AES-256-GCM. Each CARD carries its
own fresh nonce. The HKDF salt is the completed PAKE transcript hash, which does not exist at
QR-display time. Public IVs, nonces, salts and fingerprints cannot replace the secret PSK.

## Broker channel

Connect a WebSocket to `<broker-base>/v2/pairing`. Messages are final binary CBOR frames using the
shared `ProtocolCodec` and DTOs in `protocol/BrokerPairing.kt`.

1. Host sends `PairingRelayRequest(version=1, sessionId=null)`. Broker allocates a room and
   returns `PairingRelayReady(version=1, sessionId)`. Host then displays the link and QR.
2. Client sends the same request with the scanned session ID; the broker claims the only joining
   slot and returns the matching ready response. A slot is never reopened for retries.
3. Each side sends and receives exactly four messages: J-PAKE rounds 1, 2, 3, and an encrypted CARD.
   The broker forwards bounded opaque bytes without interpreting the PAKE or CARD.
4. Completion, socket closure, failure, or timeout removes the room. There is no disk persistence,
   reconnect, resume, or HTTP fallback. Queued final CARD bytes are drained before closing.

Rooms have at most a three-minute lifetime from the host connection. Clients allow at most 30 seconds after
receiving round 1 to finish the cryptographic exchange. Frames are capped at 32 KiB and the CARD
payload string at 16 KiB. Relay queues have capacity one per direction; the service permits 128
active rooms globally and eight per connection address. A reverse proxy sharing one address also
shares that cap; forwarding headers are not trusted. Initial requests have a ten-second deadline.
The route does not require normal broker identity authentication: pairing bootstraps identity, and
the PAKE authenticates both endpoints. Existing authenticated delivery routes are unchanged.

## Secret-authenticated key exchange

The JVM implementation uses Bouncy Castle's lightweight `JPAKEParticipant`, following
[RFC 8236](https://www.rfc-editor.org/rfc/rfc8236.html), with the fixed NIST-3072 prime-order group,
SHA-256, fresh secure randomness, and mandatory mutual round-3 key confirmation. It does not
register or look up a Bouncy Castle JCA provider. iOS uses the existing OpenSSL dependency for
the finite-field J-PAKE operations and CryptoKit for confirmation, HKDF, and AES-GCM. Its CBOR
frames use the shared Kotlin protocol codec. Both implementations use the same integer encodings,
proof hashing, transcript, and directional keys.

Both participants use the exact 43 ASCII characters of `k` as the J-PAKE password. The PSK is
high entropy, so authentication does not rely on a human code or on honest broker rate limits.
Participant IDs are exactly:

```
notisync-pair-v1|<normalized-http(s)-broker-base>|<session-id>|<host-id>|host
notisync-pair-v1|<normalized-http(s)-broker-base>|<session-id>|<host-id>|client
```

These fixed expected identities bind the version, broker, session, host identity pin, and roles
into J-PAKE's proofs and key confirmation. Every frame is `PairingPakeFrame(round, participantId, values)`. Values are
canonical signed big-endian `BigInteger.toByteArray()` byte arrays, at most 385 bytes each. The
`values` field uses CBOR arrays of signed byte integers. DTO field labels are sequential integers
starting at zero in the order listed; the version is always encoded. CARD nonce and ciphertext
fields use CBOR byte strings:

| Round | Values in order |
| --- | --- |
| 1 | `gx1`, `gx2`, `knowledgeProofForX1[0]`, `[1]`, `knowledgeProofForX2[0]`, `[1]` |
| 2 | `a`, `knowledgeProofForX2s[0]`, `[1]` |
| 3 | `macTag` (Bouncy Castle represents this as a signed integer) |

Both endpoints create/send their round before validating the peer's corresponding round. A bad
proof, role, session, order, or confirmation permanently burns that client's attempt. No CARD can
be encrypted or decrypted before local verification of the peer's round-3 confirmation succeeds.

After confirmation, hash the exact six encoded PAKE frames using SHA-256 in this order:
host round 1, client round 1, host round 2, client round 2, host round 3, client round 3.
Prefix each frame with its length as a four-byte big-endian integer. For each sender, derive a
32-byte key using HKDF-SHA256 with:

- IKM: `calculateKeyingMaterial().toByteArray()`;
- salt: the transcript hash;
- info: UTF-8 `<sender-participant-id>|CARD`.

Encrypt the existing Base64URL CARD-delivery payload string as UTF-8 with AES-256-GCM, a fresh
12-byte nonce, and a 128-bit authentication tag. AAD is the sender participant ID in UTF-8 followed
by the transcript hash. Send `PairingEncryptedCard(nonce, ciphertext)`, with the tag appended to
the ciphertext. Directional keys prevent reflection. Only one CARD may be sent/received per role;
fresh PAKE randomness and the transcript prevent ciphertext replay across sessions. Clear retained
key byte arrays on close. JVM-managed password strings/BigIntegers are not guaranteed zeroizable.

## Trust and threat model

Assume the broker is malicious and the user clicks Trust without inspecting CARD details. Each
client must independently authenticate the peer before offering trust. The host rejects a fake
client lacking the PSK, even if the real client has already rejected a fake host. The client rejects
a fake host lacking the PSK and also requires the received host CARD's identity to match `i`.
Both clients verify the CARD's identity-bound signature and reject self-pairing before returning
it to the UI. AES-GCM authenticates the exact CARD delivery under the mutually confirmed,
transcript-bound directional key, so the broker cannot substitute a different valid signed CARD.

The broker sees rendezvous identifiers, the public host identity pin in participant IDs, addresses,
timing, sizes, PAKE messages and CARD ciphertext. It never receives the PSK, a PSK verifier, or
plaintext CARD. Replaying a public signed CARD does not establish knowledge of the QR secret.
The broker can forward the genuine exchange, in which case the genuine devices authenticate each
other, or interrupt it. It cannot terminate two authenticated exchanges or impersonate either
side without the PSK or breaking the cryptography. This property does not depend on the relay's
single-join policy or on a user comparing names/fingerprints. Local protocol states are single-use,
and each new host attempt generates a new PSK even if a malicious broker repeats a session ID.

This assumes the intended host QR and endpoint software are genuine, and the QR secret has not
leaked. Anyone who obtains the complete QR/link can attempt to join. The broker can always deny
service; the protocol does not guarantee that both users approve or finish at the same time.

Receiving an authenticated CARD does not itself grant trust. The app opens the existing
approval sheet in either role; the desktop host prints the verified details and requires an own/other/cancel
choice. Cancellation, EOF, no interactive console, and authentication failure add no trust.
Signed peer names are stripped of terminal control and bidi formatting before terminal display.
CARD pairing and NFC retain their existing behavior and optical verification requirements.
