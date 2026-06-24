//! SDK-side request signing (scheme A, api_key as the HMAC key).
//!
//! Must be **byte-for-byte identical** to the server
//! (`io.github.pinpols.batch.common.security.RequestSignatures`) and the Java
//! SDK (`io.github.pinpols.batch.sdk.internal.RequestSigner`) — the contract is
//! pinned by the golden-vector conformance test below.
//!
//! ```text
//!   canonical = UPPER(method) "\n" path "\n" timestamp "\n" nonce "\n" hex(sha256(body))
//!   signature = hex(hmacSha256(apiKey, canonical))   // lowercase hex
//! ```
//!
//! Opt-in: the [`ReqwestTransport`](super::reqwest_transport::ReqwestTransport)
//! attaches the three signature headers only when request signing is enabled
//! **and** an api key is configured. Headers (mirroring Java):
//! * `X-Batch-Timestamp` — epoch milliseconds, as a decimal string.
//! * `X-Batch-Nonce` — random per request (uuid-shaped).
//! * `X-Batch-Signature` — the lowercase-hex HMAC.
//!
//! **Zero external dependencies**: the core crate is std-only (mirroring the
//! Kafka/HTTP feature gating), so SHA-256 and HMAC-SHA256 are implemented here
//! against std rather than pulling `sha2`/`hmac`/`hex`. The implementation is
//! pinned by the golden vectors, so any drift fails the test loudly.

/// `hex(sha256(body))` — lowercase hex of the SHA-256 digest of the raw bytes.
/// An empty body still hashes (the SHA-256 of zero bytes).
pub fn body_sha256_hex(body: &[u8]) -> String {
    to_hex(&sha256(body))
}

/// Build the canonical string per the contract. `method` is upper-cased; all
/// fields are newline-joined; the last field is `hex(sha256(body))`.
pub fn canonical_string(
    method: &str,
    path: &str,
    timestamp: &str,
    nonce: &str,
    body: &[u8],
) -> String {
    format!(
        "{}\n{}\n{}\n{}\n{}",
        method.to_uppercase(),
        path,
        timestamp,
        nonce,
        body_sha256_hex(body),
    )
}

/// `signature = hex(hmacSha256(apiKey, canonical))` — lowercase hex.
pub fn sign(
    api_key: &str,
    method: &str,
    path: &str,
    timestamp: &str,
    nonce: &str,
    body: &[u8],
) -> String {
    let canonical = canonical_string(method, path, timestamp, nonce, body);
    to_hex(&hmac_sha256(api_key.as_bytes(), canonical.as_bytes()))
}

// ---------------------------------------------------------------------------
// hex
// ---------------------------------------------------------------------------

fn to_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

// ---------------------------------------------------------------------------
// SHA-256 (FIPS 180-4), std-only.
// ---------------------------------------------------------------------------

const SHA256_K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

const SHA256_BLOCK: usize = 64;
const SHA256_DIGEST: usize = 32;

fn sha256(message: &[u8]) -> [u8; SHA256_DIGEST] {
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];

    // Pad: 0x80, then zeros, then 64-bit big-endian bit length.
    let bit_len = (message.len() as u64).wrapping_mul(8);
    let mut padded = message.to_vec();
    padded.push(0x80);
    while padded.len() % SHA256_BLOCK != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&bit_len.to_be_bytes());

    for chunk in padded.chunks_exact(SHA256_BLOCK) {
        let mut w = [0u32; 64];
        for (i, word) in chunk.chunks_exact(4).enumerate() {
            w[i] = u32::from_be_bytes([word[0], word[1], word[2], word[3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }

        let mut a = h[0];
        let mut b = h[1];
        let mut c = h[2];
        let mut d = h[3];
        let mut e = h[4];
        let mut f = h[5];
        let mut g = h[6];
        let mut hh = h[7];

        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(SHA256_K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);

            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }

        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }

    let mut out = [0u8; SHA256_DIGEST];
    for (i, word) in h.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&word.to_be_bytes());
    }
    out
}

// ---------------------------------------------------------------------------
// HMAC-SHA256 (RFC 2104), std-only.
// ---------------------------------------------------------------------------

fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; SHA256_DIGEST] {
    // Keys longer than the block size are hashed first.
    let mut block_key = [0u8; SHA256_BLOCK];
    if key.len() > SHA256_BLOCK {
        block_key[..SHA256_DIGEST].copy_from_slice(&sha256(key));
    } else {
        block_key[..key.len()].copy_from_slice(key);
    }

    let mut i_pad = [0u8; SHA256_BLOCK];
    let mut o_pad = [0u8; SHA256_BLOCK];
    for i in 0..SHA256_BLOCK {
        i_pad[i] = block_key[i] ^ 0x36;
        o_pad[i] = block_key[i] ^ 0x5c;
    }

    let mut inner = Vec::with_capacity(SHA256_BLOCK + message.len());
    inner.extend_from_slice(&i_pad);
    inner.extend_from_slice(message);
    let inner_hash = sha256(&inner);

    let mut outer = Vec::with_capacity(SHA256_BLOCK + SHA256_DIGEST);
    outer.extend_from_slice(&o_pad);
    outer.extend_from_slice(&inner_hash);
    sha256(&outer)
}

#[cfg(test)]
mod tests {
    use super::*;

    // Golden vectors — must match the server (`RequestSignatures`) and the Java
    // SDK (`RequestSigner`) byte-for-byte. Do NOT edit these values to make a
    // test pass: they are the cross-language contract.
    const GOLDEN_API_KEY: &str = "golden-key";
    const GOLDEN_METHOD: &str = "POST";
    const GOLDEN_PATH: &str = "/internal/tasks/42/report";
    const GOLDEN_TIMESTAMP: &str = "1700000000000";
    const GOLDEN_NONCE: &str = "golden-nonce";
    const GOLDEN_BODY: &str = r#"{"tenantId":"t1","success":true}"#;

    const GOLDEN_BODY_SHA256: &str =
        "c9a04b2061b2c381193ee868b9d89bc16979c738d257f8495d18457a83462dd5";
    const GOLDEN_SIGNATURE: &str =
        "287108832407aec1bc689c97ac22037b7114b2702671dfb20d1aacc6edeb0898";
    const EMPTY_BODY_SHA256: &str =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    #[test]
    fn signing_body_sha256_matches_golden() {
        assert_eq!(body_sha256_hex(GOLDEN_BODY.as_bytes()), GOLDEN_BODY_SHA256);
    }

    #[test]
    fn signing_empty_body_sha256_matches_golden() {
        assert_eq!(body_sha256_hex(b""), EMPTY_BODY_SHA256);
        assert_eq!(body_sha256_hex(&[]), EMPTY_BODY_SHA256);
    }

    #[test]
    fn signing_signature_matches_golden() {
        let sig = sign(
            GOLDEN_API_KEY,
            GOLDEN_METHOD,
            GOLDEN_PATH,
            GOLDEN_TIMESTAMP,
            GOLDEN_NONCE,
            GOLDEN_BODY.as_bytes(),
        );
        assert_eq!(sig, GOLDEN_SIGNATURE);
    }

    #[test]
    fn signing_canonical_string_shape() {
        let canonical = canonical_string(
            "post", // lower-cased on input → must be upper-cased in canonical
            GOLDEN_PATH,
            GOLDEN_TIMESTAMP,
            GOLDEN_NONCE,
            GOLDEN_BODY.as_bytes(),
        );
        let expected = format!(
            "POST\n{}\n{}\n{}\n{}",
            GOLDEN_PATH, GOLDEN_TIMESTAMP, GOLDEN_NONCE, GOLDEN_BODY_SHA256
        );
        assert_eq!(canonical, expected);
    }

    #[test]
    fn signing_method_is_upper_cased() {
        let upper = sign(
            GOLDEN_API_KEY,
            "post",
            GOLDEN_PATH,
            GOLDEN_TIMESTAMP,
            GOLDEN_NONCE,
            GOLDEN_BODY.as_bytes(),
        );
        assert_eq!(upper, GOLDEN_SIGNATURE);
    }
}
