#include "NotiSyncOpenSSLBridge.h"

#include <OpenSSL/crypto.h>
#include <OpenSSL/err.h>
#include <OpenSSL/ssl.h>
#include <arpa/inet.h>
#include <dispatch/dispatch.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

enum { NSScreenTLSCredentialCount = 2, NSScreenTLSKeyLength = 32 };

typedef struct {
    uint8_t *identity;
    size_t identityLength;
    uint8_t key[NSScreenTLSKeyLength];
} NSScreenTLSCredential;

struct NSScreenTLSListener {
    _Atomic int socketDescriptor;
    _Atomic int activeClientSocket;
    SSL_CTX *context;
    NSScreenTLSCredential credentials[NSScreenTLSCredentialCount];
};

struct NSScreenTLSConnection {
    _Atomic int socketDescriptor;
    SSL *ssl;
    NSScreenTLSListener *ownedCredentialOwner;
    struct NSScreenTLSStreamTransport *streamTransport;
};

typedef struct NSScreenTLSStreamTransport {
    NSScreenTLSStreamReadCallback readCallback;
    NSScreenTLSStreamWriteCallback writeCallback;
    NSScreenTLSStreamCloseCallback closeCallback;
    void *context;
    _Atomic int timeoutMilliseconds;
    _Atomic int closed;
} NSScreenTLSStreamTransport;

static const uint8_t NSScreenTLSALPN[] = "notisync-screen/1";

static void NSScreenTLSSetError(char *buffer, size_t length, const char *message) {
    if (buffer == NULL || length == 0) return;
    unsigned long error = ERR_peek_last_error();
    if (error == 0) {
        snprintf(buffer, length, "%s", message);
        return;
    }
    char detail[256];
    ERR_error_string_n(error, detail, sizeof(detail));
    snprintf(buffer, length, "%s: %s", message, detail);
}

static void NSScreenTLSSetSystemError(char *buffer, size_t length, const char *message) {
    if (buffer == NULL || length == 0) return;
    snprintf(buffer, length, "%s: %s", message, strerror(errno));
}

static void NSScreenTLSSetSystemErrorCode(
    char *buffer,
    size_t length,
    const char *message,
    int errorCode
) {
    if (buffer == NULL || length == 0) return;
    snprintf(buffer, length, "%s: %s", message, strerror(errorCode));
}

static int NSScreenTLSCopyCredential(
    NSScreenTLSCredential *destination,
    const uint8_t *identity,
    size_t identityLength,
    const uint8_t *key,
    size_t keyLength
) {
    if (destination == NULL || identity == NULL || identityLength == 0 ||
        identityLength > 255 || key == NULL || keyLength != NSScreenTLSKeyLength) {
        return 0;
    }
    destination->identity = calloc(identityLength + 1, 1);
    if (destination->identity == NULL) return 0;
    memcpy(destination->identity, identity, identityLength);
    destination->identityLength = identityLength;
    memcpy(destination->key, key, NSScreenTLSKeyLength);
    return 1;
}

static void NSScreenTLSClearCredential(NSScreenTLSCredential *credential) {
    if (credential == NULL) return;
    if (credential->identity != NULL) {
        OPENSSL_cleanse(credential->identity, credential->identityLength);
        free(credential->identity);
        credential->identity = NULL;
    }
    OPENSSL_cleanse(credential->key, sizeof(credential->key));
    credential->identityLength = 0;
}

static unsigned int NSScreenTLSPSKCallback(
    SSL *ssl,
    const char *identity,
    unsigned char *psk,
    unsigned int maximumPSKLength
) {
    if (ssl == NULL || identity == NULL || psk == NULL ||
        maximumPSKLength < NSScreenTLSKeyLength) {
        return 0;
    }
    SSL_CTX *context = SSL_get_SSL_CTX(ssl);
    NSScreenTLSListener *listener = context == NULL ? NULL : SSL_CTX_get_app_data(context);
    if (listener == NULL) return 0;
    size_t identityLength = strlen(identity);
    for (size_t index = 0; index < NSScreenTLSCredentialCount; index++) {
        NSScreenTLSCredential *credential = &listener->credentials[index];
        if (identityLength == credential->identityLength &&
            CRYPTO_memcmp(identity, credential->identity, identityLength) == 0) {
            memcpy(psk, credential->key, NSScreenTLSKeyLength);
            return NSScreenTLSKeyLength;
        }
    }
    return 0;
}

