#ifndef NotiSyncOpenSSLBridge_h
#define NotiSyncOpenSSLBridge_h

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct NSScreenTLSListener NSScreenTLSListener;
typedef struct NSScreenTLSConnection NSScreenTLSConnection;
typedef struct NSSshManagedKey NSSshManagedKey;

typedef enum NSSshManagedKeyAlgorithm {
    NSSshManagedKeyAlgorithmEd25519 = 1,
    NSSshManagedKeyAlgorithmRSA = 2,
    NSSshManagedKeyAlgorithmECDSANistP256 = 3,
} NSSshManagedKeyAlgorithm;

typedef enum NSSshManagedSignatureAlgorithm {
    NSSshManagedSignatureAlgorithmEd25519 = 1,
    NSSshManagedSignatureAlgorithmRSASHA256 = 2,
    NSSshManagedSignatureAlgorithmRSASHA512 = 3,
    NSSshManagedSignatureAlgorithmECDSANistP256 = 4,
    NSSshManagedSignatureAlgorithmRSASHA1Legacy = 5,
} NSSshManagedSignatureAlgorithm;

// Blocking callbacks used to nest the existing screen PSK-TLS protocol inside an ordered Relay
// WebSocket byte stream. Return a positive byte count, 0 for EOF, -1 for failure, or -2 for timeout.
typedef ptrdiff_t (*NSScreenTLSStreamReadCallback)(
    void *context,
    uint8_t *buffer,
    size_t maximumLength,
    int timeoutMilliseconds
);
typedef ptrdiff_t (*NSScreenTLSStreamWriteCallback)(
    void *context,
    const uint8_t *buffer,
    size_t length,
    int timeoutMilliseconds
);
typedef void (*NSScreenTLSStreamCloseCallback)(void *context);

NSScreenTLSListener *NSScreenTLSListenerCreate(
    const uint8_t *videoIdentity,
    size_t videoIdentityLength,
    const uint8_t *videoKey,
    size_t videoKeyLength,
    const uint8_t *controlIdentity,
    size_t controlIdentityLength,
    const uint8_t *controlKey,
    size_t controlKeyLength,
    uint16_t *port,
    char *errorBuffer,
    size_t errorBufferLength
);

void NSScreenTLSListenerClose(NSScreenTLSListener *listener);
void NSScreenTLSListenerDestroy(NSScreenTLSListener *listener);

// Returns 1 for an authenticated connection, 0 on timeout, and -1 on error.
int NSScreenTLSListenerAccept(
    NSScreenTLSListener *listener,
    int timeoutMilliseconds,
    NSScreenTLSConnection **connection,
    char *errorBuffer,
    size_t errorBufferLength
);

// Performs a server-side TLS 1.3 external-PSK handshake over an ordered callback byte stream.
NSScreenTLSConnection *NSScreenTLSStreamServerAccept(
    const uint8_t *identity,
    size_t identityLength,
    const uint8_t *key,
    size_t keyLength,
    NSScreenTLSStreamReadCallback readCallback,
    NSScreenTLSStreamWriteCallback writeCallback,
    NSScreenTLSStreamCloseCallback closeCallback,
    void *context,
    int handshakeTimeoutMilliseconds,
    char *errorBuffer,
    size_t errorBufferLength
);

int NSScreenTLSConnectionReadExactly(
    NSScreenTLSConnection *connection,
    uint8_t *buffer,
    size_t length,
    int timeoutMilliseconds,
    char *errorBuffer,
    size_t errorBufferLength
);

int NSScreenTLSConnectionWriteAll(
    NSScreenTLSConnection *connection,
    const uint8_t *buffer,
    size_t length,
    int timeoutMilliseconds,
    char *errorBuffer,
    size_t errorBufferLength
);

void NSScreenTLSConnectionClose(NSScreenTLSConnection *connection);
void NSScreenTLSConnectionDestroy(NSScreenTLSConnection *connection);

// MARK: - App-managed SSH keys

// The returned opaque keys own OpenSSL EVP_PKEY instances. All import and construction entry points
// validate the algorithm, component bounds, and a private/public signing round trip before returning.
NSSshManagedKey *NSSshManagedKeyGenerate(
    NSSshManagedKeyAlgorithm algorithm,
    int rsaBits,
    char *errorBuffer,
    size_t errorBufferLength
);

// Imports PEM (including encrypted PKCS#8/traditional PEM) or DER private-key material. OpenSSH-v1
// containers are decoded by the bounded Swift parser and passed through the component constructors below.
NSSshManagedKey *NSSshManagedKeyImport(
    const uint8_t *encoded,
    size_t encodedLength,
    const uint8_t *passphrase,
    size_t passphraseLength,
    char *errorBuffer,
    size_t errorBufferLength
);

NSSshManagedKey *NSSshManagedKeyCreateEd25519(
    const uint8_t *seed,
    size_t seedLength,
    const uint8_t *expectedPublicKey,
    size_t expectedPublicKeyLength,
    char *errorBuffer,
    size_t errorBufferLength
);

NSSshManagedKey *NSSshManagedKeyCreateRSA(
    const uint8_t *modulus,
    size_t modulusLength,
    const uint8_t *publicExponent,
    size_t publicExponentLength,
    const uint8_t *privateExponent,
    size_t privateExponentLength,
    const uint8_t *coefficient,
    size_t coefficientLength,
    const uint8_t *primeP,
    size_t primePLength,
    const uint8_t *primeQ,
    size_t primeQLength,
    char *errorBuffer,
    size_t errorBufferLength
);

NSSshManagedKey *NSSshManagedKeyCreateECDSANistP256(
    const uint8_t *privateScalar,
    size_t privateScalarLength,
    const uint8_t *expectedPublicPoint,
    size_t expectedPublicPointLength,
    char *errorBuffer,
    size_t errorBufferLength
);

NSSshManagedKeyAlgorithm NSSshManagedKeyGetAlgorithm(const NSSshManagedKey *key);

// Output buffers are allocated by the bridge. Always release them with NSSshSensitiveBufferDestroy.
int NSSshManagedKeyCopyPKCS8(
    const NSSshManagedKey *key,
    uint8_t **output,
    size_t *outputLength,
    char *errorBuffer,
    size_t errorBufferLength
);

int NSSshManagedKeyCopyPublicKeyBlob(
    const NSSshManagedKey *key,
    uint8_t **output,
    size_t *outputLength,
    char *errorBuffer,
    size_t errorBufferLength
);

// Returns the native signature bytes: raw Ed25519/RSA, or ASN.1 DER for ECDSA. Swift performs the
// final SSH signature wrapper and DER-to-mpint conversion.
int NSSshManagedKeySign(
    const NSSshManagedKey *key,
    NSSshManagedSignatureAlgorithm algorithm,
    const uint8_t *message,
    size_t messageLength,
    uint8_t **output,
    size_t *outputLength,
    char *errorBuffer,
    size_t errorBufferLength
);

int NSSshManagedKeySelfTest(
    const NSSshManagedKey *key,
    char *errorBuffer,
    size_t errorBufferLength
);

void NSSshManagedKeyDestroy(NSSshManagedKey *key);
void NSSshSensitiveBufferDestroy(uint8_t *buffer, size_t length);

#ifdef __cplusplus
}
#endif

#endif
