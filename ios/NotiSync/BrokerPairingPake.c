#include "BrokerPairingPake.h"
#if __has_include(<OpenSSL/bn.h>)
#include <OpenSSL/bn.h>
#include <OpenSSL/crypto.h>
#include <OpenSSL/evp.h>
#else
#include <openssl/bn.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#endif
#include <stdlib.h>
#include <string.h>

// NIST 3072/256 group used by Bouncy Castle JPAKEPrimeOrderGroups.NIST_3072.
static const char *groupP = "90066455B5CFC38F9CAA4A48B4281F292C260FEEF01FD61037E56258A7795A1C7AD46076982CE6BB956936C6AB4DCFE05E6784586940CA544B9B2140E1EB523F009D20A7E7880E4E5BFA690F1B9004A27811CD9904AF70420EEFD6EA11EF7DA129F58835FF56B89FAA637BC9AC2EFAAB903402229F491D8D3485261CD068699B6BA58A1DDBBEF6DB51E8FE34E8A78E542D7BA351C21EA8D8F1D29F5D5D15939487E27F4416B0CA632C59EFD1B1EB66511A5A0FBF615B766C5862D0BD8A3FE7A0E0DA0FB2FE1FCB19E8F9996A8EA0FCCDE538175238FC8B0EE6F29AF7F642773EBE8CD5402415A01451A840476B2FCEB0E388D30D4B376C37FE401C2A2C2F941DAD179C540C1C8CE030D460C4D983BE9AB0B20F69144C1AE13F9383EA1C08504FB0BF321503EFE43488310DD8DC77EC5B8349B8BFE97C2C560EA878DE87C11E3D597F1FEA742D73EEC7F37BE43949EF1A0D15C3F3E3FC0A8335617055AC91328EC22B50FC15B941D3D1624CD88BC25F3E941FDDC6200689581BFEC416B4B2CB73";
static const char *groupQ = "CFA0478A54717B08CE64805B76E5B14249A77A4838469DF7F7DC987EFCCFB11D";
static const char *groupG = "5E5CBA992E0A680D885EB903AEA78E4A45A469103D448EDE3B7ACCC54D521E37F84A4BDD5B06B0970CC2D2BBB715F7B82846F9A0C393914C792E6A923E2117AB805276A975AADB5261D91673EA9AAFFEECBFA6183DFCB5D3B7332AA19275AFA1F8EC0B60FB6F66CC23AE4870791D5982AAD1AA9485FD8F4A60126FEB2CF05DB8A7F0F09B3397F3937F2E90B9E5B9C9B6EFEF642BC48351C46FB171B9BFA9EF17A961CE96C7E7A7CC3D3D03DFAD1078BA21DA425198F07D2481622BCE45969D9C4D6063D72AB7A0F08B2F49A7CC6AF335E08C4720E31476B67299E231F8BD90B39AC3AE3BE0C6B6CACEF8289A2E2873D58E51E029CAFBD55E6841489AB66B5B4B9BA6E2F784660896AFF387D92844CCB8B69475496DE19DA2E58259B090489AC8E62363CDF82CFD8EF2A427ABCD65750B506F56DDE3B988567A88126B914D7828E2B63A6D7ED0747EC59E0E0A23CE7D8A74C1D2C2A7AFB6A29799620F00E11C33787F7DED3B30E1A22D09F1FBDA1ABBBFBF25CAE05A13F812E34563F99410E73B";

struct NSBrokerPairingPake {
    BN_CTX *ctx;
    BIGNUM *p, *q, *g, *s, *x1, *x2, *gx1, *gx2, *gx3, *gx4;
    char *localID, *remoteID;
    int phase;
};

void NSBrokerPairingBufferDestroy(uint8_t *buffer, size_t length) {
    if (buffer) { OPENSSL_cleanse(buffer, length); free(buffer); }
}

