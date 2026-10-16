use crate::xml::{self, attr, Token, Xml};
use crate::{append, bad, limit, prefix, unsupported, Context, Document, Result, TextBuffer, TEXT};
fn number(a: &[(String, String)], key: &str, default: u32) -> Result<u32> {
    match attr(a, key) {
        Some(v) => v.parse::<u32>().map_err(|_| bad("Invalid ODF repetition")),
        None => Ok(default),
    }
}
// Rows are retained for repetition; bound their combined text before storing cells.
fn retain_text(text: &str, remaining: &mut usize, truncated: &mut bool) -> Option<Box<str>> {
    let part = prefix(text, *remaining);
    *truncated |= part.len() != text.len();
    *remaining -= part.len();
    (!part.is_empty()).then(|| part.into())
}
pub fn parse(bytes: &[u8], cx: &mut Context) -> Result<Document> {
    let source = xml::source(bytes)?;
    let mut xml = Xml::new(&source);
    let mut doc = Document::new("ODF");
    let mut section = None;
    let (mut root, mut body, mut found, mut in_cell, mut in_para) =
        (false, false, false, false, false);
    let mut mode = String::new();
    let (mut paragraph, mut cell, mut scalar) =
        (TextBuffer::default(), TextBuffer::default(), String::new());
    let (mut row, mut column, mut repeat_row, mut repeat_cell) = (0u32, 0u32, 1u32, 1u32);
    let mut cells: Vec<(u32, u32, Box<str>)> = vec![];
    let mut row_remaining = TEXT;
    let mut total_records = 0usize;
    let mut heading = 0;
    let mut notes_depth = 0;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if notes_depth > 0 {
                    notes_depth += 1;
                    continue;
                }
                if body && matches!(n.as_str(), "annotation" | "notes") {
                    notes_depth = 1;
                    continue;
                }
                if !root {
                    if n != "document-content" && n != "document" {
                        return Err(unsupported("XML is not an OpenDocument document"));
                    }
                    root = true;
                }
                if n == "body" {
                    body = true;
                }
                if body
                    && !found
                    && matches!(
                        n.as_str(),
                        "text" | "spreadsheet" | "presentation" | "drawing"
                    )
                {
                    found = true;
                    mode = n.clone();
                    doc.format = match n.as_str() {
                        "text" => "ODT",
                        "spreadsheet" => "ODS",
                        "drawing" => "ODG",
                        _ => "ODP",
                    }
                    .into();
                    if n == "text" {
                        section = doc.section("Document", "document");
                    }
                    if n == "spreadsheet" {
                        doc.warn("Cells show stored values; formulas, formatting and charts are not recalculated or rendered.");
                    }
                }
                if !body || !found {
                    continue;
                }
                match n.as_str() {
                    "page" if mode == "presentation" || mode == "drawing" => {
                        let title = attr(&a, "name")
                            .map(str::to_owned)
                            .unwrap_or_else(|| format!("Slide {}", doc.sections.len() + 1));
                        section = doc.section(&title, "slide");
                    }
                    "table" if mode == "spreadsheet" => {
                        section = doc.section(attr(&a, "name").unwrap_or("Sheet"), "sheet");
                        row = 0;
                    }
                    "table-row" if mode == "spreadsheet" => {
                        repeat_row = number(&a, "number-rows-repeated", 1)?;
                        if repeat_row == 0
                            || row
                                .checked_add(repeat_row)
                                .filter(|v| *v <= 1048576)
                                .is_none()
                        {
                            return Err(limit("ODF row range exceeds preview limits"));
                        }
                        column = 0;
                        cells.clear();
                        row_remaining = TEXT;
                    }
                    "table-cell" | "covered-table-cell" if mode == "spreadsheet" => {
                        if in_cell {
                            return Err(unsupported(
                                "Nested ODF spreadsheet tables are unsupported",
                            ));
                        }
                        in_cell = true;
                        cell.clear();
                        repeat_cell = number(&a, "number-columns-repeated", 1)?;
                        if repeat_cell == 0
                            || column
                                .checked_add(repeat_cell)
                                .filter(|v| *v <= 16384)
                                .is_none()
                        {
                            return Err(limit("ODF column range exceeds preview limits"));
                        }
                        scalar = [
                            "string-value",
                            "date-value",
                            "time-value",
                            "boolean-value",
                            "value",
                        ]
                        .iter()
                        .find_map(|k| attr(&a, k))
                        .unwrap_or("")
                        .into();
                        if attr(&a, "formula").is_some() {
                            doc.warn(
                                "Formula cells show saved values; formulas are not recalculated.",
                            );
                        }
                        total_records += 1;
                        if total_records > 8192 {
                            return Err(limit("ODF cell record limit exceeded"));
                        }
                    }
                    "p" | "h" => {
                        if in_para {
                            return Err(bad("Nested ODF paragraphs"));
                        }
                        in_para = true;
                        paragraph.clear();
                        heading = if n == "h" {
                            number(&a, "outline-level", 1)?.clamp(1, 6) as i32
                        } else {
                            0
                        };
                    }
                    "s" if in_para => {
                        let count = number(&a, "c", 1)?;
                        if count > 65536 {
                            return Err(limit("ODF space repetition exceeds bounds"));
                        }
                        append(
                            &mut paragraph,
                            &" ".repeat(count as usize),
                            &mut doc.truncated,
                        );
                    }
                    "tab" if in_para => append(&mut paragraph, "\t", &mut doc.truncated),
                    "line-break" if in_para => append(&mut paragraph, "\n", &mut doc.truncated),
                    _ => {}
                }
            }
            Token::Text(_) if notes_depth > 0 => {}
            Token::End(_) if notes_depth > 0 => notes_depth -= 1,
            Token::Text(t) if in_para => append(&mut paragraph, &t, &mut doc.truncated),
            Token::End(n) => {
                match n.as_str() {
                    "p" | "h" if in_para => {
                        in_para = false;
                        if in_cell {
                            if !cell.is_empty() {
                                append(&mut cell, "\n", &mut doc.truncated);
                            }
                            cell.append_buffer(&paragraph, &mut doc.truncated);
                        } else {
                            doc.push(
                                section,
                                if heading > 0 { "heading" } else { "paragraph" },
                                &paragraph,
                                -1,
                                -1,
                                heading,
                            );
                        }
                    }
                    "table-cell" | "covered-table-cell" if in_cell => {
                        in_cell = false;
                        if cell.is_empty() {
                            append(&mut cell, &scalar, &mut doc.truncated);
                        }
                        if let Some(text) =
                            retain_text(&cell, &mut row_remaining, &mut doc.truncated)
                        {
                            cells.push((column, repeat_cell, text));
                        }
                        column += repeat_cell;
                    }
                    "table-row" if mode == "spreadsheet" => {
                        if !cells.is_empty() {
                            // Repetition counts advance coordinates; they never reserve a dense grid.
                            let mut emitted = 0usize;
                            'rows: for r in 0..repeat_row {
                                for (c, n, text) in &cells {
                                    for d in 0..*n {
                                        cx.tick()?;
                                        if emitted == 8192 {
                                            doc.truncated = true;
                                            break 'rows;
                                        }
                                        doc.push(
                                            section,
                                            "cell",
                                            text,
                                            (row + r) as i32,
                                            (c + d) as i32,
                                            0,
                                        );
                                        emitted += 1;
                                    }
                                }
                            }
                        }
                        row += repeat_row;
                    }
                    "body" => body = false,
                    _ => {}
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    if !found {
        return Err(unsupported("OpenDocument body type is unsupported"));
    }
    Ok(doc)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn annotations_and_speaker_notes_do_not_replace_body_paragraphs() {
        let xml = br#"<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"><office:body><office:presentation><draw:page draw:name="Slide"><text:p>Before<office:annotation><text:p>Private comment</text:p></office:annotation> after</text:p><presentation:notes><text:p>Speaker note</text:p></presentation:notes><text:p>Visible</text:p></draw:page></office:presentation></office:body></office:document-content>"#;
        let mut cancel = || false;
        let doc = parse(xml, &mut Context::new(&mut cancel)).unwrap();
        assert_eq!(doc.sections[0].blocks.len(), 2);
        assert_eq!(doc.sections[0].blocks[0].text, "Before after");
        assert_eq!(doc.sections[0].blocks[1].text, "Visible");
    }

    #[test]
    fn retained_row_text_has_one_scalar_safe_budget() {
        for limit in [0, 1, 2, 3, 4, 8, 16] {
            let mut remaining = limit;
            let mut truncated = false;
            let mut stored = Vec::new();
            for value in ["é", "😀abc", "last", ""] {
                if let Some(text) = retain_text(value, &mut remaining, &mut truncated) {
                    stored.push(text);
                }
                let bytes: usize = stored.iter().map(|text| text.len()).sum();
                assert_eq!(limit - remaining, bytes);
                assert!(bytes <= limit);
                assert!(stored
                    .iter()
                    .all(|text| std::str::from_utf8(text.as_bytes()).is_ok()));
            }
            assert_eq!(limit < 13, truncated);
        }
        let mut remaining = 3;
        let mut truncated = false;
        assert!(retain_text("😀", &mut remaining, &mut truncated).is_none());
        assert!(truncated);
        assert_eq!(remaining, 3);
        assert_eq!(
            retain_text("abc", &mut remaining, &mut truncated).as_deref(),
            Some("abc")
        );
        assert_eq!(remaining, 0);
        truncated = false;
        assert!(retain_text("later", &mut remaining, &mut truncated).is_none());
        assert!(truncated);
    }

    #[test]
    fn contiguous_cell_truncation_preserves_the_next_sparse_cell() {
        let retained = "a".repeat(TEXT - 1);
        let source = format!(
            "<document><body><spreadsheet><table><table-row><table-cell>\
             <p>{retained}<span>😀</span><span>Z</span></p><p>Q</p></table-cell>\
             <table-cell><p>B</p></table-cell></table-row></table></spreadsheet></body></document>"
        );
        let document = parse(source.as_bytes(), &mut Context::new(&mut || false)).unwrap();
        let blocks = &document.sections[0].blocks;
        assert_eq!(blocks.len(), 2);
        assert_eq!((blocks[0].row, blocks[0].column), (0, 0));
        assert_eq!(blocks[0].text, retained);
        assert_eq!(
            (blocks[1].row, blocks[1].column, blocks[1].text.as_str()),
            (0, 1, "B")
        );
        assert!(document.truncated);
    }

    #[test]
    fn sparse_repeated_rows_keep_coordinates_and_separate_values() {
        let source = br#"<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
            xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
            xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:spreadsheet>
            <table:table><table:table-row table:number-rows-repeated="2">
            <table:table-cell table:number-columns-repeated="3"/>
            <table:table-cell table:number-columns-repeated="2"><text:p>first</text:p></table:table-cell>
            </table:table-row><table:table-row><table:table-cell><text:p>next</text:p></table:table-cell>
            </table:table-row></table:table></office:spreadsheet></office:body></office:document>"#;
        let document = parse(source, &mut Context::new(&mut || false)).unwrap();
        let blocks = &document.sections[0].blocks;
        assert_eq!(
            blocks
                .iter()
                .map(|b| (b.row, b.column, b.text.as_str()))
                .collect::<Vec<_>>(),
            vec![
                (0, 3, "first"),
                (0, 4, "first"),
                (1, 3, "first"),
                (1, 4, "first"),
                (2, 0, "next")
            ]
        );
        assert!(!document.truncated);
    }
}
