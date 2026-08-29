#include "NotiSyncOpenSSLBridge.h"

#include <OpenSSL/crypto.h>
#include <OpenSSL/bn.h>
#include <OpenSSL/core_names.h>
#include <OpenSSL/ec.h>
#include <OpenSSL/err.h>
#include <OpenSSL/evp.h>
#include <OpenSSL/param_build.h>
#include <OpenSSL/pem.h>
#include <OpenSSL/rsa.h>
#include <OpenSSL/ssl.h>
#include <OpenSSL/x509.h>
#include <arpa/inet.h>
#include <dispatch/dispatch.h>
#include <errno.h>
#include <limits.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdbool.h>
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

// MARK: - App-managed SSH keys

enum {
    NSSshMaximumEncodedKeyBytes = 256 * 1024,
    NSSshMaximumPublicBlobBytes = 16 * 1024,
    NSSshMaximumSignatureBytes = 16 * 1024,
    NSSshMinimumRSABits = 2048,
    NSSshMaximumRSABits = 16384,
};

struct NSSshManagedKey {
    EVP_PKEY *pkey;
    NSSshManagedKeyAlgorithm algorithm;
};

typedef struct NSSshOutputBuffer {
    uint8_t *bytes;
    size_t length;
    size_t capacity;
} NSSshOutputBuffer;

typedef struct NSSshPassphrase {
    const uint8_t *bytes;
    size_t length;
} NSSshPassphrase;