static int NSScreenTLSALPNCallback(
    SSL *ssl,
    const unsigned char **output,
    unsigned char *outputLength,
    const unsigned char *input,
    unsigned int inputLength,
    void *argument
) {
    (void)ssl;
    (void)argument;
    unsigned int offset = 0;
    while (offset < inputLength) {
        unsigned int length = input[offset++];
        if (length > inputLength - offset) return SSL_TLSEXT_ERR_ALERT_FATAL;
        if (length == sizeof(NSScreenTLSALPN) - 1 &&
            CRYPTO_memcmp(input + offset, NSScreenTLSALPN, length) == 0) {
            *output = NSScreenTLSALPN;
            *outputLength = (unsigned char)length;
            return SSL_TLSEXT_ERR_OK;
        }
        offset += length;
    }
    return SSL_TLSEXT_ERR_ALERT_FATAL;
}

static int NSScreenTLSConfigureContext(NSScreenTLSListener *owner) {
    owner->context = SSL_CTX_new(TLS_server_method());
    if (owner->context == NULL ||
        !SSL_CTX_set_min_proto_version(owner->context, TLS1_3_VERSION) ||
        !SSL_CTX_set_max_proto_version(owner->context, TLS1_3_VERSION) ||
        !SSL_CTX_set_ciphersuites(
            owner->context,
            "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256") ||
        !SSL_CTX_set1_groups_list(owner->context, "X25519")) {
        return 0;
    }
    SSL_CTX_set_app_data(owner->context, owner);
    SSL_CTX_set_verify(owner->context, SSL_VERIFY_NONE, NULL);
    SSL_CTX_set_session_cache_mode(owner->context, SSL_SESS_CACHE_OFF);
    SSL_CTX_set_num_tickets(owner->context, 0);
    SSL_CTX_set_max_early_data(owner->context, 0);
    SSL_CTX_set_options(owner->context, SSL_OP_NO_TICKET | SSL_OP_NO_RENEGOTIATION);
    SSL_CTX_set_mode(owner->context, SSL_MODE_AUTO_RETRY);
    SSL_CTX_set_psk_server_callback(owner->context, NSScreenTLSPSKCallback);
    SSL_CTX_set_alpn_select_cb(owner->context, NSScreenTLSALPNCallback, NULL);
    return 1;
}

static int NSScreenTLSValidateProfile(SSL *ssl) {
    const unsigned char *selectedALPN = NULL;
    unsigned int selectedALPNLength = 0;
    SSL_get0_alpn_selected(ssl, &selectedALPN, &selectedALPNLength);
    const SSL_CIPHER *cipher = SSL_get_current_cipher(ssl);
    const char *cipherName = cipher == NULL ? NULL : SSL_CIPHER_get_name(cipher);
    int supportedCipher = cipherName != NULL &&
        (strcmp(cipherName, "TLS_AES_128_GCM_SHA256") == 0 ||
         strcmp(cipherName, "TLS_CHACHA20_POLY1305_SHA256") == 0);
    return SSL_version(ssl) == TLS1_3_VERSION && supportedCipher &&
        selectedALPNLength == sizeof(NSScreenTLSALPN) - 1 &&
        CRYPTO_memcmp(selectedALPN, NSScreenTLSALPN, selectedALPNLength) == 0;
}

