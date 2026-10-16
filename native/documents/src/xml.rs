use crate::{bad, limit, unsupported, Context, Result};
use quick_xml::{events::Event, Reader};
pub enum Token {
    Start(String, Vec<(String, String)>),
    End(String),
    Text(String),
    Done,
}
pub fn attr<'a>(attrs: &'a [(String, String)], name: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find(|(k, _)| k.rsplit(':').next() == Some(name))
        .map(|(_, v)| v.as_str())
}
pub fn source(bytes: &[u8]) -> Result<String> {
    if bytes.len() > 8 * 1024 * 1024 {
        return Err(limit("Document XML part exceeds 8 MiB"));
    }
    let text = if bytes.starts_with(b"\xff\xfe") {
        crate::utf16(&bytes[2..])?
    } else if bytes.starts_with(b"\xfe\xff") {
        if bytes.len() % 2 != 0 {
            return Err(bad("Truncated UTF-16 XML"));
        }
        String::from_utf16(
            &bytes[2..]
                .chunks_exact(2)
                .map(|b| u16::from_be_bytes([b[0], b[1]]))
                .collect::<Vec<_>>(),
        )
        .map_err(|_| bad("Invalid UTF-16 XML"))?
    } else {
        std::str::from_utf8(bytes.strip_prefix(b"\xef\xbb\xbf").unwrap_or(bytes))
            .map_err(|_| unsupported("Document XML encoding is unsupported"))?
            .to_owned()
    };
    if text
        .chars()
        .any(|c| c < ' ' && !matches!(c, '\t' | '\n' | '\r'))
    {
        return Err(bad("Invalid XML control character"));
    }
    Ok(text)
}
pub struct Xml<'a> {
    reader: Reader<&'a [u8]>,
    stack: Vec<String>,
    roots: usize,
    pending: Option<String>,
    html_doctype: bool,
}
fn text(value: impl AsRef<str>) -> Result<String> {
    Ok(value.as_ref().to_owned())
}
fn name(value: &str) -> Result<String> {
    Ok(value.rsplit(':').next().unwrap_or("").into())
}
fn unescape(s: &str) -> Result<String> {
    let value =
        quick_xml::escape::unescape(s).map_err(|_| bad("Unknown or malformed XML entity"))?;
    if value
        .chars()
        .any(|c| c < ' ' && !matches!(c, '\t' | '\n' | '\r'))
    {
        return Err(bad("Invalid XML character reference"));
    }
    Ok(value.into_owned())
}
impl<'a> Xml<'a> {
    pub fn new(text: &'a str) -> Self {
        Self {
            reader: Reader::from_str(text),
            stack: vec![],
            roots: 0,
            pending: None,
            html_doctype: false,
        }
    }
    pub fn xhtml(text: &'a str) -> Self {
        let mut reader = Self::new(text);
        reader.html_doctype = true;
        reader
    }
    pub fn next(&mut self, cx: &mut Context) -> Result<Token> {
        if let Some(n) = self.pending.take() {
            self.stack.pop();
            return Ok(Token::End(n));
        }
        loop {
            cx.tick()?;
            let event = self
                .reader
                .read_event()
                .map_err(|_| bad("Malformed document XML"))?;
            let empty = matches!(&event, Event::Empty(_));
            match event {
                Event::Start(e) | Event::Empty(e) => {
                    let n = name(e.name().as_ref())?;
                    if self.stack.len() == 64 {
                        return Err(limit("XML nesting limit exceeded"));
                    }
                    if self.stack.is_empty() {
                        self.roots += 1;
                        if self.roots != 1 {
                            return Err(bad("Multiple XML roots"));
                        }
                    }
                    let mut attrs = vec![];
                    for a in e.attributes() {
                        let a = a.map_err(|_| bad("Invalid XML attribute"))?;
                        if attrs.len() == 64 || a.value.len() > 65536 {
                            return Err(limit("XML attribute limit exceeded"));
                        }
                        attrs.push((text(a.key.as_ref())?, unescape(&text(&a.value)?)?));
                    }
                    self.stack.push(n.clone());
                    if empty {
                        self.pending = Some(n.clone());
                    }
                    return Ok(Token::Start(n, attrs));
                }
                Event::End(e) => {
                    let n = name(e.name().as_ref())?;
                    if self.stack.pop().as_deref() != Some(&n) {
                        return Err(bad("Unbalanced XML"));
                    }
                    return Ok(Token::End(n));
                }
                Event::Text(e) => {
                    let t = unescape(&text(e.as_ref())?)?;
                    if self.stack.is_empty() && !t.trim().is_empty() {
                        return Err(bad("Text outside XML root"));
                    }
                    return Ok(Token::Text(t));
                }
                Event::CData(e) => {
                    if self.stack.is_empty() {
                        return Err(bad("CDATA outside XML root"));
                    }
                    return Ok(Token::Text(text(e.as_ref())?));
                }
                Event::GeneralRef(e) => {
                    let t = unescape(&format!("&{};", text(e.as_ref())?))?;
                    if self.stack.is_empty() {
                        return Err(bad("Entity outside XML root"));
                    }
                    return Ok(Token::Text(t));
                }
                Event::DocType(e) => {
                    if !(self.html_doctype
                        && self.roots == 0
                        && e.as_ref().trim().eq_ignore_ascii_case("html"))
                    {
                        return Err(unsupported("Document DTDs are unsupported"));
                    }
                }
                Event::Decl(e) => {
                    if let Some(encoding) = e.encoding() {
                        let encoding = encoding.map_err(|_| bad("Invalid XML encoding"))?;
                        let enc = text(&encoding)?.to_ascii_lowercase();
                        if !matches!(enc.as_str(), "utf-8" | "utf-16" | "utf-16le" | "utf-16be") {
                            return Err(unsupported("Document XML encoding is unsupported"));
                        }
                    }
                }
                Event::Eof => {
                    if !self.stack.is_empty() || self.roots != 1 {
                        return Err(bad("Truncated document XML"));
                    }
                    return Ok(Token::Done);
                }
                _ => {}
            }
        }
    }
}
