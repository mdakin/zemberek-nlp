/// Bit-exact port of `zemberek.normalization.deasciifier.latticedeasciifier.FeatureHash`.
pub const GOLDEN_GAMMA: u64 = 0x9E37_79B9_7F4A_7C15;

#[inline]
pub const fn mix64(mut z: u64) -> u64 {
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

#[inline]
pub const fn hash_of1(namespace: i32, a: i64) -> u64 {
    let ns = (namespace as i64 as u64).wrapping_mul(GOLDEN_GAMMA);
    mix64(ns ^ mix64(a as u64))
}

#[inline]
pub const fn hash_of2(namespace: i32, a: i64, b: i64) -> u64 {
    let ns = (namespace as i64 as u64).wrapping_mul(GOLDEN_GAMMA);
    let h = mix64(ns ^ (a as u64));
    mix64(h ^ (b as u64).wrapping_mul(GOLDEN_GAMMA))
}

#[inline]
pub const fn hash_of3(namespace: i32, a: i64, b: i64, c: i64) -> u64 {
    let ns = (namespace as i64 as u64).wrapping_mul(GOLDEN_GAMMA);
    let mut h = mix64(ns ^ (a as u64));
    h = mix64(h ^ (b as u64).wrapping_mul(GOLDEN_GAMMA));
    mix64(h ^ (c as u64))
}

#[inline]
pub fn hash_str(text: &str) -> u64 {
    let mut h: u64 = 0xCBF2_9CE4_8422_2325;
    if text.is_ascii() {
        for &b in text.as_bytes() {
            h = (h ^ (b as u64)).wrapping_mul(0x0100_0000_01B3);
        }
    } else {
        for u in text.encode_utf16() {
            h = (h ^ (u as u64)).wrapping_mul(0x0100_0000_01B3);
        }
    }
    mix64(h)
}

#[inline]
pub fn hash_prefix(text: &str, max_chars: usize) -> u64 {
    let mut h: u64 = 0xCBF2_9CE4_8422_2325;
    if text.is_ascii() {
        let lim = text.len().min(max_chars);
        for &b in &text.as_bytes()[..lim] {
            h = (h ^ (b as u64)).wrapping_mul(0x0100_0000_01B3);
        }
    } else {
        for u in text.encode_utf16().take(max_chars) {
            h = (h ^ (u as u64)).wrapping_mul(0x0100_0000_01B3);
        }
    }
    mix64(h)
}

#[inline]
pub fn hash_suffix(text: &str, max_chars: usize) -> u64 {
    let mut h: u64 = 0xCBF2_9CE4_8422_2325;
    if text.is_ascii() {
        let len = text.len();
        let start = len.saturating_sub(max_chars);
        for &b in &text.as_bytes()[start..len] {
            h = (h ^ (b as u64)).wrapping_mul(0x0100_0000_01B3);
        }
    } else {
        let mut units = [0u16; 64];
        let mut n = 0usize;
        for u in text.encode_utf16() {
            if n < units.len() {
                units[n] = u;
                n += 1;
            }
        }
        let start = n.saturating_sub(max_chars);
        for &u in &units[start..n] {
            h = (h ^ (u as u64)).wrapping_mul(0x0100_0000_01B3);
        }
    }
    mix64(h)
}

/// Exact port of Java `Math.round(float)` (rounds half towards `+∞`, e.g. `-0.5f -> 0`).
#[inline]
pub fn java_math_round(f: f32) -> i32 {
    if f.is_nan() {
        return 0;
    }
    if f.to_bits() == 0x3eff_ffff {
        return 0;
    }
    if f <= (i32::MIN as f32) {
        return i32::MIN;
    }
    if f >= (i32::MAX as f32) {
        return i32::MAX;
    }
    let x = f + 0.5;
    let t = x as i32;
    if (t as f32) > x {
        t - 1
    } else {
        t
    }
}
