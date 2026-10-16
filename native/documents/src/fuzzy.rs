//! Filename search uses nucleo's matching rules; input and reusable workspace are bounded.
use crate::Buffer;
use nucleo_matcher::{
    pattern::{CaseMatching, Normalization, Pattern},
    Config, Matcher, Utf32Str,
};
use std::{
    ffi::c_void,
    panic::{catch_unwind, AssertUnwindSafe},
    sync::{Mutex, OnceLock},
};

struct State {
    matcher: Matcher,
    pattern: Pattern,
    query: String,
    chars: Vec<char>,
}
static STATE: OnceLock<Mutex<State>> = OnceLock::new();

fn matches(input: &[u8], query: &str, cancelled: &mut dyn FnMut() -> bool) -> Option<Vec<u8>> {
    if input.len() > 132 * 1024 || query.encode_utf16().count() > 128 || cancelled() {
        return None;
    }
    let mut input = input;
    fn short(input: &mut &[u8]) -> Option<usize> {
        let value = u16::from_be_bytes(input.get(..2)?.try_into().ok()?) as usize;
        *input = &input[2..];
        Some(value)
    }
    let count = short(&mut input)?;
    if count > 128 {
        return None;
    }
    let mut guard = STATE
        .get_or_init(|| {
            Mutex::new(State {
                matcher: Matcher::new(Config::DEFAULT),
                pattern: Pattern::default(),
                query: String::new(),
                chars: Vec::with_capacity(256),
            })
        })
        .lock()
        .ok()?;
    let State {
        matcher,
        pattern,
        query: previous,
        chars,
    } = &mut *guard;
    if previous != query {
        *pattern = Pattern::parse(query, CaseMatching::Ignore, Normalization::Smart);
        previous.clear();
        previous.push_str(query);
    }
    let mut output = Vec::with_capacity(count);
    for _ in 0..count {
        if cancelled() {
            return None;
        }
        let size = short(&mut input)?;
        if size > 1024 {
            return None;
        }
        let name = std::str::from_utf8(input.get(..size)?).ok()?;
        if name.encode_utf16().count() > 256 {
            return None;
        }
        input = &input[size..];
        output.push(u8::from(
            pattern.score(Utf32Str::new(name, chars), matcher).is_some(),
        ));
    }
    input.is_empty().then_some(output)
}

// The same checked C/JNI boundary as document parsing; matching itself is safe Rust.
#[no_mangle]
pub unsafe extern "C" fn alldoc_fuzzy_match(
    input: *const u8,
    len: usize,
    query: *const u16,
    nlen: usize,
    opaque: *mut c_void,
    poll: extern "C" fn(*mut c_void) -> i32,
) -> Buffer {
    let mut output = catch_unwind(AssertUnwindSafe(|| {
        if len > 132 * 1024 || nlen > 128 {
            return None;
        }
        let query = String::from_utf16(std::slice::from_raw_parts(query, nlen)).ok()?;
        matches(std::slice::from_raw_parts(input, len), &query, &mut || {
            poll(opaque) != 0
        })
    }))
    .ok()
    .flatten()
    .unwrap_or_default();
    let answer = Buffer {
        ptr: output.as_mut_ptr(),
        len: output.len(),
        capacity: output.capacity(),
    };
    std::mem::forget(output);
    answer
}
extern "C" {
    fn alldoc_fuzzy_jni(
        env: *mut c_void,
        cls: *mut c_void,
        input: *mut c_void,
        query: *mut c_void,
        cancel: *mut c_void,
    ) -> *mut c_void;
}
#[no_mangle]
pub unsafe extern "C" fn Java_app_smallthingz_alldoc_core_NativeFuzzy_matchesNative(
    env: *mut c_void,
    cls: *mut c_void,
    input: *mut c_void,
    query: *mut c_void,
    cancel: *mut c_void,
) -> *mut c_void {
    alldoc_fuzzy_jni(env, cls, input, query, cancel)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn wire(names: &[&str]) -> Vec<u8> {
        let mut out = (names.len() as u16).to_be_bytes().to_vec();
        for name in names {
            out.extend_from_slice(&(name.len() as u16).to_be_bytes());
            out.extend_from_slice(name.as_bytes());
        }
        out
    }
    #[test]
    fn filenames_use_unicode_fuzzy_and_word_filters() {
        let input = wire(&[
            "Quarterly Report.pdf",
            "Résumé.txt",
            "photo.png",
            "report-final.pdf",
        ]);
        assert_eq!(
            matches(&input, "qrp", &mut || false),
            Some(vec![1, 0, 0, 0])
        );
        assert_eq!(
            matches(&input, "resume", &mut || false),
            Some(vec![0, 1, 0, 0])
        );
        assert_eq!(
            matches(&input, "report !final", &mut || false),
            Some(vec![1, 0, 0, 0])
        );
        assert_eq!(
            matches(&wire(&["नमस्ते.txt", "file.txt"]), "नम", &mut || false),
            Some(vec![1, 0])
        );
    }
    #[test]
    fn cancellation_and_malformed_batches_are_rejected() {
        assert!(matches(&wire(&["a"]), "a", &mut || true).is_none());
        for input in [
            vec![],
            vec![0],
            vec![0, 129],
            vec![0, 1, 0, 2, b'a'],
            vec![0, 1, 0, 1, 255],
            vec![0, 0, 0],
        ] {
            assert!(matches(&input, "a", &mut || false).is_none());
        }
        assert!(matches(&wire(&[&"a".repeat(257)]), "a", &mut || false).is_none());
        assert!(matches(&wire(&["a"]), &"a".repeat(129), &mut || false).is_none());
    }
}
