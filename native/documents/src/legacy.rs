//! Read-only BIFF8 sparse cells over bounded CFB. No dense grid allocation.
use crate::package::{u16le, u32le};
use crate::{Context, Document, Result, TEXT, bad, encrypted, limit, unsupported};
use std::{
    cell::Cell,
    collections::BTreeSet,
    io::{self, Cursor, Read, Seek, SeekFrom},
};
struct Guarded<'a, 'b, 'c> {
    inner: Cursor<&'a [u8]>,
    cx: &'b mut Context<'c>,
    calls: usize,
    limited: &'b Cell<bool>,
}
impl Guarded<'_, '_, '_> {
    fn poll(&mut self) -> io::Result<()> {
        self.calls += 1;
        if self.calls > 2_000_000 {
            self.limited.set(true);
            return Err(io::Error::other("CFB work limit"));
        }
        if self.calls % 128 == 0 {
            self.cx
                .check()
                .map_err(|_| io::Error::other("CFB cancelled"))?;
        }
        Ok(())
    }
}
impl Read for Guarded<'_, '_, '_> {
    fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
        self.poll()?;
        self.inner.read(b)
    }
}
impl Seek for Guarded<'_, '_, '_> {
    fn seek(&mut self, p: SeekFrom) -> io::Result<u64> {
        self.poll()?;
        self.inner.seek(p)
    }
}
pub fn parse(input: &[u8], cx: &mut Context) -> Result<Document> {
    let limited = Cell::new(false);
    let result = (|| {
        let reader = Guarded {
            inner: Cursor::new(input),
            cx,
            calls: 0,
            limited: &limited,
        };
        let mut file = cfb::OpenOptions::new()
            // Compatibility validation accepts LibreOffice's adjacent red directory
            // nodes. cfb still checks sector ranges, cycles and directory ownership;
            // Guarded enforces independent I/O work and cancellation limits.
            .max_buffer_size(16384)
            .open_with(reader)
            .map_err(|_| bad("Malformed compound document"))?;
        if file.is_stream("/EncryptedPackage") {
            return Err(encrypted());
        }
        let name = if file.is_stream("/Workbook") {
            "/Workbook"
        } else if file.is_stream("/Book") {
            "/Book"
        } else if file.is_stream("/PowerPoint Document") {
            "/PowerPoint Document"
        } else if file.is_stream("/WordDocument") {
            "/WordDocument"
        } else {
            return Err(unsupported("This legacy Office format is unsupported"));
        };
        let mut read = |name: &str| -> Result<Vec<u8>> {
            let size = file
                .entry(name)
                .map_err(|_| bad("Missing legacy document stream"))?
                .len();
            if size > 8 * 1024 * 1024 {
                return Err(limit("Legacy document stream exceeds 8 MiB"));
            }
            let mut stream = file
                .open_stream(name)
                .map_err(|_| bad("Malformed legacy document stream"))?;
            let mut bytes = vec![];
            stream
                .by_ref()
                .take(8 * 1024 * 1024 + 1)
                .read_to_end(&mut bytes)
                .map_err(|_| bad("Truncated legacy document stream"))?;
            if bytes.len() as u64 != size {
                return Err(bad("Truncated legacy document stream"));
            }
            Ok(bytes)
        };
        let bytes = read(name)?;
        let other = if name == "/WordDocument" {
            let flags = u16le(&bytes, 10)?;
            if flags & 0x8100 != 0 {
                return Err(encrypted());
            }
            read(if flags & 0x200 != 0 {
                "/1Table"
            } else {
                "/0Table"
            })?
        } else if name == "/PowerPoint Document" {
            read("/Current User")?
        } else {
            vec![]
        };
        Ok((name, bytes, other))
    })();
    cx.check()?;
    if limited.get() {
        return Err(limit("Compound document work limit exceeded"));
    }
    let (name, bytes, other) = result?;
    match name {
        "/WordDocument" => crate::binary_word::parse(&bytes, &other, cx),
        "/PowerPoint Document" => crate::binary_ppt::parse(&bytes, &other, cx),
        _ => workbook(&bytes, cx),
    }
}
fn record(bytes: &[u8], offset: usize) -> Result<(u16, &[u8], usize)> {
    let kind = u16le(bytes, offset)?;
    let size = u16le(bytes, offset + 2)? as usize;
    if size > 8224 {
        return Err(bad("BIFF8 record exceeds 8224 bytes"));
    }
    let end = offset + 4 + size;
    let data = bytes
        .get(offset + 4..end)
        .ok_or_else(|| bad("Truncated BIFF record"))?;
    Ok((kind, data, end))
}
fn string(bytes: &[u8], len: usize, wide: bool) -> Result<String> {
    let n = len
        .checked_mul(if wide { 2 } else { 1 })
        .ok_or_else(|| bad("Invalid BIFF string length"))?;
    let b = bytes.get(..n).ok_or_else(|| bad("Truncated BIFF string"))?;
    if wide {
        crate::utf16(b)
    } else {
        Ok(b.iter().map(|b| char::from(*b)).collect())
    }
}
struct Segments<'a> {
    parts: Vec<&'a [u8]>,
    part: usize,
    pos: usize,
}
impl Segments<'_> {
    fn byte(&mut self) -> Result<u8> {
        while self.part < self.parts.len() && self.pos == self.parts[self.part].len() {
            self.part += 1;
            self.pos = 0;
        }
        let b = *self
            .parts
            .get(self.part)
            .and_then(|p| p.get(self.pos))
            .ok_or_else(|| bad("Truncated BIFF string table"))?;
        self.pos += 1;
        Ok(b)
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes([self.byte()?, self.byte()?]))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes([
            self.byte()?,
            self.byte()?,
            self.byte()?,
            self.byte()?,
        ]))
    }
    fn skip(&mut self, n: usize) -> Result<()> {
        for _ in 0..n {
            self.byte()?;
        }
        Ok(())
    }
    fn chars(&mut self, n: usize, mut wide: bool) -> Result<String> {
        let mut units = Vec::with_capacity(n);
        for _ in 0..n {
            if self.parts.get(self.part).map(|p| p.len()) == Some(self.pos) {
                self.part += 1;
                self.pos = 0;
                let flag = self.byte()?;
                if flag > 1 {
                    return Err(bad("Invalid BIFF string continuation"));
                }
                wide = flag == 1;
            }
            if wide {
                let part = self
                    .parts
                    .get(self.part)
                    .ok_or_else(|| bad("Truncated BIFF string"))?;
                if part.len().saturating_sub(self.pos) < 2 {
                    return Err(bad("Split BIFF UTF-16 code unit"));
                }
                units.push(self.u16()?);
            } else {
                units.push(self.byte()? as u16);
            }
        }
        String::from_utf16(&units).map_err(|_| bad("Invalid BIFF Unicode"))
    }
}
fn sst(parts: Vec<&[u8]>, cx: &mut Context) -> Result<Vec<String>> {
    let mut s = Segments {
        parts,
        part: 0,
        pos: 0,
    };
    let _total = s.u32()?;
    let unique = s.u32()? as usize;
    if unique > 65536 {
        return Err(limit("Legacy shared string count exceeds bounds"));
    }
    let mut out = vec![];
    let mut bytes = 0usize;
    for _ in 0..unique {
        cx.tick()?;
        let n = s.u16()? as usize;
        let flags = s.byte()?;
        if flags & !13 != 0 {
            return Err(bad("Invalid BIFF Unicode flags"));
        }
        let runs = if flags & 8 != 0 { s.u16()? as usize } else { 0 };
        let ext = if flags & 4 != 0 { s.u32()? as usize } else { 0 };
        if ext > 8 * 1024 * 1024 {
            return Err(limit("BIFF extension exceeds bounds"));
        }
        let text = s.chars(n, flags & 1 != 0)?;
        bytes += text.len();
        if bytes > 8 * 1024 * 1024 {
            return Err(limit("Legacy shared strings exceed bounds"));
        }
        out.push(text);
        s.skip(runs * 4 + ext)?;
    }
    Ok(out)
}
fn rk(n: u32) -> String {
    let mut f = if n & 2 != 0 {
        ((n as i32) >> 2) as f64
    } else {
        f64::from_bits(((n & !3) as u64) << 32)
    };
    if n & 1 != 0 {
        f /= 100.0;
    }
    f.to_string()
}
fn number(bytes: &[u8]) -> Result<String> {
    let b: [u8; 8] = bytes
        .get(..8)
        .ok_or_else(|| bad("Truncated numeric cell"))?
        .try_into()
        .unwrap();
    let n = f64::from_le_bytes(b);
    if !n.is_finite() {
        return Err(bad("Invalid numeric cell"));
    }
    Ok(n.to_string())
}
fn workbook(bytes: &[u8], cx: &mut Context) -> Result<Document> {
    let (kind, bof, _) = record(bytes, 0)?;
    if kind != 0x0809 || u16le(bof, 0)? != 0x0600 {
        return Err(unsupported("Only BIFF8 XLS workbooks are supported"));
    }
    if u16le(bof, 2)? != 0x0005 {
        return Err(bad("Invalid workbook globals"));
    }
    let mut pos = 0;
    let mut sheets = vec![];
    let mut shared = vec![];
    let mut ended = false;
    while pos < bytes.len() {
        cx.tick()?;
        let (kind, data, end) = record(bytes, pos)?;
        pos = end;
        match kind {
            0x002f => return Err(encrypted()),
            0x0085 => {
                if sheets.len() == 4096 {
                    return Err(limit("Too many legacy sheets"));
                }
                let offset = u32le(data, 0)? as usize;
                let kind = *data
                    .get(5)
                    .ok_or_else(|| bad("Truncated sheet reference"))?;
                let n = *data.get(6).ok_or_else(|| bad("Truncated sheet name"))? as usize;
                let flag = *data.get(7).ok_or_else(|| bad("Truncated sheet name"))?;
                if flag > 1 {
                    return Err(bad("Invalid sheet name flags"));
                }
                let name = string(&data[8..], n, flag != 0)?;
                sheets.push((offset, kind, name));
            }
            0x00fc => {
                let mut parts = vec![data];
                while pos < bytes.len() {
                    let (k, d, e) = record(bytes, pos)?;
                    if k != 0x003c {
                        break;
                    }
                    cx.tick()?;
                    parts.push(d);
                    pos = e;
                }
                shared = sst(parts, cx)?;
            }
            0x000a => {
                ended = true;
                break;
            }
            _ => {}
        }
    }
    if !ended {
        return Err(bad("Missing workbook globals end"));
    }
    let mut doc = Document::new("XLS");
    doc.warn("BIFF8 cells show stored values; formats, charts, macros and formula calculation are omitted.");
    if sheets.len() > 64 {
        doc.truncated = true;
    }
    for (offset, kind, name) in sheets.into_iter().take(64) {
        if kind != 0 {
            doc.warn("Non-worksheet legacy sheets are omitted.");
            continue;
        }
        let section = doc.section(&name, "sheet");
        sheet(bytes, offset, &shared, &mut doc, section, cx)?;
    }
    Ok(doc)
}
fn sheet(
    bytes: &[u8],
    offset: usize,
    shared: &[String],
    doc: &mut Document,
    section: Option<usize>,
    cx: &mut Context,
) -> Result<()> {
    let (kind, bof, _) = record(bytes, offset)?;
    if kind != 0x0809 || u16le(bof, 0)? != 0x0600 || u16le(bof, 2)? != 0x0010 {
        return Err(bad("Invalid BIFF worksheet start"));
    }
    let mut pos = offset;
    let mut seen = BTreeSet::new();
    let mut pending = None;
    let mut count = 0;
    while pos < bytes.len() {
        cx.tick()?;
        let (kind, data, end) = record(bytes, pos)?;
        pos = end;
        if kind == 0x000a {
            if pending.is_some() {
                return Err(bad("Missing formula string value"));
            }
            return Ok(());
        }
        if kind == 0x002f {
            return Err(encrypted());
        }
        if kind == 0x0207 {
            if let Some((r, c)) = pending.take() {
                let n = u16le(data, 0)? as usize;
                let flag = *data.get(2).ok_or_else(|| bad("Truncated formula string"))?;
                if flag > 1 {
                    return Err(unsupported("Rich formula string variant is unsupported"));
                }
                let value = string(&data[3..], n, flag == 1)?;
                count += 1;
                if count > 8192 {
                    return Err(limit("Legacy cell record limit exceeded"));
                }
                if !seen.insert((r, c)) {
                    return Err(bad("Duplicate legacy cell"));
                }
                doc.push(section, "cell", &value, r, c, 0);
            }
            continue;
        }
        if !matches!(
            kind,
            0x00fd | 0x0203 | 0x027e | 0x00bd | 0x0205 | 0x0006 | 0x0204
        ) {
            continue;
        }
        let r = u16le(data, 0)? as i32;
        let c = u16le(data, 2)? as i32;
        if c > 255 {
            return Err(bad("BIFF column out of range"));
        }
        let mut values = vec![];
        match kind {
            0x00fd => {
                let i = u32le(data, 6)? as usize;
                values.push((
                    c,
                    shared
                        .get(i)
                        .ok_or_else(|| bad("Invalid legacy shared string index"))?
                        .clone(),
                ));
            }
            0x0203 => values.push((
                c,
                number(data.get(6..).ok_or_else(|| bad("Truncated number"))?)?,
            )),
            0x027e => values.push((c, rk(u32le(data, 6)?))),
            0x00bd => {
                let last = u16le(
                    data,
                    data.len()
                        .checked_sub(2)
                        .ok_or_else(|| bad("Invalid MULRK"))?,
                )? as i32;
                if last < c || last > 255 || data.len() != 6 + 6 * (last - c + 1) as usize {
                    return Err(bad("Invalid MULRK cell range"));
                }
                for col in c..=last {
                    values.push((col, rk(u32le(data, 6 + 6 * (col - c) as usize)?)));
                }
            }
            0x0205 => {
                let v = *data.get(6).ok_or_else(|| bad("Truncated boolean cell"))?;
                let error = *data.get(7).ok_or_else(|| bad("Truncated boolean cell"))?;
                values.push((
                    c,
                    if error == 0 {
                        if v == 0 {
                            "FALSE".into()
                        } else if v == 1 {
                            "TRUE".into()
                        } else {
                            return Err(bad("Invalid boolean value"));
                        }
                    } else {
                        format!("#ERROR({v})")
                    },
                ));
            }
            0x0204 => {
                let n = u16le(data, 6)? as usize;
                let flag = *data.get(8).ok_or_else(|| bad("Truncated label"))?;
                if flag > 1 {
                    return Err(unsupported("Rich legacy LABEL variant is unsupported"));
                }
                values.push((c, string(&data[9..], n, flag == 1)?));
            }
            0x0006 => {
                doc.warn("Formula cells show saved values; formulas are not recalculated.");
                if data.get(12..14) == Some(&[255, 255]) {
                    match data.get(6) {
                        Some(0) => {
                            pending = Some((r, c));
                        }
                        Some(1) => values.push((
                            c,
                            if data.get(8) == Some(&0) {
                                "FALSE".into()
                            } else {
                                "TRUE".into()
                            },
                        )),
                        Some(2) => values.push((c, format!("#ERROR({})", data[8]))),
                        Some(3) => {}
                        _ => return Err(bad("Invalid formula result")),
                    }
                } else {
                    values.push((
                        c,
                        number(data.get(6..).ok_or_else(|| bad("Truncated formula"))?)?,
                    ));
                }
            }
            _ => {}
        }
        for (c, value) in values {
            count += 1;
            if count > 8192 {
                return Err(limit("Legacy cell record limit exceeded"));
            }
            if !seen.insert((r, c)) {
                return Err(bad("Duplicate legacy cell"));
            }
            if value.len() > TEXT {
                return Err(limit("Legacy cell text exceeds bounds"));
            }
            doc.push(section, "cell", &value, r, c, 0);
        }
    }
    Err(bad("Missing BIFF worksheet end"))
}