static void NSSshSetError(
    char *buffer,
    size_t length,
    const char *message
) {
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

static NSSshManagedSignatureAlgorithm NSSshDefaultSignatureAlgorithm(
    NSSshManagedKeyAlgorithm algorithm
) {
    switch (algorithm) {
        case NSSshManagedKeyAlgorithmEd25519:
            return NSSshManagedSignatureAlgorithmEd25519;
        case NSSshManagedKeyAlgorithmRSA:
            return NSSshManagedSignatureAlgorithmRSASHA256;
        case NSSshManagedKeyAlgorithmECDSANistP256:
            return NSSshManagedSignatureAlgorithmECDSANistP256;
    }
    return 0;
}

static NSSshManagedKeyAlgorithm NSSshAlgorithmForPKey(EVP_PKEY *pkey) {
    if (pkey == NULL) return 0;
    if (EVP_PKEY_is_a(pkey, "ED25519")) return NSSshManagedKeyAlgorithmEd25519;
    if (EVP_PKEY_is_a(pkey, "RSA")) return NSSshManagedKeyAlgorithmRSA;
    if (!EVP_PKEY_is_a(pkey, "EC")) return 0;

    char group[80] = {0};
    size_t groupLength = 0;
    if (EVP_PKEY_get_utf8_string_param(
            pkey, OSSL_PKEY_PARAM_GROUP_NAME, group, sizeof(group), &groupLength) != 1) {
        return 0;
    }
    if (strcmp(group, "prime256v1") == 0 || strcmp(group, "secp256r1") == 0 ||
        strcmp(group, "P-256") == 0) {
        return NSSshManagedKeyAlgorithmECDSANistP256;
    }
    return 0;
}

static int NSSshValidateKeyParameters(
    EVP_PKEY *pkey,
    NSSshManagedKeyAlgorithm algorithm,
    char *errorBuffer,
    size_t errorBufferLength
) {
    if (pkey == NULL || algorithm == 0) {
        NSSshSetError(errorBuffer, errorBufferLength, "unsupported SSH private-key algorithm");
        return 0;
    }
    if (algorithm == NSSshManagedKeyAlgorithmRSA) {
        int bits = EVP_PKEY_get_bits(pkey);
        if (bits < NSSshMinimumRSABits || bits > NSSshMaximumRSABits) {
            NSSshSetError(errorBuffer, errorBufferLength, "RSA key size is outside 2048...16384 bits");
            return 0;
        }
    }

    EVP_PKEY_CTX *context = EVP_PKEY_CTX_new_from_pkey(NULL, pkey, NULL);
    if (context == NULL) {
        NSSshSetError(errorBuffer, errorBufferLength, "could not validate SSH private key");
        return 0;
    }
    int privateResult = EVP_PKEY_private_check(context);
    int publicResult = EVP_PKEY_public_check(context);
    EVP_PKEY_CTX_free(context);
    if (privateResult != 1 || publicResult != 1) {
        NSSshSetError(errorBuffer, errorBufferLength, "SSH private-key parameters are invalid");
        return 0;
    }
    return 1;
}

static NSSshManagedKey *NSSshWrapValidatedKey(
    EVP_PKEY *pkey,
    char *errorBuffer,
    size_t errorBufferLength
) {
    NSSshManagedKeyAlgorithm algorithm = NSSshAlgorithmForPKey(pkey);
    if (!NSSshValidateKeyParameters(pkey, algorithm, errorBuffer, errorBufferLength)) {
        EVP_PKEY_free(pkey);
        return NULL;
    }
    NSSshManagedKey *key = calloc(1, sizeof(*key));
    if (key == NULL) {
        EVP_PKEY_free(pkey);
        NSSshSetError(errorBuffer, errorBufferLength, "could not allocate SSH private key");
        return NULL;
    }
    key->pkey = pkey;
    key->algorithm = algorithm;
    if (NSSshManagedKeySelfTest(key, errorBuffer, errorBufferLength) != 1) {
        NSSshManagedKeyDestroy(key);
        return NULL;
    }
    return key;
}

static int NSSshPassphraseCallback(char *buffer, int size, int writing, void *context) {
    (void)writing;
    NSSshPassphrase *passphrase = context;
    if (buffer == NULL || size <= 0 || passphrase == NULL || passphrase->bytes == NULL ||
        passphrase->length == 0 || passphrase->length > (size_t)size || passphrase->length > INT_MAX) {
        return 0;
    }
    memcpy(buffer, passphrase->bytes, passphrase->length);
    return (int)passphrase->length;
}

static int NSSshContainsPEMHeader(const uint8_t *bytes, size_t length) {
    static const char header[] = "-----BEGIN ";
    if (bytes == NULL || length < sizeof(header) - 1) return 0;
    size_t scanLength = length < 128 ? length : 128;
    for (size_t offset = 0; offset + sizeof(header) - 1 <= scanLength; offset++) {
        if (memcmp(bytes + offset, header, sizeof(header) - 1) == 0) return 1;
    }
    return 0;
}

static int NSSshBIOHasOnlyWhitespace(BIO *bio) {
    if (bio == NULL) return 0;
    uint8_t chunk[128];
    int count = 0;
    while ((count = BIO_read(bio, chunk, sizeof(chunk))) > 0) {
        for (int index = 0; index < count; index++) {
            switch (chunk[index]) {
                case ' ':
                case '\t':
                case '\r':
                case '\n':
                    break;
                default:
                    return 0;
            }
        }
    }
    return count == 0;
}

NSSshManagedKey *NSSshManagedKeyGenerate(
    NSSshManagedKeyAlgorithm algorithm,
    int rsaBits,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    const char *name = NULL;
    switch (algorithm) {
        case NSSshManagedKeyAlgorithmEd25519:
            name = "ED25519";
            break;
        case NSSshManagedKeyAlgorithmRSA:
            if (rsaBits != 2048 && rsaBits != 3072 && rsaBits != 4096) {
                NSSshSetError(errorBuffer, errorBufferLength, "RSA generation supports 2048, 3072, or 4096 bits");
                return NULL;
            }
            name = "RSA";
            break;
        case NSSshManagedKeyAlgorithmECDSANistP256:
            name = "EC";
            break;
        default:
            NSSshSetError(errorBuffer, errorBufferLength, "unsupported SSH key-generation algorithm");
            return NULL;
    }

    EVP_PKEY_CTX *context = EVP_PKEY_CTX_new_from_name(NULL, name, NULL);
    EVP_PKEY *pkey = NULL;
    if (context == NULL || EVP_PKEY_keygen_init(context) != 1 ||
        (algorithm == NSSshManagedKeyAlgorithmRSA &&
         EVP_PKEY_CTX_set_rsa_keygen_bits(context, rsaBits) != 1) ||
        (algorithm == NSSshManagedKeyAlgorithmECDSANistP256 &&
         EVP_PKEY_CTX_set_group_name(context, "prime256v1") != 1) ||
        EVP_PKEY_keygen(context, &pkey) != 1) {
        EVP_PKEY_CTX_free(context);
        EVP_PKEY_free(pkey);
        NSSshSetError(errorBuffer, errorBufferLength, "could not generate SSH private key");
        return NULL;
    }
    EVP_PKEY_CTX_free(context);
    return NSSshWrapValidatedKey(pkey, errorBuffer, errorBufferLength);
}

NSSshManagedKey *NSSshManagedKeyImport(
    const uint8_t *encoded,
    size_t encodedLength,
    const uint8_t *passphrase,
    size_t passphraseLength,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    if (encoded == NULL || encodedLength == 0 || encodedLength > NSSshMaximumEncodedKeyBytes ||
        passphraseLength > 4096 || (passphraseLength > 0 && passphrase == NULL) || encodedLength > INT_MAX) {
        NSSshSetError(errorBuffer, errorBufferLength, "SSH private-key input is outside the allowed bounds");
        return NULL;
    }

    NSSshPassphrase password = { passphrase, passphraseLength };
    EVP_PKEY *pkey = NULL;
    if (NSSshContainsPEMHeader(encoded, encodedLength)) {
        BIO *bio = BIO_new_mem_buf(encoded, (int)encodedLength);
        if (bio != NULL) {
            pkey = PEM_read_bio_PrivateKey(bio, NULL, NSSshPassphraseCallback, &password);
            if (pkey != NULL && !NSSshBIOHasOnlyWhitespace(bio)) {
                EVP_PKEY_free(pkey);
                pkey = NULL;
                ERR_clear_error();
                ERR_raise(ERR_LIB_PEM, PEM_R_BAD_END_LINE);
            }
        }
        BIO_free(bio);
    } else {
        BIO *bio = BIO_new_mem_buf(encoded, (int)encodedLength);
        if (bio != NULL) {
            pkey = d2i_PKCS8PrivateKey_bio(bio, NULL, NSSshPassphraseCallback, &password);
            if (pkey != NULL && BIO_ctrl_pending(bio) != 0) {
                EVP_PKEY_free(pkey);
                pkey = NULL;
            }
        }
        BIO_free(bio);
        if (pkey == NULL) {
            ERR_clear_error();
            const unsigned char *cursor = encoded;
            pkey = d2i_AutoPrivateKey(NULL, &cursor, (long)encodedLength);
            if (pkey != NULL && cursor != encoded + encodedLength) {
                EVP_PKEY_free(pkey);
                pkey = NULL;
            }
        }
    }
    if (pkey == NULL) {
        NSSshSetError(errorBuffer, errorBufferLength,
                      passphraseLength == 0 ? "invalid private key or passphrase required" :
                                              "invalid private key or passphrase");
        return NULL;
    }
    return NSSshWrapValidatedKey(pkey, errorBuffer, errorBufferLength);
}

NSSshManagedKey *NSSshManagedKeyCreateEd25519(
    const uint8_t *seed,
    size_t seedLength,
    const uint8_t *expectedPublicKey,
    size_t expectedPublicKeyLength,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    if (seed == NULL || seedLength != 32 || expectedPublicKey == NULL || expectedPublicKeyLength != 32) {
        NSSshSetError(errorBuffer, errorBufferLength, "Ed25519 identity components have invalid lengths");
        return NULL;
    }
    EVP_PKEY *pkey = EVP_PKEY_new_raw_private_key_ex(NULL, "ED25519", NULL, seed, seedLength);
    uint8_t derivedPublic[32];
    size_t derivedPublicLength = sizeof(derivedPublic);
    if (pkey == NULL || EVP_PKEY_get_raw_public_key(pkey, derivedPublic, &derivedPublicLength) != 1 ||
        derivedPublicLength != expectedPublicKeyLength ||
        CRYPTO_memcmp(derivedPublic, expectedPublicKey, expectedPublicKeyLength) != 0) {
        OPENSSL_cleanse(derivedPublic, sizeof(derivedPublic));
        EVP_PKEY_free(pkey);
        NSSshSetError(errorBuffer, errorBufferLength, "Ed25519 private and public components do not match");
        return NULL;
    }
    OPENSSL_cleanse(derivedPublic, sizeof(derivedPublic));
    return NSSshWrapValidatedKey(pkey, errorBuffer, errorBufferLength);
}

static BIGNUM *NSSshBNFromBytes(const uint8_t *bytes, size_t length) {
    if (bytes == NULL || length == 0 || length > 2048 || length > INT_MAX) return NULL;
    return BN_bin2bn(bytes, (int)length, NULL);
}

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
) {
    ERR_clear_error();
    BIGNUM *n = NSSshBNFromBytes(modulus, modulusLength);
    BIGNUM *e = NSSshBNFromBytes(publicExponent, publicExponentLength);
    BIGNUM *d = NSSshBNFromBytes(privateExponent, privateExponentLength);
    BIGNUM *iqmp = NSSshBNFromBytes(coefficient, coefficientLength);
    BIGNUM *p = NSSshBNFromBytes(primeP, primePLength);
    BIGNUM *q = NSSshBNFromBytes(primeQ, primeQLength);
    BIGNUM *product = BN_new();
    BIGNUM *inverse = NULL;
    BIGNUM *pMinusOne = p == NULL ? NULL : BN_dup(p);
    BIGNUM *qMinusOne = q == NULL ? NULL : BN_dup(q);
    BIGNUM *dmp1 = BN_new();
    BIGNUM *dmq1 = BN_new();
    BN_CTX *bnContext = BN_CTX_new();
    EVP_PKEY_CTX *keyContext = NULL;
    OSSL_PARAM_BLD *builder = NULL;
    OSSL_PARAM *parameters = NULL;
    EVP_PKEY *pkey = NULL;

    int valid = n != NULL && e != NULL && d != NULL && iqmp != NULL && p != NULL && q != NULL &&
        product != NULL && pMinusOne != NULL && qMinusOne != NULL && dmp1 != NULL && dmq1 != NULL &&
        bnContext != NULL && BN_num_bits(n) >= NSSshMinimumRSABits &&
        BN_num_bits(n) <= NSSshMaximumRSABits && BN_cmp(e, BN_value_one()) > 0 && BN_is_odd(e) &&
        BN_is_odd(p) && BN_is_odd(q) && BN_mul(product, p, q, bnContext) == 1 && BN_cmp(product, n) == 0 &&
        (inverse = BN_mod_inverse(NULL, q, p, bnContext)) != NULL && BN_cmp(inverse, iqmp) == 0 &&
        BN_sub_word(pMinusOne, 1) == 1 && BN_sub_word(qMinusOne, 1) == 1 &&
        BN_mod(dmp1, d, pMinusOne, bnContext) == 1 && BN_mod(dmq1, d, qMinusOne, bnContext) == 1;
    if (valid) {
        builder = OSSL_PARAM_BLD_new();
        valid = builder != NULL &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_N, n) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_E, e) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_D, d) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_FACTOR1, p) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_FACTOR2, q) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_EXPONENT1, dmp1) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_EXPONENT2, dmq1) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_RSA_COEFFICIENT1, iqmp) == 1 &&
            (parameters = OSSL_PARAM_BLD_to_param(builder)) != NULL &&
            (keyContext = EVP_PKEY_CTX_new_from_name(NULL, "RSA", NULL)) != NULL &&
            EVP_PKEY_fromdata_init(keyContext) == 1 &&
            EVP_PKEY_fromdata(keyContext, &pkey, EVP_PKEY_KEYPAIR, parameters) == 1;
    }

    EVP_PKEY_CTX_free(keyContext);
    OSSL_PARAM_free(parameters);
    OSSL_PARAM_BLD_free(builder);
    BN_clear_free(n);
    BN_clear_free(e);
    BN_clear_free(d);
    BN_clear_free(iqmp);
    BN_clear_free(p);
    BN_clear_free(q);
    BN_clear_free(product);
    BN_clear_free(inverse);
    BN_clear_free(pMinusOne);
    BN_clear_free(qMinusOne);
    BN_clear_free(dmp1);
    BN_clear_free(dmq1);
    BN_CTX_free(bnContext);

    if (!valid || pkey == NULL) {
        EVP_PKEY_free(pkey);
        NSSshSetError(errorBuffer, errorBufferLength, "RSA identity components are inconsistent");
        return NULL;
    }
    return NSSshWrapValidatedKey(pkey, errorBuffer, errorBufferLength);
}

