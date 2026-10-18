use crate::{append, bad, limit, unsupported, Context, Document, Result, TextBuffer, TEXT};
use crate::{
    package::Package,
    xml::{self, attr, Token, Xml},
};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) fn path(base: &str, target: &str) -> Result<String> {
    if target.len() > 1024 || target.contains(['\\', ':', '?', '#']) {
        return Err(unsupported("Unsupported document relationship target"));
    }
    let mut decoded = Vec::new();
    let mut i = 0;
    let bytes = target.as_bytes();
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let h = std::str::from_utf8(
                bytes
                    .get(i + 1..i + 3)
                    .ok_or_else(|| bad("Invalid relationship escape"))?,
            )
            .map_err(|_| bad("Invalid relationship escape"))?;
            decoded
                .push(u8::from_str_radix(h, 16).map_err(|_| bad("Invalid relationship escape"))?);
            i += 3;
        } else {
            decoded.push(bytes[i]);
            i += 1;
        }
    }
    let target = std::str::from_utf8(&decoded).map_err(|_| bad("Invalid relationship Unicode"))?;
    if target.contains(['\\', ':', '?', '#']) || target.chars().any(|c| c < ' ' || c == '\u{7f}') {
        return Err(bad("Unsafe relationship target"));
    }
    let mut parts: Vec<&str> = if target.starts_with('/') {
        vec![]
    } else {
        base.rsplit_once('/')
            .map(|(p, _)| p.split('/').collect())
            .unwrap_or_default()
    };
    for part in target.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                if parts.pop().is_none() {
                    return Err(bad("Relationship escapes package"));
                }
            }
            p => parts.push(p),
        }
    }
    if parts.is_empty() {
        return Err(bad("Empty relationship target"));
    }
    Ok(parts.join("/"))
}
fn rels(package: &mut Package, base: &str, cx: &mut Context) -> Result<BTreeMap<String, String>> {
    let (directory, file) = base
        .rsplit_once('/')
        .ok_or_else(|| bad("Invalid package base"))?;
    let bytes = package.read(&format!("{directory}/_rels/{file}.rels"), cx)?;
    let text = xml::source(&bytes)?;
    let mut xml = Xml::new(&text);
    let mut result = BTreeMap::new();
    let mut root = false;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if !root {
                    if n != "Relationships" {
                        return Err(bad("Invalid relationship XML root"));
                    }
                    root = true;
                }
                if n == "Relationship" {
                    if result.len() == 4096 {
                        return Err(limit("Too many document relationships"));
                    }
                    let id = attr(&a, "Id").ok_or_else(|| bad("Missing relationship ID"))?;
                    let target =
                        attr(&a, "Target").ok_or_else(|| bad("Missing relationship target"))?;
                    if id.len() > 128 {
                        return Err(limit("Relationship ID is too long"));
                    }
                    let value = if attr(&a, "TargetMode") == Some("External") {
                        String::new()
                    } else {
                        path(base, target)?
                    };
                    if result.insert(id.into(), value).is_some() {
                        return Err(bad("Duplicate relationship ID"));
                    }
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    Ok(result)
}
fn resolve<'a>(rels: &'a BTreeMap<String, String>, id: &str) -> Result<&'a str> {
    let p = rels
        .get(id)
        .ok_or_else(|| bad("Unresolved document relationship"))?;
    if p.is_empty() {
        return Err(unsupported("External document parts are unsupported"));
    }
    Ok(p)
}