static int NSScreenTLSSetSocketTimeout(int socketDescriptor, int timeoutMilliseconds) {
    struct timeval timeout = {0};
    if (timeoutMilliseconds >= 0) {
        timeout.tv_sec = timeoutMilliseconds / 1000;
        timeout.tv_usec = (timeoutMilliseconds % 1000) * 1000;
    }
    return setsockopt(socketDescriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == 0 &&
           setsockopt(socketDescriptor, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) == 0;
}

static void NSScreenTLSCloseSocket(_Atomic int *socketDescriptor) {
    int descriptor = atomic_exchange(socketDescriptor, -1);
    if (descriptor < 0) return;
    shutdown(descriptor, SHUT_RDWR);
    close(descriptor);
}

static void NSScreenTLSCloseAcceptedSocket(
    NSScreenTLSListener *listener,
    int clientSocket
) {
    int expected = clientSocket;
    if (!atomic_compare_exchange_strong(&listener->activeClientSocket, &expected, -1)) return;
    shutdown(clientSocket, SHUT_RDWR);
    close(clientSocket);
}

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
) {
    if (port == NULL) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "missing listener port");
        return NULL;
    }
    *port = 0;
    NSScreenTLSListener *listener = calloc(1, sizeof(*listener));
    if (listener == NULL) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not allocate TLS listener");
        return NULL;
    }
    atomic_init(&listener->socketDescriptor, -1);
    atomic_init(&listener->activeClientSocket, -1);
    if (!NSScreenTLSCopyCredential(
            &listener->credentials[0], videoIdentity, videoIdentityLength, videoKey, videoKeyLength) ||
        !NSScreenTLSCopyCredential(
            &listener->credentials[1], controlIdentity, controlIdentityLength, controlKey, controlKeyLength)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "invalid screen credentials");
        NSScreenTLSListenerDestroy(listener);
        return NULL;
    }

    ERR_clear_error();
    if (!NSScreenTLSConfigureContext(listener)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not configure TLS 1.3");
        NSScreenTLSListenerDestroy(listener);
        return NULL;
    }

    int socketDescriptor = socket(AF_INET6, SOCK_STREAM, IPPROTO_TCP);
    if (socketDescriptor < 0) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not open LAN socket");
        NSScreenTLSListenerDestroy(listener);
        return NULL;
    }
    atomic_store(&listener->socketDescriptor, socketDescriptor);
    int enabled = 1;
    int disabled = 0;
    setsockopt(socketDescriptor, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    setsockopt(socketDescriptor, IPPROTO_IPV6, IPV6_V6ONLY, &disabled, sizeof(disabled));
    setsockopt(socketDescriptor, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled));

    struct sockaddr_in6 address;
    memset(&address, 0, sizeof(address));
    address.sin6_len = sizeof(address);
    address.sin6_family = AF_INET6;
    address.sin6_addr = in6addr_any;
    address.sin6_port = 0;
    if (bind(socketDescriptor, (struct sockaddr *)&address, sizeof(address)) != 0 ||
        listen(socketDescriptor, 8) != 0) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not bind LAN socket");
        NSScreenTLSListenerDestroy(listener);
        return NULL;
    }
    socklen_t addressLength = sizeof(address);
    if (getsockname(socketDescriptor, (struct sockaddr *)&address, &addressLength) != 0) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not read LAN port");
        NSScreenTLSListenerDestroy(listener);
        return NULL;
    }
    *port = ntohs(address.sin6_port);
    return listener;
}

void NSScreenTLSListenerClose(NSScreenTLSListener *listener) {
    if (listener == NULL) return;
    NSScreenTLSCloseSocket(&listener->socketDescriptor);
    NSScreenTLSCloseSocket(&listener->activeClientSocket);
}

void NSScreenTLSListenerDestroy(NSScreenTLSListener *listener) {
    if (listener == NULL) return;
    NSScreenTLSListenerClose(listener);
    if (listener->context != NULL) {
        SSL_CTX_free(listener->context);
        listener->context = NULL;
    }
    for (size_t index = 0; index < NSScreenTLSCredentialCount; index++) {
        NSScreenTLSClearCredential(&listener->credentials[index]);
    }
    free(listener);
}