NSSshManagedKey *NSSshManagedKeyCreateECDSANistP256(
    const uint8_t *privateScalar,
    size_t privateScalarLength,
    const uint8_t *expectedPublicPoint,
    size_t expectedPublicPointLength,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    if (privateScalar == NULL || privateScalarLength == 0 || privateScalarLength > 32 ||
        expectedPublicPoint == NULL || expectedPublicPointLength != 65 || expectedPublicPoint[0] != 4) {
        NSSshSetError(errorBuffer, errorBufferLength, "P-256 identity components have invalid lengths");
        return NULL;
    }

    BIGNUM *scalar = BN_bin2bn(privateScalar, (int)privateScalarLength, NULL);
    BIGNUM *order = BN_new();
    EC_GROUP *group = EC_GROUP_new_by_curve_name(NID_X9_62_prime256v1);
    EC_POINT *expected = group == NULL ? NULL : EC_POINT_new(group);
    EC_POINT *derived = group == NULL ? NULL : EC_POINT_new(group);
    BN_CTX *bnContext = BN_CTX_new();
    EVP_PKEY_CTX *keyContext = NULL;
    OSSL_PARAM_BLD *builder = NULL;
    OSSL_PARAM *parameters = NULL;
    EVP_PKEY *pkey = NULL;

    int valid = scalar != NULL && order != NULL && group != NULL && expected != NULL && derived != NULL &&
        bnContext != NULL && !BN_is_zero(scalar) && !BN_is_negative(scalar) &&
        EC_GROUP_get_order(group, order, bnContext) == 1 && BN_cmp(scalar, order) < 0 &&
        EC_POINT_oct2point(group, expected, expectedPublicPoint, expectedPublicPointLength, bnContext) == 1 &&
        EC_POINT_is_on_curve(group, expected, bnContext) == 1 &&
        EC_POINT_mul(group, derived, scalar, NULL, NULL, bnContext) == 1 &&
        EC_POINT_cmp(group, expected, derived, bnContext) == 0;
    if (valid) {
        builder = OSSL_PARAM_BLD_new();
        valid = builder != NULL &&
            OSSL_PARAM_BLD_push_utf8_string(builder, OSSL_PKEY_PARAM_GROUP_NAME, "prime256v1", 0) == 1 &&
            OSSL_PARAM_BLD_push_BN(builder, OSSL_PKEY_PARAM_PRIV_KEY, scalar) == 1 &&
            OSSL_PARAM_BLD_push_octet_string(
                builder, OSSL_PKEY_PARAM_PUB_KEY, expectedPublicPoint, expectedPublicPointLength) == 1 &&
            (parameters = OSSL_PARAM_BLD_to_param(builder)) != NULL &&
            (keyContext = EVP_PKEY_CTX_new_from_name(NULL, "EC", NULL)) != NULL &&
            EVP_PKEY_fromdata_init(keyContext) == 1 &&
            EVP_PKEY_fromdata(keyContext, &pkey, EVP_PKEY_KEYPAIR, parameters) == 1;
    }

    EVP_PKEY_CTX_free(keyContext);
    OSSL_PARAM_free(parameters);
    OSSL_PARAM_BLD_free(builder);
    BN_clear_free(scalar);
    BN_clear_free(order);
    EC_POINT_free(expected);
    EC_POINT_free(derived);
    EC_GROUP_free(group);
    BN_CTX_free(bnContext);
    if (!valid || pkey == NULL) {
        EVP_PKEY_free(pkey);
        NSSshSetError(errorBuffer, errorBufferLength, "P-256 private and public components do not match");
        return NULL;
    }
    return NSSshWrapValidatedKey(pkey, errorBuffer, errorBufferLength);
}

