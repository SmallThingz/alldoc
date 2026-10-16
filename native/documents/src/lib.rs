//! Bounded read-only semantic previews; no file, network, font or macro execution.
mod fuzzy;
mod binary_ppt;
mod binary_word;
mod epub;
mod legacy;
mod odf;
mod office;
mod package;
mod rtf;
mod xml;
use std::{
    ffi::c_void,
    panic::{catch_unwind, AssertUnwindSafe},
};

pub const INPUT: usize = 16 * 1024 * 1024;
pub const TEXT: usize = 512 * 1024;
pub type Result<T> = std::result::Result<T, Error>;
#[derive(Debug)]
pub struct Error {
    pub code: &'static str,
    pub message: &'static str,
}
pub fn bad(message: &'static str) -> Error {
    Error {
        code: "MALFORMED",
        message,
    }
}
pub fn limit(message: &'static str) -> Error {
    Error {
        code: "LIMIT",
        message,
    }
}
pub fn unsupported(message: &'static str) -> Error {
    Error {
        code: "UNSUPPORTED",
        message,
    }
}
pub fn encrypted() -> Error {
    Error {
        code: "ENCRYPTED",
        message: "Encrypted Office preview is unsupported",
    }
}

pub struct Context<'a> {
    cancel: &'a mut dyn FnMut() -> bool,
    events: usize,
    inflated: usize,
}
impl<'a> Context<'a> {
    pub fn new(cancel: &'a mut dyn FnMut() -> bool) -> Self {
        Self {
            cancel,
            events: 0,
            inflated: 0,
        }
    }
    pub fn check(&mut self) -> Result<()> {
        if (self.cancel)() {
            return Err(Error {
                code: "CANCELLED",
                message: "Document preview cancelled",
            });
        }
        Ok(())
    }
    pub fn tick(&mut self) -> Result<()> {
        self.events += 1;
        if self.events > 200_000 {
            return Err(limit("Document parse work limit exceeded"));
        }
        if self.events % 64 == 0 {
            self.check()?;
        }
        Ok(())
    }
    pub fn inflate(&mut self, size: usize) -> Result<()> {
        self.inflated += size;
        if self.inflated > 32 * 1024 * 1024 {
            return Err(limit("Document expanded data limit exceeded"));
        }
        self.check()
    }
}