int NSScreenTLSListenerAccept(
    NSScreenTLSListener *listener,
    int timeoutMilliseconds,
    NSScreenTLSConnection **connection,
    char *errorBuffer,
    size_t errorBufferLength
) {
    if (connection == NULL) return -1;
    *connection = NULL;
    if (listener == NULL || timeoutMilliseconds <= 0) return 0;
    int listenerSocket = atomic_load(&listener->socketDescriptor);
    if (listenerSocket < 0) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen listener is closed");
        return -1;
    }
    struct pollfd descriptor = { .fd = listenerSocket, .events = POLLIN };
    int pollResult;
    do {
        pollResult = poll(&descriptor, 1, timeoutMilliseconds);
    } while (pollResult < 0 && errno == EINTR);
    if (pollResult == 0) return 0;
    if (pollResult < 0 || (descriptor.revents & POLLIN) == 0) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not accept LAN connection");
        return -1;
    }

    int clientSocket = accept(listenerSocket, NULL, NULL);
    if (clientSocket < 0) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not accept LAN connection");
        return -1;
    }
    int expectedClientSocket = -1;
    if (!atomic_compare_exchange_strong(
            &listener->activeClientSocket, &expectedClientSocket, clientSocket)) {
        shutdown(clientSocket, SHUT_RDWR);
        close(clientSocket);
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen listener is closed");
        return -1;
    }
    if (atomic_load(&listener->socketDescriptor) < 0) {
        NSScreenTLSCloseAcceptedSocket(listener, clientSocket);
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen listener is closed");
        return -1;
    }
    int enabled = 1;
    setsockopt(clientSocket, IPPROTO_TCP, TCP_NODELAY, &enabled, sizeof(enabled));
    setsockopt(clientSocket, SOL_SOCKET, SO_KEEPALIVE, &enabled, sizeof(enabled));
    setsockopt(clientSocket, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled));
    int handshakeTimeout = timeoutMilliseconds < 10000 ? timeoutMilliseconds : 10000;
    if (!NSScreenTLSSetSocketTimeout(clientSocket, handshakeTimeout)) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not configure TLS timeout");
        NSScreenTLSCloseAcceptedSocket(listener, clientSocket);
        return -1;
    }

    ERR_clear_error();
    SSL *ssl = SSL_new(listener->context);
    if (ssl == NULL || !SSL_set_fd(ssl, clientSocket) || SSL_accept(ssl) != 1) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "TLS 1.3 PSK handshake failed");
        if (ssl != NULL) SSL_free(ssl);
        NSScreenTLSCloseAcceptedSocket(listener, clientSocket);
        return -1;
    }
    if (!NSScreenTLSValidateProfile(ssl)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "unsupported TLS screen profile");
        SSL_free(ssl);
        NSScreenTLSCloseAcceptedSocket(listener, clientSocket);
        return -1;
    }
    if (!NSScreenTLSSetSocketTimeout(clientSocket, -1)) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not clear TLS timeout");
        SSL_free(ssl);
        NSScreenTLSCloseAcceptedSocket(listener, clientSocket);
        return -1;
    }
    NSScreenTLSConnection *accepted = calloc(1, sizeof(*accepted));
    if (accepted == NULL) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not allocate screen connection");
        SSL_free(ssl);
        NSScreenTLSCloseAcceptedSocket(listener, clientSocket);
        return -1;
    }
    expectedClientSocket = clientSocket;
    if (!atomic_compare_exchange_strong(
            &listener->activeClientSocket, &expectedClientSocket, -1)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen listener is closed");
        SSL_free(ssl);
        free(accepted);
        return -1;
    }
    atomic_init(&accepted->socketDescriptor, clientSocket);
    accepted->ssl = ssl;
    *connection = accepted;
    return 1;
}

static int NSScreenTLSStreamBIOCreate(BIO *bio) {
    BIO_set_init(bio, 1);
    BIO_set_data(bio, NULL);
    return 1;
}

static int NSScreenTLSStreamBIODestroy(BIO *bio) {
    if (bio == NULL) return 0;
    BIO_set_data(bio, NULL);
    BIO_set_init(bio, 0);
    return 1;
}

static int NSScreenTLSStreamBIORead(
    BIO *bio,
    char *buffer,
    size_t maximumLength,
    size_t *readBytes
) {
    if (readBytes != NULL) *readBytes = 0;
    NSScreenTLSStreamTransport *transport = BIO_get_data(bio);
    if (transport == NULL || buffer == NULL || maximumLength == 0 ||
        atomic_load(&transport->closed)) return 0;
    ptrdiff_t result = transport->readCallback(
        transport->context,
        (uint8_t *)buffer,
        maximumLength,
        atomic_load(&transport->timeoutMilliseconds)
    );
    if (result > 0 && (size_t)result <= maximumLength) {
        if (readBytes != NULL) *readBytes = (size_t)result;
        return 1;
    }
    if (result == -2) errno = ETIMEDOUT;
    else if (result < 0) errno = EIO;
    return 0;
}

static int NSScreenTLSStreamBIOWrite(
    BIO *bio,
    const char *buffer,
    size_t length,
    size_t *writtenBytes
) {
    if (writtenBytes != NULL) *writtenBytes = 0;
    NSScreenTLSStreamTransport *transport = BIO_get_data(bio);
    if (transport == NULL || buffer == NULL || length == 0 ||
        atomic_load(&transport->closed)) return 0;
    ptrdiff_t result = transport->writeCallback(
        transport->context,
        (const uint8_t *)buffer,
        length,
        atomic_load(&transport->timeoutMilliseconds)
    );
    if (result > 0 && (size_t)result <= length) {
        if (writtenBytes != NULL) *writtenBytes = (size_t)result;
        return 1;
    }
    if (result == -2) errno = ETIMEDOUT;
    else if (result < 0) errno = EIO;
    return 0;
}

