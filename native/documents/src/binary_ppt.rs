//! MS-PPT current-edit -> persist directory -> current slide list. No raw stream text scan.
use crate::package::{u16le, u32le};
use crate::{bad, encrypted, limit, unsupported, Context, Document, Result};
use std::collections::{BTreeMap, BTreeSet};
struct Record<'a> {
    kind: u16,
    version: u16,
    instance: u16,
    body: &'a [u8],
    end: usize,
}
fn record(bytes: &[u8], pos: usize) -> Result<Record<'_>> {
    let flags = u16le(bytes, pos)?;
    let kind = u16le(bytes, pos + 2)?;
    let len = u32le(bytes, pos + 4)? as usize;
    let end = pos
        .checked_add(8)
        .and_then(|v| v.checked_add(len))
        .ok_or_else(|| bad("PowerPoint record size overflow"))?;
    let body = bytes
        .get(pos + 8..end)
        .ok_or_else(|| bad("Truncated PowerPoint record"))?;
    Ok(Record {
        kind,
        version: flags & 15,
        instance: flags >> 4,
        body,
        end,
    })
}
fn container(bytes: &[u8], pos: usize, kind: u16) -> Result<&[u8]> {
    let r = record(bytes, pos)?;
    if r.kind != kind || r.version != 15 {
        return Err(bad("Invalid PowerPoint container"));
    }
    Ok(r.body)
}
fn text(r: &Record) -> Result<String> {
    if r.body.len() > 1024 * 1024 {
        return Err(limit("PowerPoint text atom exceeds limits"));
    }
    if r.kind == 0x0fa0 {
        crate::utf16(r.body)
    } else {
        // MS-PPT TextBytesAtom: each byte is the low byte of a UTF-16 unit.
        Ok(r.body.iter().map(|b| char::from(*b)).collect())
    }
}
struct Slide {
    id: u32,
    outline: Vec<String>,
}
fn list(bytes: &[u8], cx: &mut Context) -> Result<Vec<Slide>> {
    let mut pos = 0;
    let mut slides: Vec<Slide> = vec![];
    let mut total = 0usize;
    let mut runs = 0usize;
    while pos < bytes.len() {
        cx.tick()?;
        let r = record(bytes, pos)?;
        pos = r.end;
        match r.kind {
            0x03f3 => {
                if r.body.len() < 20 {
                    return Err(bad("Truncated slide persist atom"));
                }
                if slides.len() == 4096 {
                    return Err(limit("Too many legacy slides"));
                }
                slides.push(Slide {
                    id: u32le(r.body, 0)?,
                    outline: vec![],
                });
            }
            0x0f9f => {
                if r.body.len() != 4 {
                    return Err(bad("Invalid text header atom"));
                }
                runs += 1;
                if runs > 8192 {
                    return Err(limit("PowerPoint outline count exceeds bounds"));
                }
                slides
                    .last_mut()
                    .ok_or_else(|| bad("Outline outside slide entry"))?
                    .outline
                    .push(String::new());
            }
            0x0fa0 | 0x0fa8 => {
                let t = text(&r)?;
                total += t.len();
                if total > 8 * 1024 * 1024 {
                    return Err(limit("PowerPoint outline data exceeds bounds"));
                }
                slides
                    .last_mut()
                    .and_then(|s| s.outline.last_mut())
                    .ok_or_else(|| bad("PowerPoint outline has no text header"))?
                    .push_str(&t);
            }
            _ => {}
        }
    }
    Ok(slides)
}
fn paragraphs(text: &str, doc: &mut Document, section: Option<usize>) {
    for p in text.split(['\r', '\u{b}']) {
        if doc.full() {
            doc.truncated = true;
            break;
        }
        doc.paragraph(section, p);
    }
}
fn shapes(
    bytes: &[u8],
    outline: &[String],
    depth: usize,
    doc: &mut Document,
    section: Option<usize>,
    cx: &mut Context,
) -> Result<()> {
    if depth > 64 {
        return Err(limit("PowerPoint record nesting exceeds bounds"));
    }
    let mut pos = 0;
    while pos < bytes.len() {
        cx.tick()?;
        let r = record(bytes, pos)?;
        pos = r.end;
        match r.kind {
            0x0fa0 | 0x0fa8 => paragraphs(&text(&r)?, doc, section),
            0x0f9e => {
                if r.body.len() != 4 {
                    return Err(bad("Invalid outline text reference"));
                }
                let n = u32le(r.body, 0)? as usize;
                paragraphs(
                    outline
                        .get(n)
                        .ok_or_else(|| bad("Unresolved outline text reference"))?,
                    doc,
                    section,
                );
            }
            _ if r.kind == 1036
                || r.kind == 0xf00d
                || (matches!(r.kind, 0xf002 | 0xf003 | 0xf004) && r.version == 15) =>
            {
                shapes(r.body, outline, depth + 1, doc, section, cx)?
            }
            _ => {}
        }
    }
    Ok(())
}
pub fn parse(bytes: &[u8], current_user: &[u8], cx: &mut Context) -> Result<Document> {
    let user = record(current_user, 0)?;
    if user.kind != 0x0ff6 || user.body.len() < 20 {
        return Err(bad("Invalid PowerPoint Current User stream"));
    }
    match u32le(user.body, 4)? {
        0xf3d1c4df => return Err(encrypted()),
        0xe391c05f => {}
        _ => {
            return Err(unsupported(
                "PowerPoint Current User variant is unsupported",
            ))
        }
    }
    let mut edit = u32le(user.body, 8)? as usize;
    let mut seen = BTreeSet::new();
    let mut directory = BTreeMap::new();
    let mut document_id = None;
    loop {
        cx.tick()?;
        if !seen.insert(edit) {
            return Err(bad("Cyclic PowerPoint edit chain"));
        }
        if seen.len() > 512 {
            return Err(limit("PowerPoint edit history exceeds bounds"));
        }
        let r = record(bytes, edit)?;
        if r.kind != 0x0ff5 || r.body.len() < 28 {
            return Err(bad("Invalid PowerPoint current edit"));
        }
        if r.body.len() > 28 && u32le(r.body, 28)? != 0 {
            return Err(encrypted());
        }
        if document_id.is_none() {
            document_id = Some(u32le(r.body, 16)?);
        }
        let p = record(bytes, u32le(r.body, 12)? as usize)?;
        if p.kind != 0x1772 {
            return Err(unsupported(
                "PowerPoint persist directory variant is unsupported",
            ));
        }
        let mut i = 0;
        let mut local = BTreeSet::new();
        while i < p.body.len() {
            cx.tick()?;
            let header = u32le(p.body, i)?;
            i += 4;
            let id = header & 0x000fffff;
            let count = header >> 20;
            if id == 0 || count == 0 || id + count > 0x00100000 {
                return Err(bad("Invalid PowerPoint persist range"));
            }
            for n in 0..count {
                let offset = u32le(p.body, i)? as usize;
                i += 4;
                if offset >= bytes.len() {
                    return Err(bad("PowerPoint persist offset exceeds stream"));
                }
                if !local.insert(id + n) {
                    return Err(bad("Duplicate persist ID in one edit"));
                }
                directory.entry(id + n).or_insert(offset);
                if directory.len() > 65536 {
                    return Err(limit("PowerPoint persist count exceeds bounds"));
                }
            }
        }
        let previous = u32le(r.body, 8)? as usize;
        if previous == 0 {
            break;
        }
        edit = previous;
    }
    let resolve = |id: u32| -> Result<usize> {
        directory
            .get(&id)
            .copied()
            .ok_or_else(|| bad("Missing current PowerPoint persist object"))
    };
    let document = container(
        bytes,
        resolve(document_id.ok_or_else(|| bad("Missing current document ID"))?)?,
        0x03e8,
    )?;
    let mut pos = 0;
    let mut slide_list = None;
    while pos < document.len() {
        cx.tick()?;
        let r = record(document, pos)?;
        pos = r.end;
        if r.kind == 0x0ff0 && r.instance == 0 {
            if slide_list.is_some() || r.version != 15 {
                return Err(bad("Invalid duplicate slide list"));
            }
            slide_list = Some(r.body);
        }
    }
    let slides = list(
        slide_list.ok_or_else(|| unsupported("PowerPoint has no supported current slide list"))?,
        cx,
    )?;
    let mut doc = Document::new("PPT");
    doc.warn("Current saved slide text only; masters, notes, drawings, fields, fonts and animation are omitted.");
    if slides.len() > 64 {
        doc.truncated = true;
    }
    for (i, slide) in slides.iter().take(64).enumerate() {
        let body = container(bytes, resolve(slide.id)?, 0x03ee)?;
        let section = doc.section(&format!("Slide {}", i + 1), "slide");
        shapes(body, &slide.outline, 0, &mut doc, section, cx)?;
    }
    Ok(doc)
}