void NSBrokerPairingPakeDestroy(NSBrokerPairingPake *v) {
    if (!v) return;
    BN_clear_free(v->p); BN_clear_free(v->q); BN_clear_free(v->g); BN_clear_free(v->s);
    BN_clear_free(v->x1); BN_clear_free(v->x2); BN_clear_free(v->gx1); BN_clear_free(v->gx2);
    BN_clear_free(v->gx3); BN_clear_free(v->gx4); BN_CTX_free(v->ctx);
    free(v->localID); free(v->remoteID); OPENSSL_cleanse(v, sizeof(*v)); free(v);
}

NSBrokerPairingPake *NSBrokerPairingPakeCreate(const char *localID, const char *remoteID,
                                             const uint8_t *secret, size_t secretLength) {
    if (!localID || !remoteID || strlen(localID) > 2048 || strlen(remoteID) > 2048 ||
        strcmp(localID, remoteID) == 0 || !secret || secretLength != 43) return NULL;
    NSBrokerPairingPake *v = calloc(1, sizeof(*v));
    if (!v) return NULL;
    v->ctx = BN_CTX_secure_new();
    v->localID = strdup(localID); v->remoteID = strdup(remoteID);
    v->s = BN_secure_new(); v->x1 = BN_secure_new(); v->x2 = BN_secure_new();
    v->gx1 = BN_new(); v->gx2 = BN_new();
    if (!v->ctx || !v->localID || !v->remoteID || !v->s || !v->x1 || !v->x2 || !v->gx1 || !v->gx2 ||
        !BN_hex2bn(&v->p, groupP) || !BN_hex2bn(&v->q, groupQ) || !BN_hex2bn(&v->g, groupG) ||
        !BN_bin2bn(secret, (int)secretLength, v->s) || !BN_nnmod(v->s, v->s, v->q, v->ctx) ||
        BN_is_zero(v->s)) { NSBrokerPairingPakeDestroy(v); return NULL; }
    BN_set_flags(v->s, BN_FLG_CONSTTIME);
    BN_set_flags(v->x1, BN_FLG_CONSTTIME); BN_set_flags(v->x2, BN_FLG_CONSTTIME);
    return v;
}

static void put32(uint8_t *out, size_t n) {
    out[0] = (uint8_t)(n >> 24); out[1] = (uint8_t)(n >> 16);
    out[2] = (uint8_t)(n >> 8); out[3] = (uint8_t)n;
}

static int pack(BIGNUM **numbers, size_t count, uint8_t **output, size_t *length) {
    size_t capacity = count * 389;
    uint8_t *buffer = calloc(capacity, 1);
    if (!buffer) return 0;
    size_t offset = 0;
    for (size_t i = 0; i < count; i++) {
        int n = BN_num_bytes(numbers[i]);
        size_t pad = n == 0 || BN_num_bits(numbers[i]) % 8 == 0 ? 1 : 0;
        size_t width = (size_t)n + pad;
        if (BN_is_negative(numbers[i]) || width > 385) { free(buffer); return 0; }
        put32(buffer + offset, width); offset += 4;
        if (n && BN_bn2bin(numbers[i], buffer + offset + pad) != n) { free(buffer); return 0; }
        offset += width;
    }
    *output = buffer; *length = offset; return 1;
}

static int unpack(NSBrokerPairingPake *v, const uint8_t *input, size_t length, BIGNUM **out, size_t count) {
    if (!input || length > count * 389) return 0;
    size_t offset = 0;
    for (size_t i = 0; i < count; i++) {
        if (length - offset < 4) return 0;
        size_t width = ((size_t)input[offset] << 24) | ((size_t)input[offset + 1] << 16) |
                       ((size_t)input[offset + 2] << 8) | input[offset + 3];
        offset += 4;
        if (width == 0 || width > 385 || width > length - offset || (input[offset] & 128) ||
            (width > 1 && input[offset] == 0 && (input[offset + 1] & 128) == 0)) return 0;
        out[i] = BN_bin2bn(input + offset, (int)width, NULL);
        if (!out[i] || BN_cmp(out[i], v->p) >= 0) return 0;
        offset += width;
    }
    return offset == length;
}

static int hashPart(EVP_MD_CTX *hash, const uint8_t *bytes, size_t length) {
    uint8_t prefix[4]; put32(prefix, length);
    return EVP_DigestUpdate(hash, prefix, 4) && EVP_DigestUpdate(hash, bytes, length);
}

