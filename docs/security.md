# Security model

[Documentation](README.md) · [Architecture](architecture.md) · [Self-hosting](self-hosting.md)

NotiSync encrypts notification content between trusted devices. This document describes the
implementation's trust boundaries; the [privacy policy](https://notisync.apps.extrawdw.net/privacy/)
covers the published apps' data handling.

## Encryption and signatures

Each notification body is sealed once with a random data-encryption key using **AES-256-GCM**.
That data key is HPKE-sealed separately for each recipient using **X25519, HKDF-SHA256, and
AES-256-GCM**. ECDSA P-256 signatures authenticate device information and envelopes.

The current suite, **NS2**, delegates operational signing and HPKE keys through identity-signed
key-epoch certificates. Envelope signatures and recipient key contexts bind the relevant epochs.
The stable identity remains the trust anchor while operational keys can rotate. The wire suite
is explicit in signed/encrypted structures; the current broker rejects legacy NS1 traffic.

See [CipherSuite](../protocol/src/main/kotlin/net/extrawdw/notisync/protocol/CipherSuite.kt),
[EnvelopeCrypto](../protocol-crypto/src/main/kotlin/net/extrawdw/notisync/protocol/crypto/EnvelopeCrypto.kt),
and [iOS Crypto](../ios/NotiSyncKit/Crypto.swift) for the implementation.

## Device trust and pairing

Pairing exchanges signed public identity material through a QR code or pairing link. Verify
the device identity and safety number before trusting it; a self-signature proves possession of
the corresponding key, while your verification establishes whose device it is.

The client ID is derived as:

```text
base32(first 20 bytes of SHA-256(identity public key in X.509 SubjectPublicKeyInfo form))
```

It is a stable fingerprint of the identity key. Clients own their trusted-device membership and
verify signed trust updates. A broker does not decide which devices you trust.

Secure Exchange uses a fresh 256-bit QR secret in J-PAKE, with mutual cryptographic key
confirmation before signed CARDs are exchanged under transcript-bound directional AES-GCM keys.
The QR also pins the host's full identity fingerprint. The broker cannot authenticate as either
participant without the secret; both clients reject unauthenticated CARDs before trust approval,
even if the user would click Trust without inspecting the details. Public nonces or fingerprints
alone do not authenticate the joining device. No manual six-digit confirmation is needed.

Keep the complete QR/link private. Its secret is in the fragment, absent from HTTP requests;
a browser handoff still requires trusted Pages JavaScript. Both devices require explicit approval
to grant trust. See [Secure Exchange](broker-pairing.md) for the wire format and assumptions.

## What the broker sees

The broker handles ciphertext, public verification material, device IDs, push routes, and delivery
metadata. It can observe connections, timing, and message sizes. It cannot decrypt notification
titles, text, sender details, source-app details, or private media carried inside encrypted payloads.
Platform push services carry ciphertext or wake-up pointers.

End-to-end encryption does not hide all metadata or guarantee availability: a broker can withhold
or lose queued messages. Its database is recoverable routing/cache state, not the authority for
device identity or trust. Decrypted content is available on trusted endpoints, so device access
and notification-preview settings remain relevant.

## Key storage

| Platform | Storage behavior |
| --- | --- |
| Android | Identity signing uses Android Keystore, preferring StrongBox with a Keystore fallback. Operational signing also uses Keystore; actual hardware backing depends on the device. |
| iOS | Signing keys use Secure Enclave where available, with a Keychain fallback. Other key material uses platform storage. |
| Desktop | The current file key provider stores unencrypted private key material under `private-keys-v1/` in the private daemon data directory. Access is protected by filesystem permissions, not an encrypted key vault. |

Hardware-backed signing does not mean that every encryption or feature key has the same storage
policy. Seal's OpenPGP private keys are managed by OpenKeychain on Android; SSH key storage and
approval behavior depend on the selected provider. See [Seal](seal.md) and
[SSH Agent setup](ssh-agent.md).

## Broker authentication

Signed request and JWT enforcement are on by default. Requiring a passing client-integrity
attestation is a separate setting and defaults off. Firebase App Check attests clients when
configured; it does not replace device pairing or end-to-end encryption.

Use HTTPS for deployed brokers. Keep `NOTISYNC_SECURITY_ENABLED` enabled outside isolated local
protocol tests. See [broker configuration](self-hosting.md#authentication-and-integrity).
