//! Narrow ZIP reader for Office stored/deflate parts. Bounds precede allocation.
use crate::{bad, encrypted, limit, unsupported, Context, Result};
use std::{collections::BTreeMap, io::Read};
pub fn u16le(b: &[u8], p: usize) -> Result<u16> {
    let b = b.get(p..p + 2).ok_or_else(|| bad("Truncated container"))?;
    Ok(u16::from_le_bytes([b[0], b[1]]))
}
pub fn u32le(b: &[u8], p: usize) -> Result<u32> {
    let b = b.get(p..p + 4).ok_or_else(|| bad("Truncated container"))?;
    Ok(u32::from_le_bytes(b.try_into().unwrap()))
}
struct Member {
    start: usize,
    compressed: usize,
    size: usize,
    method: u16,
    crc: u32,
}
pub struct Package<'a> {
    bytes: &'a [u8],
    members: BTreeMap<String, Member>,
}
impl<'a> Package<'a> {
    pub fn open(bytes: &'a [u8], cx: &mut Context) -> Result<Self> {
        let low = bytes.len().saturating_sub(65557);
        let eocd = (low..bytes.len().saturating_sub(21))
            .rev()
            .find(|&i| {
                bytes.get(i..i + 4) == Some(b"PK\x05\x06")
                    && u16le(bytes, i + 20)
                        .map(|n| i + 22 + n as usize == bytes.len())
                        .unwrap_or(false)
            })
            .ok_or_else(|| bad("Missing ZIP end record"))?;
        if u16le(bytes, eocd + 4)? != 0
            || u16le(bytes, eocd + 6)? != 0
            || u16le(bytes, eocd + 8)? != u16le(bytes, eocd + 10)?
        {
            return Err(unsupported("Multi-disk document ZIP is unsupported"));
        }
        let count = u16le(bytes, eocd + 10)? as usize;
        let size = u32le(bytes, eocd + 12)? as usize;
        let start = u32le(bytes, eocd + 16)? as usize;
        if count > 4096 || size > 4 * 1024 * 1024 {
            return Err(limit("ZIP directory exceeds document limits"));
        }
        if start.checked_add(size) != Some(eocd) {
            return Err(bad("Invalid ZIP directory range"));
        }
        let mut pos = start;
        let mut members = BTreeMap::new();
        let mut ranges = vec![];
        for _ in 0..count {
            cx.tick()?;
            if bytes.get(pos..pos + 4) != Some(b"PK\x01\x02") {
                return Err(bad("Invalid ZIP directory entry"));
            }
            let flags = u16le(bytes, pos + 8)?;
            if flags & (1 | 64 | 8192) != 0 {
                return Err(encrypted());
            }
            let method = u16le(bytes, pos + 10)?;
            let crc = u32le(bytes, pos + 16)?;
            let compressed = u32le(bytes, pos + 20)? as usize;
            let inflated = u32le(bytes, pos + 24)? as usize;
            let nl = u16le(bytes, pos + 28)? as usize;
            let el = u16le(bytes, pos + 30)? as usize;
            let cl = u16le(bytes, pos + 32)? as usize;
            if nl == 0 || nl > 1024 {
                return Err(limit("ZIP member name limit exceeded"));
            }
            if u16le(bytes, pos + 34)? != 0 {
                return Err(unsupported("Multi-disk document ZIP is unsupported"));
            }
            let local = u32le(bytes, pos + 42)? as usize;
            let end = pos + 46 + nl + el + cl;
            if end > eocd {
                return Err(bad("Truncated ZIP directory"));
            }
            let raw = &bytes[pos + 46..pos + 46 + nl];
            let name =
                std::str::from_utf8(raw).map_err(|_| bad("ZIP member name must be UTF-8"))?;
            if name.starts_with('/')
                || name.contains(['\\', ':'])
                || name.chars().any(|c| c < ' ' || c == '\u{7f}')
                || name.split('/').any(|p| p == "." || p == "..")
            {
                return Err(bad("Unsafe document member name"));
            }
            if bytes.get(local..local + 4) != Some(b"PK\x03\x04")
                || u16le(bytes, local + 6)? != flags
                || u16le(bytes, local + 8)? != method
            {
                return Err(bad("Mismatched ZIP local header"));
            }
            let local_nl = u16le(bytes, local + 26)? as usize;
            let local_el = u16le(bytes, local + 28)? as usize;
            if local_nl != nl || bytes.get(local + 30..local + 30 + nl) != Some(raw) {
                return Err(bad("Mismatched ZIP member name"));
            }
            if flags & 8 == 0
                && (u32le(bytes, local + 14)? != crc
                    || u32le(bytes, local + 18)? as usize != compressed
                    || u32le(bytes, local + 22)? as usize != inflated)
            {
                return Err(bad("Mismatched ZIP member size or CRC"));
            }
            let data = local + 30 + local_nl + local_el;
            let data_end = data
                .checked_add(compressed)
                .ok_or_else(|| bad("ZIP member size overflow"))?;
            if data_end > start {
                return Err(bad("ZIP member overlaps directory"));
            }
            ranges.push((local, data_end));
            if members
                .insert(
                    name.into(),
                    Member {
                        start: data,
                        compressed,
                        size: inflated,
                        method,
                        crc,
                    },
                )
                .is_some()
            {
                return Err(bad("Duplicate document member"));
            }
            pos = end;
        }
        if pos != eocd {
            return Err(bad("Unexpected ZIP directory data"));
        }
        ranges.sort_unstable();
        if ranges.windows(2).any(|r| r[0].1 > r[1].0) {
            return Err(bad("Overlapping document members"));
        }
        Ok(Self { bytes, members })
    }
    pub fn has(&self, name: &str) -> bool {
        self.members.contains_key(name)
    }
    pub fn read(&mut self, name: &str, cx: &mut Context) -> Result<Vec<u8>> {
        let m = self
            .members
            .get(name)
            .ok_or_else(|| bad("Missing document package part"))?;
        if m.size > 8 * 1024 * 1024 {
            return Err(limit("Document part exceeds 8 MiB"));
        }
        let compressed = &self.bytes[m.start..m.start + m.compressed];
        let mut out = Vec::new();
        match m.method {
            0 => {
                if m.size != m.compressed {
                    return Err(bad("Invalid stored ZIP size"));
                }
                cx.inflate(compressed.len())?;
                out.extend_from_slice(compressed);
            }
            8 => {
                let mut decoder = flate2::read::DeflateDecoder::new(compressed);
                let mut chunk = [0u8; 16384];
                loop {
                    cx.check()?;
                    let n = decoder
                        .read(&mut chunk)
                        .map_err(|_| bad("Invalid document deflate stream"))?;
                    if n == 0 {
                        break;
                    }
                    if out.len() + n > 8 * 1024 * 1024 || out.len() + n > m.size {
                        return Err(limit("Document part expansion exceeds bounds"));
                    }
                    cx.inflate(n)?;
                    out.extend_from_slice(&chunk[..n]);
                }
                if decoder.total_in() != m.compressed as u64 {
                    return Err(bad("Trailing document deflate data"));
                }
            }
            _ => {
                return Err(unsupported(
                    "Document ZIP compression method is unsupported",
                ))
            }
        }
        if out.len() != m.size || crc32fast::hash(&out) != m.crc {
            return Err(bad("Document part size or CRC mismatch"));
        }
        Ok(out)
    }
}