// BC hashes length-prefixed unsigned integers, then interprets SHA-256 as a signed BigInteger.
static int challenge(NSBrokerPairingPake *v, BIGNUM *out, const BIGNUM *base,
                     const BIGNUM *commitment, const BIGNUM *gx, const char *id) {
    EVP_MD_CTX *hash = EVP_MD_CTX_new();
    uint8_t bytes[384], digest[32]; unsigned int digestLength = 0;
    int ok = hash && EVP_DigestInit_ex(hash, EVP_sha256(), NULL);
    const BIGNUM *numbers[] = {base, commitment, gx};
    for (size_t i = 0; ok && i < 3; i++) {
        int n = BN_bn2bin(numbers[i], bytes);
        if (n == 0) { bytes[0] = 0; n = 1; }
        ok = hashPart(hash, bytes, (size_t)n);
    }
    ok = ok && hashPart(hash, (const uint8_t *)id, strlen(id)) &&
         EVP_DigestFinal_ex(hash, digest, &digestLength) && digestLength == 32 &&
         BN_bin2bn(digest, 32, out) != NULL;
    if (ok && (digest[0] & 128)) {
        BN_CTX_start(v->ctx);
        BIGNUM *sign = BN_CTX_get(v->ctx);
        ok = sign && BN_one(sign) && BN_lshift(sign, sign, 256) && BN_sub(out, out, sign);
        BN_CTX_end(v->ctx);
    }
    ok = ok && BN_nnmod(out, out, v->q, v->ctx);
    EVP_MD_CTX_free(hash); OPENSSL_cleanse(digest, sizeof(digest)); return ok;
}

static int secretPower(NSBrokerPairingPake *v, BIGNUM *out, const BIGNUM *base, const BIGNUM *exponent) {
    return BN_mod_exp_mont_consttime(out, base, exponent, v->p, v->ctx, NULL);
}

static int prove(NSBrokerPairingPake *v, const BIGNUM *base, const BIGNUM *gx,
                 const BIGNUM *exponent, BIGNUM *commitment, BIGNUM *response) {
    BN_CTX_start(v->ctx);
    BIGNUM *nonce = BN_CTX_get(v->ctx), *h = BN_CTX_get(v->ctx), *product = BN_CTX_get(v->ctx);
    int ok = product && BN_priv_rand_range(nonce, v->q) && secretPower(v, commitment, base, nonce) &&
             challenge(v, h, base, commitment, gx, v->localID) &&
             BN_mod_mul(product, exponent, h, v->q, v->ctx) &&
             BN_mod_sub(response, nonce, product, v->q, v->ctx);
    BN_CTX_end(v->ctx); return ok;
}

static int verify(NSBrokerPairingPake *v, const BIGNUM *base, const BIGNUM *gx,
                  const BIGNUM *commitment, const BIGNUM *response) {
    if (BN_is_zero(gx) || BN_cmp(gx, v->p) >= 0 || BN_is_zero(commitment) ||
        BN_cmp(response, v->q) >= 0) return 0;
    BN_CTX_start(v->ctx);
    BIGNUM *h = BN_CTX_get(v->ctx), *left = BN_CTX_get(v->ctx), *right = BN_CTX_get(v->ctx);
    int ok = right && BN_mod_exp(left, gx, v->q, v->p, v->ctx) && BN_is_one(left) &&
             challenge(v, h, base, commitment, gx, v->remoteID) &&
             BN_mod_exp(left, base, response, v->p, v->ctx) &&
             BN_mod_exp(right, gx, h, v->p, v->ctx) &&
             BN_mod_mul(left, left, right, v->p, v->ctx) && BN_cmp(left, commitment) == 0;
    BN_CTX_end(v->ctx); return ok;
}

