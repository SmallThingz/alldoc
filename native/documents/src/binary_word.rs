//! MS-DOC Word97+ FibRgFcLcb97/Clx/PlcPcd main-story text, independent bounded reader.
use crate::package::{u16le, u32le};
use crate::{append, bad, encrypted, limit, unsupported, Context, Document, Result, TextBuffer};
struct Text {
    paragraph: TextBuffer,
    fields: Vec<bool>,
    high: Option<u16>,
}
impl Text {
    fn unit(&mut self, u: u16, doc: &mut Document, section: Option<usize>) -> Result<()> {
        if (0xd800..=0xdbff).contains(&u) {
            if self.high.replace(u).is_some() {
                return Err(bad("Unpaired Word surrogate"));
            }
            return Ok(());
        }
        let scalar = if (0xdc00..=0xdfff).contains(&u) {
            let h = self
                .high
                .take()
                .ok_or_else(|| bad("Unpaired Word surrogate"))?;
            0x10000 + ((h as u32 - 0xd800) << 10) + (u as u32 - 0xdc00)
        } else {
            if self.high.is_some() {
                return Err(bad("Unpaired Word surrogate"));
            }
            u as u32
        };
        let c = char::from_u32(scalar).ok_or_else(|| bad("Invalid Word Unicode"))?;
        match c {
            '\u{13}' => {
                if self.fields.len() == 64 {
                    return Err(limit("Word field nesting exceeds bounds"));
                }
                self.fields.push(false);
            }
            '\u{14}' => {
                *self
                    .fields
                    .last_mut()
                    .ok_or_else(|| bad("Unbalanced Word field"))? = true;
            }
            '\u{15}' => {
                self.fields
                    .pop()
                    .ok_or_else(|| bad("Unbalanced Word field"))?;
            }
            _ if self.fields.iter().any(|result| !*result) => {}
            '\r' | '\u{b}' | '\u{c}' => {
                doc.paragraph(section, &self.paragraph);
                self.paragraph.clear();
            }
            '\u{7}' => append(&mut self.paragraph, "\t", &mut doc.truncated),
            '\t' => append(&mut self.paragraph, "\t", &mut doc.truncated),
            _ if c < ' ' => {}
            _ => append(
                &mut self.paragraph,
                c.encode_utf8(&mut [0; 4]),
                &mut doc.truncated,
            ),
        }
        Ok(())
    }
}
// No extension traits or unchecked layout transmutation at the format boundary.
pub fn parse(word: &[u8], table: &[u8], cx: &mut Context) -> Result<Document> {
    if u16le(word, 0)? != 0xa5ec {
        return Err(bad("Invalid Word document signature"));
    }
    let version = u16le(word, 2)?;
    if !matches!(version, 0x00c1 | 0x00d9 | 0x0101 | 0x010c | 0x0112) {
        return Err(unsupported("This Word binary version is unsupported"));
    }
    if u16le(word, 10)? & 0x8100 != 0 {
        return Err(encrypted());
    }
    let cb_mac = u32le(word, 64)? as usize;
    let word = word
        .get(..cb_mac)
        .ok_or_else(|| bad("Word cbMac exceeds stream"))?;
    if u16le(word, 32)? != 14 || u16le(word, 62)? != 22 || u16le(word, 152)? < 34 {
        return Err(bad("Invalid Word FIB layout"));
    }
    let count = u32le(word, 76)? as usize;
    if count > 8 * 1024 * 1024 {
        return Err(limit("Word main story exceeds character limit"));
    }
    let fc = u32le(word, 418)? as usize;
    let len = u32le(word, 422)? as usize;
    if len == 0 {
        return Err(unsupported("Word document has no supported piece table"));
    }
    let clx = table
        .get(
            fc..fc
                .checked_add(len)
                .ok_or_else(|| bad("Word CLX overflow"))?,
        )
        .ok_or_else(|| bad("Truncated Word piece table"))?;
    let mut pos = 0;
    while clx.get(pos) == Some(&1) {
        cx.tick()?;
        let n = u16le(clx, pos + 1)? as usize;
        pos = pos
            .checked_add(3 + n)
            .filter(|p| *p <= clx.len())
            .ok_or_else(|| bad("Truncated Word property run"))?;
    }
    if clx.get(pos) != Some(&2) {
        return Err(bad("Missing Word piece table record"));
    }
    let size = u32le(clx, pos + 1)? as usize;
    if size < 4 || (size - 4) % 12 != 0 || pos + 5 + size != clx.len() {
        return Err(bad("Invalid Word piece table size"));
    }
    let pieces = (size - 4) / 12;
    if pieces > 8192 {
        return Err(limit("Word piece count exceeds bounds"));
    }
    let plc = &clx[pos + 5..];
    if u32le(plc, 0)? != 0 || (u32le(plc, pieces * 4)? as usize) < count {
        return Err(bad("Word piece table has a main-story gap"));
    }
    let mut doc = Document::new("DOC");
    doc.warn("Word97+ main-story text only; headers, notes, text boxes, revision styling and page geometry are omitted.");
    let section = doc.section("Document", "document");
    let mut text = Text {
        paragraph: TextBuffer::default(),
        fields: vec![],
        high: None,
    };
    for i in 0..pieces {
        cx.tick()?;
        let start = u32le(plc, i * 4)? as usize;
        let end = u32le(plc, (i + 1) * 4)? as usize;
        if end < start {
            return Err(bad("Unordered Word character ranges"));
        }
        let raw = u32le(plc, (pieces + 1) * 4 + i * 8 + 2)?;
        if raw & 0x80000000 != 0 {
            return Err(bad("Invalid Word piece offset"));
        }
        let compressed = raw & 0x40000000 != 0;
        let mut offset = (raw & 0x3fffffff) as usize;
        if compressed {
            if offset % 2 != 0 {
                return Err(bad("Invalid compressed Word offset"));
            }
            offset /= 2;
        }
        let stride = if compressed { 1 } else { 2 };
        let full = (end - start)
            .checked_mul(stride)
            .ok_or_else(|| bad("Word piece size overflow"))?;
        let bytes = word
            .get(
                offset
                    ..offset
                        .checked_add(full)
                        .ok_or_else(|| bad("Word piece offset overflow"))?,
            )
            .ok_or_else(|| bad("Word piece points outside document"))?;
        let n = end.min(count).saturating_sub(start.min(count));
        if compressed {
            // MS-DOC FcCompressed has an explicit exception table, not CP1252.
            for (j, b) in bytes[..n].iter().enumerate() {
                if j % 4096 == 0 {
                    cx.check()?;
                }
                let unit = match *b {
                    0x82 => 0x201a,
                    0x83 => 0x0192,
                    0x84 => 0x201e,
                    0x85 => 0x2026,
                    0x86 => 0x2020,
                    0x87 => 0x2021,
                    0x88 => 0x02c6,
                    0x89 => 0x2030,
                    0x8a => 0x0160,
                    0x8b => 0x2039,
                    0x8c => 0x0152,
                    0x91 => 0x2018,
                    0x92 => 0x2019,
                    0x93 => 0x201c,
                    0x94 => 0x201d,
                    0x95 => 0x2022,
                    0x96 => 0x2013,
                    0x97 => 0x2014,
                    0x98 => 0x02dc,
                    0x99 => 0x2122,
                    0x9a => 0x0161,
                    0x9b => 0x203a,
                    0x9c => 0x0153,
                    0x9f => 0x0178,
                    n => n as u16,
                };
                text.unit(unit, &mut doc, section)?;
            }
        } else {
            for (j, b) in bytes[..n * 2].chunks_exact(2).enumerate() {
                if j % 4096 == 0 {
                    cx.check()?;
                }
                text.unit(u16::from_le_bytes([b[0], b[1]]), &mut doc, section)?;
            }
        }
    }
    if text.high.is_some() || !text.fields.is_empty() {
        return Err(bad("Truncated Word text or field"));
    }
    if !text.paragraph.is_empty() {
        doc.paragraph(section, &text.paragraph);
    }
    Ok(doc)
}