pub fn word(package: &mut Package, cx: &mut Context) -> Result<Document> {
    let bytes = package.read("word/document.xml", cx)?;
    word_xml(&bytes, cx)
}
fn word_xml(bytes: &[u8], cx: &mut Context) -> Result<Document> {
    let text = xml::source(bytes)?;
    let mut xml = Xml::new(&text);
    let mut doc = Document::new("DOCX");
    let section = doc.section("Document", "document");
    let mut p = TextBuffer::default();
    let mut cell = TextBuffer::default();
    let (mut capture, mut in_p, mut in_cell, mut root) = (false, false, false, false);
    let (mut row, mut column, mut level) = (-1, -1, 0);
    let (mut paragraph_depth, mut textbox_depth) = (0, 0);
    let mut alternatives = Vec::new();
    let mut skipped = 0;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if skipped > 0 {
                    skipped += 1;
                    continue;
                }
                if n == "AlternateContent" {
                    alternatives.push(false);
                }
                if matches!(n.as_str(), "Choice" | "Fallback") {
                    if let Some(chosen) = alternatives.last_mut() {
                        if *chosen {
                            skipped = 1;
                            continue;
                        }
                        *chosen = true;
                    }
                }
                if !root {
                    if n != "document" {
                        return Err(bad("Invalid Word document root"));
                    }
                    root = true;
                }
                match n.as_str() {
                    "tbl" => {
                        row = -1;
                    }
                    "tr" => {
                        row += 1;
                        column = -1;
                    }
                    "tc" => {
                        if in_cell {
                            return Err(unsupported("Nested Word tables are unsupported"));
                        }
                        in_cell = true;
                        column += 1;
                        cell.clear();
                    }
                    "txbxContent" => textbox_depth += 1,
                    "p" => {
                        if in_p && textbox_depth == 0 {
                            return Err(bad("Nested Word paragraphs"));
                        }
                        if in_p {
                            if !p.is_empty() && !p.ends_with('\n') {
                                append(&mut p, "\n", &mut doc.truncated);
                            }
                        } else {
                            p.clear();
                            level = 0;
                        }
                        in_p = true;
                        paragraph_depth += 1;
                    }
                    "pStyle" if in_p && paragraph_depth == 1 => {
                        if let Some(v) = attr(&a, "val") {
                            let v = v.to_ascii_lowercase();
                            if let Some(v) = v.strip_prefix("heading") {
                                level = v.parse::<i32>().unwrap_or(0).clamp(0, 6);
                            }
                        }
                    }
                    "t" if in_p => capture = true,
                    "tab" if in_p => append(&mut p, "\t", &mut doc.truncated),
                    "br" | "cr" if in_p => append(&mut p, "\n", &mut doc.truncated),
                    _ => {}
                }
            }
            Token::Text(_) if skipped > 0 => {}
            Token::End(_) if skipped > 0 => skipped -= 1,
            Token::Text(t) if capture => append(&mut p, &t, &mut doc.truncated),
            Token::End(n) => match n.as_str() {
                "AlternateContent" => {
                    alternatives.pop();
                }
                "t" => capture = false,
                "txbxContent" => textbox_depth -= 1,
                "p" if paragraph_depth > 1 => {
                    paragraph_depth -= 1;
                    if !p.ends_with('\n') {
                        append(&mut p, "\n", &mut doc.truncated);
                    }
                }
                "p" => {
                    paragraph_depth = 0;
                    in_p = false;
                    if in_cell {
                        if !cell.is_empty() {
                            append(&mut cell, "\n", &mut doc.truncated);
                        }
                        cell.append_buffer(&p, &mut doc.truncated);
                    } else {
                        doc.push(
                            section,
                            if level > 0 { "heading" } else { "paragraph" },
                            &p,
                            -1,
                            -1,
                            level,
                        );
                    }
                }
                "tc" => {
                    in_cell = false;
                    doc.push(section, "cell", &cell, row.max(0), column.max(0), 0);
                }
                _ => {}
            },
            Token::Done => break,
            _ => {}
        }
    }
    Ok(doc)
}