#[derive(Debug)]
pub struct Document {
    pub format: String,
    pub truncated: bool,
    pub warnings: Vec<String>,
    pub sections: Vec<Section>,
    bytes: usize,
    blocks: usize,
}
#[derive(Debug)]
pub struct Section {
    pub title: String,
    pub kind: &'static str,
    pub blocks: Vec<Block>,
}
#[derive(Debug)]
pub struct Block {
    pub kind: &'static str,
    pub text: String,
    pub row: i32,
    pub column: i32,
    pub level: i32,
}
fn prefix(s: &str, max: usize) -> &str {
    let mut n = s.len().min(max);
    while !s.is_char_boundary(n) {
        n -= 1;
    }
    &s[..n]
}
impl Document {
    pub fn full(&self) -> bool {
        self.blocks >= 4096 || self.bytes >= TEXT
    }
    pub fn new(format: &str) -> Self {
        Self { format: format.into(), truncated: false,
        warnings: vec!["Text and structure preview; page layout, drawings and embedded objects are omitted.".into()],
        sections: vec![], bytes: 0, blocks: 0 }
    }
    pub fn warn(&mut self, text: &str) {
        if self.warnings.len() < 32 && !self.warnings.iter().any(|w| w == text) {
            self.warnings.push(text.into());
        }
    }
    pub fn section(&mut self, title: &str, kind: &'static str) -> Option<usize> {
        if self.sections.len() == 64 {
            self.truncated = true;
            return None;
        }
        if title.len() > 1024 {
            self.truncated = true;
        }
        self.sections.push(Section {
            title: prefix(title, 1024).into(),
            kind,
            blocks: vec![],
        });
        Some(self.sections.len() - 1)
    }
    pub fn push(
        &mut self,
        section: Option<usize>,
        kind: &'static str,
        text: &str,
        row: i32,
        column: i32,
        level: i32,
    ) {
        let Some(section) = section else {
            self.truncated = true;
            return;
        };
        if self.blocks == 4096 || self.bytes == TEXT {
            self.truncated = true;
            return;
        }
        let part = prefix(text, TEXT - self.bytes);
        if part.len() != text.len() {
            self.truncated = true;
        }
        self.bytes += part.len();
        self.blocks += 1;
        self.sections[section].blocks.push(Block {
            kind,
            text: part.into(),
            row,
            column,
            level,
        });
    }
    pub fn paragraph(&mut self, section: Option<usize>, text: &str) {
        self.push(section, "paragraph", text, -1, -1, 0);
    }
}
// Each contiguous string keeps a prefix after its first omitted scalar.
#[derive(Default)]
pub(crate) struct TextBuffer {
    text: String,
    exhausted: bool,
}
impl TextBuffer {
    pub(crate) fn as_str(&self) -> &str {
        &self.text
    }
    pub(crate) fn clear(&mut self) {
        self.text.clear();
        self.exhausted = false;
    }
    fn append_limit(&mut self, text: &str, limit: usize, truncated: &mut bool) {
        if self.exhausted {
            *truncated |= !text.is_empty();
            return;
        }
        let part = prefix(text, limit.saturating_sub(self.text.len()));
        self.exhausted = part.len() != text.len();
        *truncated |= self.exhausted;
        self.text.push_str(part);
    }
    pub(crate) fn append_buffer(&mut self, source: &Self, truncated: &mut bool) {
        append(self, source.as_str(), truncated);
        self.exhausted |= source.exhausted;
        *truncated |= self.exhausted;
    }
}
impl std::ops::Deref for TextBuffer {
    type Target = str;
    fn deref(&self) -> &str {
        self.as_str()
    }
}
pub(crate) fn append(to: &mut TextBuffer, s: &str, truncated: &mut bool) {
    to.append_limit(s, TEXT, truncated);
}
pub fn utf16(bytes: &[u8]) -> Result<String> {
    if bytes.len() % 2 != 0 {
        return Err(bad("Truncated UTF-16 text"));
    }
    String::from_utf16(
        &bytes
            .chunks_exact(2)
            .map(|b| u16::from_le_bytes([b[0], b[1]]))
            .collect::<Vec<_>>(),
    )
    .map_err(|_| bad("Malformed UTF-16 text"))
}
pub fn preview(input: &[u8], _name: &str, cancel: &mut dyn FnMut() -> bool) -> Result<Document> {
    if input.len() > INPUT {
        return Err(limit("Document input exceeds 16 MiB"));
    }
    let mut cx = Context::new(cancel);
    cx.check()?;
    let result = if input.starts_with(b"PK\x03\x04") || input.starts_with(b"PK\x05\x06") {
        let mut package = package::Package::open(input, &mut cx)?;
        if package.has("META-INF/container.xml") {
            epub::parse(&mut package, &mut cx)
        } else if package.has("word/document.xml") {
            office::word(&mut package, &mut cx)
        } else if package.has("xl/workbook.xml") {
            office::workbook(&mut package, &mut cx)
        } else if package.has("ppt/presentation.xml") {
            office::presentation(&mut package, &mut cx)
        } else if package.has("content.xml") {
            let bytes = package.read("content.xml", &mut cx)?;
            odf::parse(&bytes, &mut cx)
        } else {
            Err(unsupported(
                "ZIP package is not a supported Office document",
            ))
        }
    } else if input.starts_with(b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1") {
        legacy::parse(input, &mut cx)
    } else if input.starts_with(b"{\\rtf") {
        rtf::parse(input, &mut cx)
    } else if input.starts_with(b"\xef\xbb\xbf")
        || input.trim_ascii_start().starts_with(b"<")
        || input.starts_with(b"\xff\xfe")
        || input.starts_with(b"\xfe\xff")
    {
        odf::parse(input, &mut cx)
    } else {
        Err(unsupported("Unsupported document content"))
    }?;
    cx.check()?;
    Ok(result)
}

fn put_int(out: &mut Vec<u8>, n: i32) {
    out.extend_from_slice(&n.to_be_bytes());
}
fn put_string(out: &mut Vec<u8>, s: &str) {
    put_int(out, s.len() as i32);
    out.extend_from_slice(s.as_bytes());
}
pub fn encode(result: Result<Document>) -> Vec<u8> {
    let mut out = b"ZFD1".to_vec();
    match result {
        Err(e) => {
            out.push(1);
            put_string(&mut out, e.code);
            put_string(&mut out, e.message);
        }
        Ok(d) => {
            out.push(0);
            put_string(&mut out, &d.format);
            out.push(d.truncated as u8);
            put_int(&mut out, d.warnings.len() as i32);
            for w in d.warnings {
                put_string(&mut out, &w);
            }
            put_int(&mut out, d.sections.len() as i32);
            for s in d.sections {
                put_string(&mut out, &s.title);
                put_string(&mut out, s.kind);
                put_int(&mut out, s.blocks.len() as i32);
                for b in s.blocks {
                    put_string(&mut out, b.kind);
                    put_string(&mut out, &b.text);
                    put_int(&mut out, b.row);
                    put_int(&mut out, b.column);
                    put_int(&mut out, b.level);
                }
            }
        }
    }
    assert!(out.len() <= 2 * 1024 * 1024);
    out
}