#[cfg(test)]
mod regression_tests {
    use super::*;
    fn record(kind: u16, body: &[u8]) -> Vec<u8> {
        let mut out = Vec::from(kind.to_le_bytes());
        out.extend_from_slice(&(body.len() as u16).to_le_bytes());
        out.extend_from_slice(body);
        out
    }
    fn bof(kind: u16) -> Vec<u8> {
        let mut body = vec![0; 16];
        body[..2].copy_from_slice(&0x0600u16.to_le_bytes());
        body[2..4].copy_from_slice(&kind.to_le_bytes());
        record(0x0809, &body)
    }
    fn cached(row: u16, col: u16, text: &str) -> Vec<u8> {
        let utf: Vec<u16> = text.encode_utf16().collect();
        let mut tokens = vec![0x17, utf.len() as u8, 1];
        for unit in &utf {
            tokens.extend_from_slice(&unit.to_le_bytes());
        }
        let mut formula = vec![0; 22];
        formula[..2].copy_from_slice(&row.to_le_bytes());
        formula[2..4].copy_from_slice(&col.to_le_bytes());
        formula[12..14].fill(255);
        formula[20..22].copy_from_slice(&(tokens.len() as u16).to_le_bytes());
        formula.extend_from_slice(&tokens);
        let mut string = Vec::from((utf.len() as u16).to_le_bytes());
        string.push(1);
        for unit in utf {
            string.extend_from_slice(&unit.to_le_bytes());
        }
        let mut out = record(0x0006, &formula);
        out.extend(record(0x0207, &string));
        out
    }
    fn numeric(row: u16, col: u16) -> Vec<u8> {
        let mut body = Vec::from(row.to_le_bytes());
        body.extend_from_slice(&col.to_le_bytes());
        body.extend_from_slice(&0u16.to_le_bytes());
        body.extend_from_slice(&7f64.to_le_bytes());
        record(0x0203, &body)
    }
    fn parse_cells(cells: &[u8]) -> Result<Document> {
        let mut bytes = bof(5);
        let offset = (bytes.len() + 13 + 4) as u32;
        let mut bound = Vec::from(offset.to_le_bytes());
        bound.extend_from_slice(&[0, 0, 1, 0, b'S']);
        bytes.extend(record(0x0085, &bound));
        bytes.extend(record(0x000a, &[]));
        bytes.extend(bof(0x0010));
        bytes.extend_from_slice(cells);
        bytes.extend(record(0x000a, &[]));
        let mut cancel = || false;
        workbook(&bytes, &mut Context::new(&mut cancel))
    }
    #[test]
    fn cached_strings_keep_unicode_and_coordinates() {
        let mut cells = numeric(0, 0);
        cells.extend(cached(1, 2, "Ω😀"));
        let d = parse_cells(&cells).unwrap();
        let blocks = &d.sections[0].blocks;
        assert_eq!(blocks.len(), 2);
        assert_eq!(
            (blocks[1].row, blocks[1].column, blocks[1].text.as_str()),
            (1, 2, "Ω😀")
        );
    }
    #[test]
    fn cached_strings_share_duplicate_checks() {
        let pairs = [
            (cached(0, 0, "X"), cached(0, 0, "Y")),
            (numeric(0, 0), cached(0, 0, "X")),
            (cached(0, 0, "X"), numeric(0, 0)),
        ];
        for (mut a, b) in pairs {
            a.extend(b);
            assert_eq!(parse_cells(&a).unwrap_err().code, "MALFORMED");
        }
    }
    #[test]
    fn cached_strings_share_record_limit() {
        let mut cells = Vec::new();
        for row in 0..8192 {
            cells.extend(cached(row, 0, "X"));
        }
        let d = parse_cells(&cells).unwrap();
        assert!(d.truncated);
        assert_eq!(d.sections[0].blocks.len(), 4096);
        cells.extend(cached(8192, 0, "X"));
        assert_eq!(parse_cells(&cells).unwrap_err().code, "LIMIT");
        cells.truncate(cells.len() - cached(8192, 0, "X").len());
        cells.extend(numeric(8192, 0));
        assert_eq!(parse_cells(&cells).unwrap_err().code, "LIMIT");
    }
}