fn paragraphs(
    bytes: &[u8],
    doc: &mut Document,
    section: Option<usize>,
    cx: &mut Context,
) -> Result<()> {
    let text = xml::source(bytes)?;
    let mut xml = Xml::new(&text);
    let mut p = TextBuffer::default();
    let (mut capture, mut in_p, mut root) = (false, false, false);
    loop {
        match xml.next(cx)? {
            Token::Start(n, _) => {
                if !root {
                    if n != "sld" {
                        return Err(bad("Invalid slide XML root"));
                    }
                    root = true;
                }
                match n.as_str() {
                    "p" => {
                        if in_p {
                            return Err(bad("Nested slide paragraphs"));
                        }
                        in_p = true;
                        p.clear();
                    }
                    "t" if in_p => capture = true,
                    "br" if in_p => append(&mut p, "\n", &mut doc.truncated),
                    _ => {}
                }
            }
            Token::Text(t) if capture => append(&mut p, &t, &mut doc.truncated),
            Token::End(n) if n == "t" => capture = false,
            Token::End(n) if n == "p" => {
                doc.paragraph(section, &p);
                in_p = false;
            }
            Token::Done => break,
            _ => {}
        }
    }
    Ok(())
}
pub fn presentation(package: &mut Package, cx: &mut Context) -> Result<Document> {
    let relations = rels(package, "ppt/presentation.xml", cx)?;
    let bytes = package.read("ppt/presentation.xml", cx)?;
    let text = xml::source(&bytes)?;
    let mut xml = Xml::new(&text);
    let mut slides = vec![];
    let mut root = false;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if !root {
                    if n != "presentation" {
                        return Err(bad("Invalid presentation root"));
                    }
                    root = true;
                }
                if n == "sldId" {
                    let id = a
                        .iter()
                        .find(|(k, _)| k.ends_with(":id"))
                        .map(|(_, v)| v)
                        .ok_or_else(|| bad("Missing slide relationship"))?;
                    if slides.len() == 4096 {
                        return Err(limit("Too many slide references"));
                    }
                    slides.push(resolve(&relations, id)?.to_owned());
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    let mut doc = Document::new("PPTX");
    if slides.len() > 64 {
        doc.truncated = true;
    }
    for (i, path) in slides.iter().take(64).enumerate() {
        let section = doc.section(&format!("Slide {}", i + 1), "slide");
        let bytes = package.read(path, cx)?;
        paragraphs(&bytes, &mut doc, section, cx)?;
    }
    Ok(doc)
}

fn shared_strings(package: &mut Package, cx: &mut Context) -> Result<Vec<String>> {
    if !package.has("xl/sharedStrings.xml") {
        return Ok(vec![]);
    }
    let bytes = package.read("xl/sharedStrings.xml", cx)?;
    let text = xml::source(&bytes)?;
    let mut xml = Xml::new(&text);
    let mut strings = vec![];
    let (mut value, mut capture, mut phonetic, mut root) = (String::new(), false, false, false);
    let mut total = 0;
    loop {
        match xml.next(cx)? {
            Token::Start(n, _) => {
                if !root {
                    if n != "sst" {
                        return Err(bad("Invalid shared strings root"));
                    }
                    root = true;
                }
                match n.as_str() {
                    "si" => value.clear(),
                    "rPh" => phonetic = true,
                    "t" if !phonetic => capture = true,
                    _ => {}
                }
            }
            Token::Text(t) if capture => {
                if value.len() + t.len() > TEXT {
                    return Err(limit("Shared string exceeds text limit"));
                }
                value.push_str(&t);
            }
            Token::End(n) => match n.as_str() {
                "rPh" => phonetic = false,
                "t" => capture = false,
                "si" => {
                    total += value.len();
                    if total > 8 * 1024 * 1024 || strings.len() == 65536 {
                        return Err(limit("Shared strings limit exceeded"));
                    }
                    strings.push(std::mem::take(&mut value));
                }
                _ => {}
            },
            Token::Done => break,
            _ => {}
        }
    }
    Ok(strings)
}
fn coordinate(value: &str) -> Result<(i32, i32)> {
    let mut col = 0i32;
    let mut p = 0;
    for c in value.bytes() {
        if !c.is_ascii_uppercase() {
            break;
        }
        col = col
            .checked_mul(26)
            .and_then(|v| v.checked_add((c - b'A' + 1) as i32))
            .ok_or_else(|| bad("Invalid cell reference"))?;
        p += 1;
    }
    let row = value
        .get(p..)
        .ok_or_else(|| bad("Invalid cell reference"))?
        .parse::<i32>()
        .map_err(|_| bad("Invalid cell reference"))?;
    if !(1..=1048576).contains(&row) || !(1..=16384).contains(&col) {
        return Err(bad("Cell reference is out of range"));
    }
    Ok((row - 1, col - 1))
}
fn worksheet(
    bytes: &[u8],
    shared: &[String],
    doc: &mut Document,
    section: Option<usize>,
    cx: &mut Context,
) -> Result<bool> {
    let text = xml::source(bytes)?;
    let mut xml = Xml::new(&text);
    let (mut root, mut active, mut capture, mut phonetic) = (false, false, false, false);
    let (mut row, mut col) = (-1, -1);
    let (mut typ, mut value) = (String::new(), TextBuffer::default());
    let mut seen = BTreeSet::new();
    let mut records = 0usize;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if !root {
                    if n != "worksheet" {
                        return Err(bad("Invalid worksheet root"));
                    }
                    root = true;
                }
                match n.as_str() {
                    "row" => {
                        row = if let Some(r) = attr(&a, "r") {
                            r.parse::<i32>().map_err(|_| bad("Invalid row"))? - 1
                        } else {
                            row + 1
                        };
                        col = -1;
                    }
                    "c" => {
                        if active {
                            return Err(bad("Nested cells"));
                        }
                        if doc.full() || records == 8192 {
                            cx.check()?;
                            doc.truncated = true;
                            doc.warn("Spreadsheet text preview is incomplete; remaining worksheet data was not parsed.");
                            return Ok(false);
                        }
                        active = true;
                        value.clear();
                        typ = attr(&a, "t").unwrap_or("n").into();
                        if let Some(r) = attr(&a, "r") {
                            (row, col) = coordinate(r)?;
                        } else {
                            col += 1;
                        }
                        if !(0..1048576).contains(&row) || !(0..16384).contains(&col) {
                            return Err(bad("Cell reference out of range"));
                        }
                        records += 1;
                        if !seen.insert((row, col)) {
                            return Err(bad("Duplicate worksheet cell"));
                        }
                    }
                    "rPh" => phonetic = true,
                    "v" if active => capture = true,
                    "t" if active && typ == "inlineStr" && !phonetic => capture = true,
                    "f" if active => {
                        doc.warn("Formula cells show saved values; formulas are not recalculated.")
                    }
                    _ => {}
                }
            }
            Token::Text(t) if capture => append(&mut value, &t, &mut doc.truncated),
            Token::End(n) => match n.as_str() {
                "v" | "t" => capture = false,
                "rPh" => phonetic = false,
                "c" => {
                    if !active {
                        return Err(bad("Invalid cell end"));
                    }
                    active = false;
                    let display = if typ == "s" {
                        let index = value
                            .parse::<usize>()
                            .map_err(|_| bad("Invalid shared string reference"))?;
                        shared
                            .get(index)
                            .ok_or_else(|| bad("Missing shared string"))?
                            .as_str()
                    } else if typ == "b" {
                        match value.as_str() {
                            "0" => "FALSE",
                            "1" => "TRUE",
                            _ => return Err(bad("Invalid boolean cell")),
                        }
                    } else {
                        &value
                    };
                    if !display.is_empty() {
                        doc.push(section, "cell", display, row, col, 0);
                    }
                }
                _ => {}
            },
            Token::Done => break,
            _ => {}
        }
    }
    Ok(true)
}
pub fn workbook(package: &mut Package, cx: &mut Context) -> Result<Document> {
    let relations = rels(package, "xl/workbook.xml", cx)?;
    let shared = shared_strings(package, cx)?;
    let bytes = package.read("xl/workbook.xml", cx)?;
    let text = xml::source(&bytes)?;
    let mut xml = Xml::new(&text);
    let mut sheets = vec![];
    let mut root = false;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if !root {
                    if n != "workbook" {
                        return Err(bad("Invalid workbook root"));
                    }
                    root = true;
                }
                if n == "sheet" {
                    if sheets.len() == 4096 {
                        return Err(limit("Too many worksheet references"));
                    }
                    let title = attr(&a, "name").ok_or_else(|| bad("Missing sheet name"))?;
                    let id = a
                        .iter()
                        .find(|(k, _)| k.ends_with(":id"))
                        .map(|(_, v)| v)
                        .ok_or_else(|| bad("Missing sheet relationship"))?;
                    sheets.push((title.to_owned(), resolve(&relations, id)?.to_owned()));
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    let mut doc = Document::new("XLSX");
    doc.warn("Text and JSON exports show stored cell values without spreadsheet number formats or charts; formulas are not recalculated.");
    if sheets.len() > 64 {
        doc.truncated = true;
    }
    for (title, path) in sheets.iter().take(64) {
        let section = doc.section(title, "sheet");
        let bytes = package.read(path, cx)?;
        if !worksheet(&bytes, &shared, &mut doc, section, cx)? {
            break;
        }
    }
    Ok(doc)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fmt::Write;

    #[test]
    fn word_text_boxes_preserve_outer_and_inner_text() {
        let xml = br#"<document><body><p><r><t>Before</t></r><r><pict><shape><textbox><txbxContent><p><r><t>Box first</t></r></p><p><r><t>Box second</t></r></p></txbxContent></textbox></shape></pict></r><r><t>After</t></r></p><p><r><t>Last</t></r></p></body></document>"#;
        let doc = word_xml(xml, &mut Context::new(&mut || false)).unwrap();
        assert_eq!(
            doc.sections[0].blocks[0].text,
            "Before\nBox first\nBox second\nAfter"
        );
        assert_eq!(doc.sections[0].blocks[1].text, "Last");
        let invalid = b"<document><body><p><p><r><t>Nested</t></r></p></p></body></document>";
        assert_eq!(
            word_xml(invalid, &mut Context::new(&mut || false))
                .unwrap_err()
                .code,
            "MALFORMED"
        );
    }

    #[test]
    fn word_alternative_text_boxes_are_read_once() {
        let xml = br#"<document><body><p><r><t>Before</t></r><AlternateContent><Choice Requires="wps"><txbxContent><p><r><t>Box</t></r></p></txbxContent></Choice><Fallback><txbxContent><p><r><t>Box</t></r></p></txbxContent></Fallback></AlternateContent><r><t>After</t></r></p><AlternateContent><Fallback><p><r><t>Fallback only</t></r></p></Fallback></AlternateContent></body></document>"#;
        let doc = word_xml(xml, &mut Context::new(&mut || false)).unwrap();
        assert_eq!(doc.sections[0].blocks[0].text, "Before\nBox\nAfter");
        assert_eq!(doc.sections[0].blocks[1].text, "Fallback only");
    }

    #[test]
    fn worksheet_excerpt_keeps_existing_output_and_record_budgets() {
        for (cells, filled, complete) in [
            (4096, true, true),
            (120012, true, false),
            (8192, false, true),
            (8193, false, false),
        ] {
            let mut text = String::from("<worksheet><sheetData>");
            for i in 0..cells {
                if i % 12 == 0 {
                    text.push_str("<row>");
                }
                write!(
                    text,
                    "<c r=\"{}{}\">",
                    (b'A' + (i % 12) as u8) as char,
                    i / 12 + 1
                )
                .unwrap();
                if filled {
                    write!(text, "<v>{i}</v>").unwrap();
                }
                text.push_str("</c>");
                if i % 12 == 11 || i + 1 == cells {
                    text.push_str("</row>");
                }
            }
            text.push_str("</sheetData></worksheet>");
            let mut doc = Document::new("XLSX");
            let section = doc.section("Data", "sheet");
            let mut cancel = || false;
            let mut cx = Context::new(&mut cancel);
            assert_eq!(
                worksheet(text.as_bytes(), &[], &mut doc, section, &mut cx).unwrap(),
                complete
            );
            assert_eq!(doc.truncated, !complete);
            assert_eq!(doc.blocks, if filled { cells.min(4096) } else { 0 });
            assert_eq!(
                doc.warnings
                    .iter()
                    .any(|w| w.contains("remaining worksheet data was not parsed")),
                !complete
            );
            if filled {
                assert_eq!(doc.sections[0].blocks.first().unwrap().text, "0");
                assert_eq!(doc.sections[0].blocks.last().unwrap().text, "4095");
            }
        }
    }

    #[test]
    fn worksheet_excerpt_preserves_processed_validation_and_cancellation() {
        for text in [
            "<worksheet><row><c r=\"A1\"><v>1</v></c><c r=\"A1\"/></row></worksheet>",
            "<worksheet><row><c r=\"A1\"><v>1</c></row></worksheet>",
            "<worksheet><row><c r=\"A1\"><c r=\"A2\"/></c></row></worksheet>",
        ] {
            let mut doc = Document::new("XLSX");
            let section = doc.section("Data", "sheet");
            let mut cancel = || false;
            assert_eq!(
                worksheet(
                    text.as_bytes(),
                    &[],
                    &mut doc,
                    section,
                    &mut Context::new(&mut cancel)
                )
                .unwrap_err()
                .code,
                "MALFORMED"
            );
        }
        let mut doc = Document::new("XLSX");
        let section = doc.section("Data", "sheet");
        for _ in 0..4096 {
            doc.push(section, "cell", "1", 0, 0, 0);
        }
        let mut cancel = || true;
        assert_eq!(
            worksheet(
                b"<worksheet><row><c r=\"A1\"/></row></worksheet>",
                &[],
                &mut doc,
                section,
                &mut Context::new(&mut cancel)
            )
            .unwrap_err()
            .code,
            "CANCELLED"
        );
    }
}