#[repr(C)]
pub struct Buffer {
    ptr: *mut u8,
    len: usize,
    capacity: usize,
}
#[no_mangle]
pub unsafe extern "C" fn alldoc_doc_parse(
    input: *const u8,
    len: usize,
    name: *const u16,
    nlen: usize,
    opaque: *mut c_void,
    poll: extern "C" fn(*mut c_void) -> i32,
) -> Buffer {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if len > INPUT || nlen > 512 {
            return Err(limit("Document input exceeds bounds"));
        }
        let hint = String::from_utf16(std::slice::from_raw_parts(name, nlen))
            .map_err(|_| bad("Invalid name Unicode"))?;
        preview(std::slice::from_raw_parts(input, len), &hint, &mut || {
            poll(opaque) != 0
        })
    }))
    .unwrap_or_else(|_| Err(bad("Native document parser failed")));
    let mut out = encode(result);
    let answer = Buffer {
        ptr: out.as_mut_ptr(),
        len: out.len(),
        capacity: out.capacity(),
    };
    std::mem::forget(out);
    answer
}
#[no_mangle]
pub unsafe extern "C" fn alldoc_doc_free(buffer: Buffer) {
    drop(Vec::from_raw_parts(buffer.ptr, buffer.len, buffer.capacity));
}
extern "C" {
    fn alldoc_doc_jni(
        env: *mut c_void,
        cls: *mut c_void,
        input: *mut c_void,
        name: *mut c_void,
        cancel: *mut c_void,
    ) -> *mut c_void;
}
#[no_mangle]
pub unsafe extern "C" fn Java_app_smallthingz_alldoc_core_NativeDocuments_previewNative(
    env: *mut c_void,
    cls: *mut c_void,
    input: *mut c_void,
    name: *mut c_void,
    cancel: *mut c_void,
) -> *mut c_void {
    alldoc_doc_jni(env, cls, input, name, cancel)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contiguous_text_stops_after_the_first_omitted_scalar() {
        for (limit, expected) in ["", "a", "a", "a", "a", "a😀", "a😀Z"].iter().enumerate() {
            for pieces in [["a", "😀", "Z"], ["a😀", "Z", ""], ["a", "😀Z", ""]] {
                let mut text = TextBuffer::default();
                let mut truncated = false;
                for piece in pieces {
                    text.append_limit(piece, limit, &mut truncated);
                }
                assert_eq!(text.as_str(), *expected);
                assert_eq!(truncated, limit < 6);
            }
        }
    }

    #[test]
    fn contiguous_exhaustion_follows_cell_transfers_and_resets_on_clear() {
        let mut paragraph = TextBuffer::default();
        let mut truncated = false;
        paragraph.append_limit("a", 2, &mut truncated);
        paragraph.append_limit("😀", 2, &mut truncated);
        let mut cell = TextBuffer::default();
        cell.append_buffer(&paragraph, &mut truncated);
        append(&mut cell, "Z", &mut truncated);
        assert_eq!(cell.as_str(), "a");
        assert!(truncated);
        paragraph.clear();
        append(&mut paragraph, "B", &mut truncated);
        cell.append_buffer(&paragraph, &mut truncated);
        assert_eq!(cell.as_str(), "a");
        cell.clear();
        cell.append_buffer(&paragraph, &mut truncated);
        append(&mut cell, "C", &mut truncated);
        assert_eq!(cell.as_str(), "BC");
        assert!(truncated); // Other omissions do not suppress an independent string.
    }

    #[test]
    fn omitted_blocks_report_truncation_at_both_limits() {
        for byte_limit in [false, true] {
            for extra in ["", "more"] {
                let mut doc = Document::new("test");
                let section = doc.section("", "document");
                if byte_limit {
                    doc.paragraph(section, &"x".repeat(TEXT));
                } else {
                    for _ in 0..4096 {
                        doc.paragraph(section, "x");
                    }
                }
                assert!(!doc.truncated);
                let blocks = doc.sections[0].blocks.len();
                doc.paragraph(section, extra);
                assert!(doc.truncated);
                assert_eq!(doc.sections[0].blocks.len(), blocks);
            }
        }
    }
}
