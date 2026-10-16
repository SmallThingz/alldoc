//! EPUB semantic chapters in OPF spine order. No CSS, scripts or external fetches.
use crate::{append, bad, limit, unsupported, Context, Document, Result, TextBuffer};
use crate::{
    office::path,
    package::Package,
    xml::{self, attr, Token, Xml},
};
use std::collections::BTreeMap;
fn chapter(
    bytes: &[u8],
    doc: &mut Document,
    section: Option<usize>,
    cx: &mut Context,
) -> Result<()> {
    let source = xml::source(bytes)?;
    // Bare HTML5 doctype defines no external or internal entities. Other DTDs
    // remain rejected by the shared XML reader; no entity/resource resolution.
    let mut xml = Xml::xhtml(&source);
    let (mut root, mut body) = (false, false);
    let mut skipped = 0usize;
    let mut text = TextBuffer::default();
    let mut level = 0;
    fn flush(doc: &mut Document, section: Option<usize>, text: &mut TextBuffer, level: i32) {
        if !text.trim().is_empty() {
            doc.push(
                section,
                if level > 0 { "heading" } else { "paragraph" },
                text.trim(),
                -1,
                -1,
                level,
            );
        }
        text.clear();
    }
    loop {
        match xml.next(cx)? {
            Token::Start(n, _) => {
                if !root {
                    if n != "html" {
                        return Err(unsupported("EPUB spine item is not XHTML"));
                    }
                    root = true;
                }
                if skipped > 0 {
                    skipped += 1;
                    continue;
                }
                if matches!(n.as_str(), "script" | "style" | "template" | "noscript") {
                    skipped = 1;
                    continue;
                }
                if n == "body" {
                    body = true;
                }
                if body
                    && matches!(
                        n.as_str(),
                        "p" | "div"
                            | "li"
                            | "pre"
                            | "blockquote"
                            | "h1"
                            | "h2"
                            | "h3"
                            | "h4"
                            | "h5"
                            | "h6"
                            | "td"
                            | "th"
                    )
                {
                    flush(doc, section, &mut text, level);
                    level = if n.len() == 2 && n.starts_with('h') {
                        n[1..].parse().unwrap_or(0)
                    } else {
                        0
                    };
                }
                if body && n == "br" {
                    append(&mut text, "\n", &mut doc.truncated);
                }
            }
            Token::Text(t) if body && skipped == 0 => append(&mut text, &t, &mut doc.truncated),
            Token::End(n) => {
                if skipped > 0 {
                    skipped -= 1;
                    continue;
                }
                if body
                    && matches!(
                        n.as_str(),
                        "p" | "div"
                            | "li"
                            | "pre"
                            | "blockquote"
                            | "h1"
                            | "h2"
                            | "h3"
                            | "h4"
                            | "h5"
                            | "h6"
                            | "td"
                            | "th"
                            | "body"
                    )
                {
                    flush(doc, section, &mut text, level);
                    level = 0;
                }
                if n == "body" {
                    body = false;
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    Ok(())
}
pub fn parse(package: &mut Package, cx: &mut Context) -> Result<Document> {
    if !package.has("mimetype") || package.read("mimetype", cx)? != b"application/epub+zip" {
        return Err(unsupported("Unsupported book package type"));
    }
    if package.has("META-INF/encryption.xml") {
        return Err(unsupported(
            "EPUB encrypted or obfuscated resources are unsupported",
        ));
    }
    let data = package.read("META-INF/container.xml", cx)?;
    let source = xml::source(&data)?;
    let mut xml = Xml::new(&source);
    let mut rootfile = None;
    let mut root = false;
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if !root {
                    if n != "container" {
                        return Err(bad("Invalid EPUB container root"));
                    }
                    root = true;
                }
                if n == "rootfile"
                    && attr(&a, "media-type") == Some("application/oebps-package+xml")
                    && rootfile.is_none()
                {
                    rootfile = Some(path(
                        "root",
                        attr(&a, "full-path").ok_or_else(|| bad("Missing EPUB rootfile path"))?,
                    )?);
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    let rootfile = rootfile.ok_or_else(|| unsupported("No supported EPUB package document"))?;
    let data = package.read(&rootfile, cx)?;
    let source = xml::source(&data)?;
    let mut xml = Xml::new(&source);
    let mut items = BTreeMap::new();
    let mut spine = vec![];
    let (mut root, mut manifest, mut in_spine) = (false, false, false);
    loop {
        match xml.next(cx)? {
            Token::Start(n, a) => {
                if !root {
                    if n != "package" {
                        return Err(bad("Invalid EPUB OPF root"));
                    }
                    root = true;
                }
                if n == "manifest" {
                    manifest = true;
                }
                if n == "spine" {
                    in_spine = true;
                }
                if manifest && n == "item" {
                    if items.len() == 4096 {
                        return Err(limit("EPUB manifest count exceeds bounds"));
                    }
                    let id = attr(&a, "id").ok_or_else(|| bad("Missing EPUB manifest ID"))?;
                    let href = attr(&a, "href").ok_or_else(|| bad("Missing EPUB item path"))?;
                    let media = attr(&a, "media-type").unwrap_or("");
                    if items
                        .insert(id.to_owned(), (href.to_owned(), media.to_owned()))
                        .is_some()
                    {
                        return Err(bad("Duplicate EPUB manifest ID"));
                    }
                }
                if in_spine && n == "itemref" && attr(&a, "linear") != Some("no") {
                    if spine.len() == 4096 {
                        return Err(limit("EPUB spine count exceeds bounds"));
                    }
                    spine.push(
                        attr(&a, "idref")
                            .ok_or_else(|| bad("Missing EPUB spine reference"))?
                            .to_owned(),
                    );
                }
            }
            Token::End(n) => {
                if n == "manifest" {
                    manifest = false;
                }
                if n == "spine" {
                    in_spine = false;
                }
            }
            Token::Done => break,
            _ => {}
        }
    }
    let mut doc = Document::new("EPUB");
    doc.warn("Book text follows the linear OPF spine; CSS, images, scripts, navigation-only items and page geometry are omitted.");
    if spine.len() > 64 {
        doc.truncated = true;
    }
    for (i, id) in spine.iter().take(64).enumerate() {
        let (href, media) = items
            .get(id)
            .ok_or_else(|| bad("Unresolved EPUB spine reference"))?;
        if media != "application/xhtml+xml" {
            return Err(unsupported("EPUB spine media type is unsupported"));
        }
        let part = path(&rootfile, href)?;
        let data = package.read(&part, cx)?;
        let section = doc.section(&format!("Chapter {}", i + 1), "document");
        chapter(&data, &mut doc, section, cx)?;
    }
    Ok(doc)
}