NSSshManagedKeyAlgorithm NSSshManagedKeyGetAlgorithm(const NSSshManagedKey *key) {
    return key == NULL ? 0 : key->algorithm;
}

static int NSSshOutputReserve(NSSshOutputBuffer *output, size_t additional) {
    if (output == NULL || additional > NSSshMaximumPublicBlobBytes ||
        output->length > NSSshMaximumPublicBlobBytes - additional) {
        return 0;
    }
    size_t needed = output->length + additional;
    if (needed <= output->capacity) return 1;
    size_t capacity = output->capacity == 0 ? 128 : output->capacity;
    while (capacity < needed) {
        if (capacity > NSSshMaximumPublicBlobBytes / 2) {
            capacity = NSSshMaximumPublicBlobBytes;
            break;
        }
        capacity *= 2;
    }
    uint8_t *bytes = realloc(output->bytes, capacity);
    if (bytes == NULL) return 0;
    output->bytes = bytes;
    output->capacity = capacity;
    return 1;
}

static int NSSshOutputRaw(NSSshOutputBuffer *output, const void *bytes, size_t length) {
    if ((length > 0 && bytes == NULL) || !NSSshOutputReserve(output, length)) return 0;
    if (length > 0) memcpy(output->bytes + output->length, bytes, length);
    output->length += length;
    return 1;
}