static long NSScreenTLSStreamBIOControl(BIO *bio, int command, long argument, void *pointer) {
    (void)bio;
    (void)argument;
    (void)pointer;
    return command == BIO_CTRL_FLUSH ? 1 : 0;
}

static BIO_METHOD *NSScreenTLSStreamBIOMethod(void) {
    static BIO_METHOD *method = NULL;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        method = BIO_meth_new(BIO_TYPE_SOURCE_SINK, "NotiSync Relay stream");
        if (method == NULL) return;
        BIO_meth_set_create(method, NSScreenTLSStreamBIOCreate);
        BIO_meth_set_destroy(method, NSScreenTLSStreamBIODestroy);
        BIO_meth_set_read_ex(method, NSScreenTLSStreamBIORead);
        BIO_meth_set_write_ex(method, NSScreenTLSStreamBIOWrite);
        BIO_meth_set_ctrl(method, NSScreenTLSStreamBIOControl);
    });
    return method;
}

static void NSScreenTLSCloseStreamTransport(NSScreenTLSStreamTransport *transport) {
    if (transport == NULL || atomic_exchange(&transport->closed, 1)) return;
    transport->closeCallback(transport->context);
}

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
) {
    if (readCallback == NULL || writeCallback == NULL || closeCallback == NULL || context == NULL ||
        handshakeTimeoutMilliseconds <= 0) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "invalid Relay TLS stream");
        return NULL;
    }
    NSScreenTLSListener *owner = calloc(1, sizeof(*owner));
    NSScreenTLSStreamTransport *transport = calloc(1, sizeof(*transport));
    if (owner == NULL || transport == NULL) {
        free(owner);
        free(transport);
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not allocate Relay TLS stream");
        closeCallback(context);
        return NULL;
    }
    atomic_init(&owner->socketDescriptor, -1);
    atomic_init(&owner->activeClientSocket, -1);
    transport->readCallback = readCallback;
    transport->writeCallback = writeCallback;
    transport->closeCallback = closeCallback;
    transport->context = context;
    atomic_init(&transport->timeoutMilliseconds, handshakeTimeoutMilliseconds);
    atomic_init(&transport->closed, 0);
    if (!NSScreenTLSCopyCredential(&owner->credentials[0], identity, identityLength, key, keyLength) ||
        !NSScreenTLSConfigureContext(owner)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not configure Relay TLS 1.3");
        NSScreenTLSListenerDestroy(owner);
        NSScreenTLSCloseStreamTransport(transport);
        free(transport);
        return NULL;
    }

    ERR_clear_error();
    SSL *ssl = SSL_new(owner->context);
    BIO_METHOD *method = NSScreenTLSStreamBIOMethod();
    BIO *bio = method == NULL ? NULL : BIO_new(method);
    if (ssl == NULL || bio == NULL) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not allocate Relay TLS connection");
        if (ssl != NULL) SSL_free(ssl);
        if (bio != NULL) BIO_free(bio);
        NSScreenTLSListenerDestroy(owner);
        NSScreenTLSCloseStreamTransport(transport);
        free(transport);
        return NULL;
    }
    BIO_set_data(bio, transport);
    SSL_set_bio(ssl, bio, bio);
    if (SSL_accept(ssl) != 1 || !NSScreenTLSValidateProfile(ssl)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "Relay TLS 1.3 PSK handshake failed");
        SSL_free(ssl);
        NSScreenTLSListenerDestroy(owner);
        NSScreenTLSCloseStreamTransport(transport);
        free(transport);
        return NULL;
    }

    NSScreenTLSConnection *connection = calloc(1, sizeof(*connection));
    if (connection == NULL) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "could not allocate Relay screen connection");
        SSL_free(ssl);
        NSScreenTLSListenerDestroy(owner);
        NSScreenTLSCloseStreamTransport(transport);
        free(transport);
        return NULL;
    }
    atomic_init(&connection->socketDescriptor, -2);
    connection->ssl = ssl;
    connection->ownedCredentialOwner = owner;
    connection->streamTransport = transport;
    return connection;
}

