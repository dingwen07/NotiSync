#ifndef BrokerPairingPake_h
#define BrokerPairingPake_h
#include <stddef.h>
#include <stdint.h>

typedef struct NSBrokerPairingPake NSBrokerPairingPake;

// RFC 8236 finite-field arithmetic. Inputs/outputs contain length-prefixed positive Java BigIntegers.
// The Swift owner handles the shared CBOR codec, round-3 confirmation and authenticated CARD encryption.
NSBrokerPairingPake *NSBrokerPairingPakeCreate(const char *localID, const char *remoteID,
                                             const uint8_t *secret, size_t secretLength);
int NSBrokerPairingPakeRound1(NSBrokerPairingPake *, uint8_t **output, size_t *length);
int NSBrokerPairingPakeRound2(NSBrokerPairingPake *, const uint8_t *input, size_t inputLength,
                            uint8_t **output, size_t *length);
int NSBrokerPairingPakeKey(NSBrokerPairingPake *, const uint8_t *input, size_t inputLength,
                         uint8_t **output, size_t *length);
void NSBrokerPairingPakeDestroy(NSBrokerPairingPake *);
void NSBrokerPairingBufferDestroy(uint8_t *buffer, size_t length);
#endif