static int NSSshOutputUInt32(NSSshOutputBuffer *output, uint32_t value) {
    uint8_t encoded[4] = {
        (uint8_t)(value >> 24), (uint8_t)(value >> 16), (uint8_t)(value >> 8), (uint8_t)value,
    };
    return NSSshOutputRaw(output, encoded, sizeof(encoded));
}

static int NSSshOutputString(NSSshOutputBuffer *output, const void *bytes, size_t length) {
    return length <= UINT32_MAX && NSSshOutputUInt32(output, (uint32_t)length) &&
        NSSshOutputRaw(output, bytes, length);
}

static int NSSshOutputCString(NSSshOutputBuffer *output, const char *value) {
    return value != NULL && NSSshOutputString(output, value, strlen(value));
}

static int NSSshOutputBNMpInt(NSSshOutputBuffer *output, const BIGNUM *value) {
    if (value == NULL || BN_is_negative(value)) return 0;
    if (BN_is_zero(value)) return NSSshOutputString(output, NULL, 0);
    size_t byteCount = (size_t)BN_num_bytes(value);
    int prefix = BN_is_bit_set(value, (int)(byteCount * 8 - 1));
    if (byteCount > NSSshMaximumPublicBlobBytes - (size_t)prefix ||
        !NSSshOutputUInt32(output, (uint32_t)(byteCount + (size_t)prefix)) ||
        (prefix && !NSSshOutputRaw(output, "\0", 1)) ||
        !NSSshOutputReserve(output, byteCount)) {
        return 0;
    }
    if (BN_bn2bin(value, output->bytes + output->length) != (int)byteCount) return 0;
    output->length += byteCount;
    return 1;
}