int NSBrokerPairingPakeRound1(NSBrokerPairingPake *v, uint8_t **output, size_t *length) {
    if (!v || !output || !length || v->phase != 0) return 0;
    v->phase = -1;
    BN_CTX_start(v->ctx);
    BIGNUM *a = BN_CTX_get(v->ctx), *b = BN_CTX_get(v->ctx), *c = BN_CTX_get(v->ctx), *d = BN_CTX_get(v->ctx);
    int ok = d && BN_priv_rand_range(v->x1, v->q);
    do { if (!ok || !BN_priv_rand_range(v->x2, v->q)) { ok = 0; break; } } while (BN_is_zero(v->x2));
    ok = ok && secretPower(v, v->gx1, v->g, v->x1) && secretPower(v, v->gx2, v->g, v->x2) &&
         prove(v, v->g, v->gx1, v->x1, a, b) && prove(v, v->g, v->gx2, v->x2, c, d);
    BIGNUM *values[] = {v->gx1, v->gx2, a, b, c, d};
    ok = ok && pack(values, 6, output, length);
    BN_CTX_end(v->ctx); if (ok) v->phase = 1; return ok;
}

int NSBrokerPairingPakeRound2(NSBrokerPairingPake *v, const uint8_t *input, size_t inputLength,
                            uint8_t **output, size_t *length) {
    if (!v || !output || !length || v->phase != 1) return 0;
    v->phase = -1;
    BIGNUM *remote[6] = {0};
    BN_CTX_start(v->ctx);
    BIGNUM *base = BN_CTX_get(v->ctx), *exponent = BN_CTX_get(v->ctx), *a = BN_CTX_get(v->ctx);
    BIGNUM *commitment = BN_CTX_get(v->ctx), *response = BN_CTX_get(v->ctx);
    int ok = response && unpack(v, input, inputLength, remote, 6) && !BN_is_one(remote[1]) &&
             verify(v, v->g, remote[0], remote[2], remote[3]) && verify(v, v->g, remote[1], remote[4], remote[5]);
    if (ok) {
        v->gx3 = BN_dup(remote[0]); v->gx4 = BN_dup(remote[1]);
        ok = v->gx3 && v->gx4 && BN_mod_mul(base, v->gx1, v->gx3, v->p, v->ctx) &&
             BN_mod_mul(base, base, v->gx4, v->p, v->ctx) && !BN_is_one(base) &&
             BN_mod_mul(exponent, v->x2, v->s, v->q, v->ctx) && secretPower(v, a, base, exponent) &&
             prove(v, base, a, exponent, commitment, response);
    }
    BIGNUM *values[] = {a, commitment, response};
    ok = ok && pack(values, 3, output, length);
    for (size_t i = 0; i < 6; i++) BN_clear_free(remote[i]);
    BN_CTX_end(v->ctx); if (ok) v->phase = 2; return ok;
}

int NSBrokerPairingPakeKey(NSBrokerPairingPake *v, const uint8_t *input, size_t inputLength,
                         uint8_t **output, size_t *length) {
    if (!v || !output || !length || v->phase != 2) return 0;
    v->phase = -1;
    BIGNUM *remote[3] = {0};
    BN_CTX_start(v->ctx);
    BIGNUM *base = BN_CTX_get(v->ctx), *exponent = BN_CTX_get(v->ctx), *key = BN_CTX_get(v->ctx);
    int ok = key && unpack(v, input, inputLength, remote, 3) &&
             BN_mod_mul(base, v->gx3, v->gx1, v->p, v->ctx) &&
             BN_mod_mul(base, base, v->gx2, v->p, v->ctx) && !BN_is_one(base) &&
             verify(v, base, remote[0], remote[1], remote[2]) &&
             BN_mod_mul(exponent, v->x2, v->s, v->q, v->ctx) && BN_sub(exponent, v->q, exponent) &&
             secretPower(v, key, v->gx4, exponent) && BN_mod_mul(key, key, remote[0], v->p, v->ctx) &&
             secretPower(v, key, key, v->x2);
    BIGNUM *values[] = {key};
    ok = ok && pack(values, 1, output, length);
    for (size_t i = 0; i < 3; i++) BN_clear_free(remote[i]);
    BN_clear(v->s); BN_clear(v->x1); BN_clear(v->x2);
    BN_CTX_end(v->ctx); return ok;
}