static void NSScreenTLSSetIOError(
    NSScreenTLSConnection *connection,
    int result,
    char *errorBuffer,
    size_t errorBufferLength,
    const char *operation
) {
    int systemError = errno;
    int sslError = SSL_get_error(connection->ssl, result);
    if (sslError == SSL_ERROR_ZERO_RETURN || (sslError == SSL_ERROR_SYSCALL && result == 0)) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen connection closed");
    } else if (sslError == SSL_ERROR_WANT_READ || sslError == SSL_ERROR_WANT_WRITE ||
        (sslError == SSL_ERROR_SYSCALL &&
         (systemError == EAGAIN || systemError == EWOULDBLOCK || systemError == ETIMEDOUT))) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen connection timed out");
    } else if (sslError == SSL_ERROR_SYSCALL && systemError != 0) {
        NSScreenTLSSetSystemErrorCode(errorBuffer, errorBufferLength, operation, systemError);
    } else {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, operation);
    }
}

int NSScreenTLSConnectionReadExactly(
    NSScreenTLSConnection *connection,
    uint8_t *buffer,
    size_t length,
    int timeoutMilliseconds,
    char *errorBuffer,
    size_t errorBufferLength
) {
    if (connection == NULL || buffer == NULL || connection->ssl == NULL) return 0;
    int socketDescriptor = atomic_load(&connection->socketDescriptor);
    if (socketDescriptor == -1) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen connection closed");
        return 0;
    }
    if (socketDescriptor >= 0 && !NSScreenTLSSetSocketTimeout(socketDescriptor, timeoutMilliseconds)) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not configure screen read timeout");
        return 0;
    } else if (socketDescriptor == -2) {
        atomic_store(&connection->streamTransport->timeoutMilliseconds, timeoutMilliseconds);
    }
    size_t offset = 0;
    while (offset < length) {
        size_t received = 0;
        ERR_clear_error();
        int result = SSL_read_ex(connection->ssl, buffer + offset, length - offset, &received);
        if (result != 1 || received == 0) {
            NSScreenTLSSetIOError(
                connection, result, errorBuffer, errorBufferLength, "could not read screen connection");
            return 0;
        }
        offset += received;
    }
    return 1;
}

int NSScreenTLSConnectionWriteAll(
    NSScreenTLSConnection *connection,
    const uint8_t *buffer,
    size_t length,
    int timeoutMilliseconds,
    char *errorBuffer,
    size_t errorBufferLength
) {
    if (connection == NULL || buffer == NULL || connection->ssl == NULL) return 0;
    int socketDescriptor = atomic_load(&connection->socketDescriptor);
    if (socketDescriptor == -1) {
        NSScreenTLSSetError(errorBuffer, errorBufferLength, "screen connection closed");
        return 0;
    }
    if (socketDescriptor >= 0 && !NSScreenTLSSetSocketTimeout(socketDescriptor, timeoutMilliseconds)) {
        NSScreenTLSSetSystemError(errorBuffer, errorBufferLength, "could not configure screen write timeout");
        return 0;
    } else if (socketDescriptor == -2) {
        atomic_store(&connection->streamTransport->timeoutMilliseconds, timeoutMilliseconds);
    }
    size_t offset = 0;
    while (offset < length) {
        size_t sent = 0;
        ERR_clear_error();
        int result = SSL_write_ex(connection->ssl, buffer + offset, length - offset, &sent);
        if (result != 1 || sent == 0) {
            NSScreenTLSSetIOError(
                connection, result, errorBuffer, errorBufferLength, "could not write screen connection");
            return 0;
        }
        offset += sent;
    }
    return 1;
}

void NSScreenTLSConnectionClose(NSScreenTLSConnection *connection) {
    if (connection == NULL) return;
    int descriptor = atomic_exchange(&connection->socketDescriptor, -1);
    if (descriptor >= 0) {
        shutdown(descriptor, SHUT_RDWR);
        close(descriptor);
    } else if (descriptor == -2) {
        NSScreenTLSCloseStreamTransport(connection->streamTransport);
    }
}

void NSScreenTLSConnectionDestroy(NSScreenTLSConnection *connection) {
    if (connection == NULL) return;
    NSScreenTLSConnectionClose(connection);
    if (connection->ssl != NULL) {
        SSL_free(connection->ssl);
        connection->ssl = NULL;
    }
    if (connection->ownedCredentialOwner != NULL) {
        NSScreenTLSListenerDestroy(connection->ownedCredentialOwner);
        connection->ownedCredentialOwner = NULL;
    }
    if (connection->streamTransport != NULL) {
        NSScreenTLSCloseStreamTransport(connection->streamTransport);
        OPENSSL_cleanse(connection->streamTransport, sizeof(*connection->streamTransport));
        free(connection->streamTransport);
        connection->streamTransport = NULL;
    }
    free(connection);
}