int NSSshManagedKeyCopyPKCS8(
    const NSSshManagedKey *key,
    uint8_t **output,
    size_t *outputLength,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    if (output == NULL || outputLength == NULL) return 0;
    *output = NULL;
    *outputLength = 0;
    if (key == NULL || key->pkey == NULL) {
        NSSshSetError(errorBuffer, errorBufferLength, "missing SSH private key");
        return 0;
    }
    PKCS8_PRIV_KEY_INFO *info = EVP_PKEY2PKCS8(key->pkey);
    int encodedLength = info == NULL ? 0 : i2d_PKCS8_PRIV_KEY_INFO(info, NULL);
    if (encodedLength <= 0 || encodedLength > NSSshMaximumEncodedKeyBytes) {
        PKCS8_PRIV_KEY_INFO_free(info);
        NSSshSetError(errorBuffer, errorBufferLength, "could not encode SSH private key");
        return 0;
    }
    uint8_t *encoded = malloc((size_t)encodedLength);
    unsigned char *cursor = encoded;
    if (encoded == NULL || i2d_PKCS8_PRIV_KEY_INFO(info, &cursor) != encodedLength) {
        PKCS8_PRIV_KEY_INFO_free(info);
        NSSshSensitiveBufferDestroy(encoded, (size_t)encodedLength);
        NSSshSetError(errorBuffer, errorBufferLength, "could not encode SSH private key");
        return 0;
    }
    PKCS8_PRIV_KEY_INFO_free(info);
    *output = encoded;
    *outputLength = (size_t)encodedLength;
    return 1;
}

