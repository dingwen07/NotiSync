#ifndef NotiSyncOpenSSLBridge_h
#define NotiSyncOpenSSLBridge_h

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct NSScreenTLSListener NSScreenTLSListener;
typedef struct NSScreenTLSConnection NSScreenTLSConnection;

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

#ifdef __cplusplus
}
#endif

#endif
