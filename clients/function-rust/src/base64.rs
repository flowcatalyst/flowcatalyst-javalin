//! Standard (RFC 4648, padded) base64 — the same alphabet Java's
//! `java.util.Base64.getEncoder()/getDecoder()` uses to build/read `bodyBase64`
//! (`WasmAbi`). Hand-rolled rather than pulled from a crate: the JS library's
//! `base64.ts` doc explains why a guest library stays dependency-free — JS gets
//! `atob`/`btoa` from the host's own prelude, but nothing equivalent exists for
//! Rust on `wasm32-unknown-unknown`, and pulling in the `base64` crate would be
//! the one dependency this crate's own doc (`docs/spec/function-rust-guest.md`
//! §1) rules out.

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b0 = chunk[0];
        let b1 = *chunk.get(1).unwrap_or(&0);
        let b2 = *chunk.get(2).unwrap_or(&0);
        let n = ((b0 as u32) << 16) | ((b1 as u32) << 8) | (b2 as u32);
        out.push(ALPHABET[((n >> 18) & 0x3f) as usize] as char);
        out.push(ALPHABET[((n >> 12) & 0x3f) as usize] as char);
        out.push(if chunk.len() > 1 { ALPHABET[((n >> 6) & 0x3f) as usize] as char } else { '=' });
        out.push(if chunk.len() > 2 { ALPHABET[(n & 0x3f) as usize] as char } else { '=' });
    }
    out
}

/// Decodes standard base64 (padded, `+`/`/`). `Err` names what was wrong —
/// never partial output, matching Java's `Base64.getDecoder().decode`
/// throwing `IllegalArgumentException` on malformed input rather than
/// returning a best-effort prefix.
pub fn decode(text: &str) -> Result<Vec<u8>, String> {
    let cleaned: &str = text.trim_end_matches('=');
    if cleaned.len() != text.len() && (text.len() - cleaned.len()) > 2 {
        return Err("bodyBase64 is not base64: too much padding".to_string());
    }
    if !cleaned.is_ascii() {
        return Err("bodyBase64 is not base64: non-ASCII input".to_string());
    }
    let mut out = Vec::with_capacity(cleaned.len() * 3 / 4 + 3);
    let mut bits: u32 = 0;
    let mut nbits: u32 = 0;
    for c in cleaned.bytes() {
        let v = decode_char(c).ok_or_else(|| format!("bodyBase64 is not base64: invalid character '{}'", c as char))?;
        bits = (bits << 6) | v as u32;
        nbits += 6;
        if nbits >= 8 {
            nbits -= 8;
            out.push(((bits >> nbits) & 0xff) as u8);
        }
    }
    Ok(out)
}

fn decode_char(c: u8) -> Option<u8> {
    match c {
        b'A'..=b'Z' => Some(c - b'A'),
        b'a'..=b'z' => Some(c - b'a' + 26),
        b'0'..=b'9' => Some(c - b'0' + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_arbitrary_bytes() {
        for input in [
            &b""[..],
            b"f",
            b"fo",
            b"foo",
            b"foob",
            b"fooba",
            b"foobar",
            "héllo body".as_bytes(),
            &[0u8, 1, 2, 255, 254, 128, 127],
        ] {
            let encoded = encode(input);
            assert_eq!(decode(&encoded).unwrap(), input, "round trip for {input:?}");
        }
    }

    // mutant: use the wrong alphabet (e.g. URL-safe `-_`) — the fixture below is Java's own
    // Base64.getEncoder() output for "héllo body" (WasmAbi's convention), so a wrong alphabet
    // fails this exact byte-for-byte comparison, not just the round trip above.
    #[test]
    fn matches_javas_standard_base64_encoding() {
        assert_eq!(encode("héllo body".as_bytes()), "aMOpbGxvIGJvZHk=");
        assert_eq!(decode("aMOpbGxvIGJvZHk=").unwrap(), "héllo body".as_bytes());
    }

    #[test]
    fn empty_input_round_trips_to_empty() {
        assert_eq!(encode(&[]), "");
        assert_eq!(decode("").unwrap(), Vec::<u8>::new());
    }

    // mutant: skip validating the character set — garbage in produces garbage out with no error.
    #[test]
    fn rejects_invalid_characters() {
        assert!(decode("not valid base64!!").is_err());
    }
}