int NSSshManagedKeyCopyPublicKeyBlob(
    const NSSshManagedKey *key,
    uint8_t **output,
    size_t *outputLength,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    if (output == NULL || outputLength == NULL) return 0;
    *output = NULL;
    *outputLength = 0;
    if (key == NULL || key->pkey == NULL) {
        NSSshSetError(errorBuffer, errorBufferLength, "missing SSH public key");
        return 0;
    }
    NSSshOutputBuffer encoded = {0};
    int success = 0;
    if (key->algorithm == NSSshManagedKeyAlgorithmEd25519) {
        uint8_t publicKey[32];
        size_t publicKeyLength = sizeof(publicKey);
        success = EVP_PKEY_get_raw_public_key(key->pkey, publicKey, &publicKeyLength) == 1 &&
            publicKeyLength == sizeof(publicKey) && NSSshOutputCString(&encoded, "ssh-ed25519") &&
            NSSshOutputString(&encoded, publicKey, publicKeyLength);
        OPENSSL_cleanse(publicKey, sizeof(publicKey));
    } else if (key->algorithm == NSSshManagedKeyAlgorithmRSA) {
        BIGNUM *modulus = NULL;
        BIGNUM *exponent = NULL;
        success = EVP_PKEY_get_bn_param(key->pkey, OSSL_PKEY_PARAM_RSA_N, &modulus) == 1 &&
            EVP_PKEY_get_bn_param(key->pkey, OSSL_PKEY_PARAM_RSA_E, &exponent) == 1 &&
            NSSshOutputCString(&encoded, "ssh-rsa") && NSSshOutputBNMpInt(&encoded, exponent) &&
            NSSshOutputBNMpInt(&encoded, modulus);
        BN_free(modulus);
        BN_free(exponent);
    } else if (key->algorithm == NSSshManagedKeyAlgorithmECDSANistP256) {
        BIGNUM *x = NULL;
        BIGNUM *y = NULL;
        uint8_t point[65] = {4};
        success = EVP_PKEY_get_bn_param(key->pkey, OSSL_PKEY_PARAM_EC_PUB_X, &x) == 1 &&
            EVP_PKEY_get_bn_param(key->pkey, OSSL_PKEY_PARAM_EC_PUB_Y, &y) == 1 &&
            BN_bn2binpad(x, point + 1, 32) == 32 && BN_bn2binpad(y, point + 33, 32) == 32 &&
            NSSshOutputCString(&encoded, "ecdsa-sha2-nistp256") &&
            NSSshOutputCString(&encoded, "nistp256") && NSSshOutputString(&encoded, point, sizeof(point));
        BN_free(x);
        BN_free(y);
        OPENSSL_cleanse(point, sizeof(point));
    }
    if (!success || encoded.length == 0 || encoded.length > NSSshMaximumPublicBlobBytes) {
        NSSshSensitiveBufferDestroy(encoded.bytes, encoded.capacity);
        NSSshSetError(errorBuffer, errorBufferLength, "could not encode SSH public key");
        return 0;
    }
    *output = encoded.bytes;
    *outputLength = encoded.length;
    return 1;
}

static const EVP_MD *NSSshSignatureDigest(
    NSSshManagedKeyAlgorithm keyAlgorithm,
    NSSshManagedSignatureAlgorithm signatureAlgorithm
) {
    switch (signatureAlgorithm) {
        case NSSshManagedSignatureAlgorithmEd25519:
            return NULL; // Ed25519 signs the message directly; algorithm matching is checked separately.
        case NSSshManagedSignatureAlgorithmRSASHA256:
            return keyAlgorithm == NSSshManagedKeyAlgorithmRSA ? EVP_sha256() : NULL;
        case NSSshManagedSignatureAlgorithmRSASHA512:
            return keyAlgorithm == NSSshManagedKeyAlgorithmRSA ? EVP_sha512() : NULL;
        case NSSshManagedSignatureAlgorithmECDSANistP256:
            return keyAlgorithm == NSSshManagedKeyAlgorithmECDSANistP256 ? EVP_sha256() : NULL;
        case NSSshManagedSignatureAlgorithmRSASHA1Legacy:
            return keyAlgorithm == NSSshManagedKeyAlgorithmRSA ? EVP_sha1() : NULL;
    }
    return NULL;
}

static int NSSshSignatureAlgorithmMatches(
    NSSshManagedKeyAlgorithm keyAlgorithm,
    NSSshManagedSignatureAlgorithm signatureAlgorithm
) {
    return (keyAlgorithm == NSSshManagedKeyAlgorithmEd25519 &&
            signatureAlgorithm == NSSshManagedSignatureAlgorithmEd25519) ||
        (keyAlgorithm == NSSshManagedKeyAlgorithmRSA &&
         (signatureAlgorithm == NSSshManagedSignatureAlgorithmRSASHA256 ||
          signatureAlgorithm == NSSshManagedSignatureAlgorithmRSASHA512 ||
          signatureAlgorithm == NSSshManagedSignatureAlgorithmRSASHA1Legacy)) ||
        (keyAlgorithm == NSSshManagedKeyAlgorithmECDSANistP256 &&
         signatureAlgorithm == NSSshManagedSignatureAlgorithmECDSANistP256);
}

static int NSSshConfigureSignatureContext(
    EVP_PKEY_CTX *context,
    NSSshManagedKeyAlgorithm keyAlgorithm
) {
    return keyAlgorithm != NSSshManagedKeyAlgorithmRSA ||
        (context != NULL && EVP_PKEY_CTX_set_rsa_padding(context, RSA_PKCS1_PADDING) == 1);
}

int NSSshManagedKeySign(
    const NSSshManagedKey *key,
    NSSshManagedSignatureAlgorithm algorithm,
    const uint8_t *message,
    size_t messageLength,
    uint8_t **output,
    size_t *outputLength,
    char *errorBuffer,
    size_t errorBufferLength
) {
    ERR_clear_error();
    if (output == NULL || outputLength == NULL) return 0;
    *output = NULL;
    *outputLength = 0;
    if (key == NULL || key->pkey == NULL || (messageLength > 0 && message == NULL) ||
        messageLength > NSSshMaximumEncodedKeyBytes ||
        !NSSshSignatureAlgorithmMatches(key->algorithm, algorithm)) {
        NSSshSetError(errorBuffer, errorBufferLength, "SSH signature algorithm does not match the key");
        return 0;
    }
    const EVP_MD *digest = NSSshSignatureDigest(key->algorithm, algorithm);
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    EVP_PKEY_CTX *pkeyContext = NULL;
    size_t signatureLength = 0;
    uint8_t *signature = NULL;
    int success = context != NULL &&
        EVP_DigestSignInit(context, &pkeyContext, digest, NULL, key->pkey) == 1 &&
        NSSshConfigureSignatureContext(pkeyContext, key->algorithm) &&
        EVP_DigestSign(context, NULL, &signatureLength, message, messageLength) == 1 &&
        signatureLength > 0 && signatureLength <= NSSshMaximumSignatureBytes &&
        (signature = malloc(signatureLength)) != NULL &&
        EVP_DigestSign(context, signature, &signatureLength, message, messageLength) == 1;
    EVP_MD_CTX_free(context);
    if (!success) {
        NSSshSensitiveBufferDestroy(signature, signatureLength);
        NSSshSetError(errorBuffer, errorBufferLength, "could not sign with SSH private key");
        return 0;
    }
    *output = signature;
    *outputLength = signatureLength;
    return 1;
}

static int NSSshManagedKeyVerifyNative(
    const NSSshManagedKey *key,
    NSSshManagedSignatureAlgorithm algorithm,
    const uint8_t *message,
    size_t messageLength,
    const uint8_t *signature,
    size_t signatureLength
) {
    const EVP_MD *digest = NSSshSignatureDigest(key->algorithm, algorithm);
    EVP_MD_CTX *context = EVP_MD_CTX_new();
    EVP_PKEY_CTX *pkeyContext = NULL;
    int verified = context != NULL &&
        EVP_DigestVerifyInit(context, &pkeyContext, digest, NULL, key->pkey) == 1 &&
        NSSshConfigureSignatureContext(pkeyContext, key->algorithm) &&
        EVP_DigestVerify(context, signature, signatureLength, message, messageLength) == 1;
    EVP_MD_CTX_free(context);
    return verified;
}

int NSSshManagedKeySelfTest(
    const NSSshManagedKey *key,
    char *errorBuffer,
    size_t errorBufferLength
) {
    static const uint8_t challenge[] = {
        0x4e, 0x6f, 0x74, 0x69, 0x53, 0x79, 0x6e, 0x63,
        0x2d, 0x53, 0x53, 0x48, 0x2d, 0x73, 0x65, 0x6c,
        0x66, 0x2d, 0x74, 0x65, 0x73, 0x74, 0x2d, 0x76,
        0x31, 0x00, 0xa5, 0x5a, 0x7c, 0x13, 0x82, 0xe1,
    };
    if (key == NULL) {
        NSSshSetError(errorBuffer, errorBufferLength, "missing SSH private key");
        return 0;
    }
    uint8_t *signature = NULL;
    size_t signatureLength = 0;
    NSSshManagedSignatureAlgorithm algorithm = NSSshDefaultSignatureAlgorithm(key->algorithm);
    int signedResult = NSSshManagedKeySign(
        key, algorithm, challenge, sizeof(challenge), &signature, &signatureLength,
        errorBuffer, errorBufferLength);
    int verified = signedResult == 1 && NSSshManagedKeyVerifyNative(
        key, algorithm, challenge, sizeof(challenge), signature, signatureLength);
    NSSshSensitiveBufferDestroy(signature, signatureLength);
    if (!verified) {
        NSSshSetError(errorBuffer, errorBufferLength, "SSH private/public key self-test failed");
        return 0;
    }
    return 1;
}

void NSSshManagedKeyDestroy(NSSshManagedKey *key) {
    if (key == NULL) return;
    EVP_PKEY_free(key->pkey);
    key->pkey = NULL;
    OPENSSL_cleanse(key, sizeof(*key));
    free(key);
}

void NSSshSensitiveBufferDestroy(uint8_t *buffer, size_t length) {
    if (buffer == NULL) return;
    if (length > 0) OPENSSL_cleanse(buffer, length);
    free(buffer);
}
