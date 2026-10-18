//! Iterative RTF text subset. Destinations/binary payloads are skipped, never executed.
use crate::{append, bad, limit, unsupported, Context, Document, Result, TextBuffer};
use encoding_rs::{Encoding, WINDOWS_1252};
#[derive(Clone, Copy)]
struct State {
    skip: bool,
    uc: usize,
    encoding: &'static Encoding,
}
fn flush(
    raw: &mut Vec<u8>,
    text: &mut TextBuffer,
    doc: &mut Document,
    encoding: &'static Encoding,
) -> Result<()> {
    if raw.is_empty() {
        return Ok(());
    }
    let (value, _, errors) = encoding.decode(raw);
    if errors {
        return Err(bad("Malformed RTF codepage text"));
    }
    append(text, &value, &mut doc.truncated);
    raw.clear();
    Ok(())
}
fn unicode(
    value: u16,
    high: &mut Option<u16>,
    text: &mut TextBuffer,
    doc: &mut Document,
) -> Result<()> {
    if (0xd800..=0xdbff).contains(&value) {
        if high.replace(value).is_some() {
            return Err(bad("Unpaired RTF surrogate"));
        }
        return Ok(());
    }
    let scalar = if (0xdc00..=0xdfff).contains(&value) {
        let h = high.take().ok_or_else(|| bad("Unpaired RTF surrogate"))?;
        0x10000 + (((h as u32 - 0xd800) << 10) | (value as u32 - 0xdc00))
    } else {
        if high.is_some() {
            return Err(bad("Unpaired RTF surrogate"));
        }
        value as u32
    };
    let c = char::from_u32(scalar).ok_or_else(|| bad("Invalid RTF Unicode"))?;
    append(text, c.encode_utf8(&mut [0; 4]), &mut doc.truncated);
    Ok(())
}
pub fn parse(bytes: &[u8], cx: &mut Context) -> Result<Document> {
    let mut doc = Document::new("RTF");
    doc.warn("RTF preview omits pictures, objects, headers, footers and font-specific codepages.");
    let section = doc.section("Document", "document");
    let mut state = State {
        skip: false,
        uc: 1,
        encoding: WINDOWS_1252,
    };
    let mut stack = vec![];
    let (mut text, mut raw) = (TextBuffer::default(), Vec::new());
    let mut i = 0;
    let mut fallback = 0usize;
    let mut high = None;
    let mut closed = false;
    while i < bytes.len() {
        let b = bytes[i];
        if matches!(b, b'{' | b'}' | b'\\') || i % 4096 == 0 {
            cx.tick()?;
        }
        if i % 4096 == 0 {
            cx.check()?;
        }
        i += 1;
        if closed {
            if !b.is_ascii_whitespace() {
                return Err(bad("Trailing RTF data"));
            }
            continue;
        }
        match b {
            b'{' => {
                fallback = 0;
                flush(&mut raw, &mut text, &mut doc, state.encoding)?;
                if stack.len() == 64 {
                    return Err(limit("RTF nesting limit exceeded"));
                }
                stack.push(state);
            }
            b'}' => {
                fallback = 0;
                flush(&mut raw, &mut text, &mut doc, state.encoding)?;
                state = stack.pop().ok_or_else(|| bad("Unbalanced RTF group"))?;
                if stack.is_empty() {
                    closed = true;
                }
            }
            b'\r' | b'\n' => {}
            b'\\' => {
                let c = *bytes.get(i).ok_or_else(|| bad("Truncated RTF escape"))?;
                i += 1;
                if matches!(c, b'\\' | b'{' | b'}') {
                    if fallback > 0 {
                        fallback -= 1;
                    } else if !state.skip {
                        raw.push(c);
                    }
                    continue;
                }
                if c == b'\'' {
                    let h = std::str::from_utf8(
                        bytes
                            .get(i..i + 2)
                            .ok_or_else(|| bad("Truncated RTF hex escape"))?,
                    )
                    .map_err(|_| bad("Invalid RTF hex escape"))?;
                    let value =
                        u8::from_str_radix(h, 16).map_err(|_| bad("Invalid RTF hex escape"))?;
                    i += 2;
                    if fallback > 0 {
                        fallback -= 1;
                    } else if !state.skip {
                        raw.push(value);
                    }
                    continue;
                }
                flush(&mut raw, &mut text, &mut doc, state.encoding)?;
                // A whole control symbol counts as one Unicode fallback token.
                if fallback > 0 && !c.is_ascii_alphabetic() {
                    fallback -= 1;
                    continue;
                }
                if c == b'*' {
                    state.skip = true;
                    continue;
                }
                if matches!(c, b'~' | b'-' | b'_') {
                    if fallback > 0 {
                        fallback -= 1;
                    } else if !state.skip {
                        append(
                            &mut text,
                            if c == b'~' {
                                "\u{a0}"
                            } else if c == b'_' {
                                "\u{2011}"
                            } else {
                                "\u{ad}"
                            },
                            &mut doc.truncated,
                        );
                    }
                    continue;
                }
                if !c.is_ascii_alphabetic() {
                    continue;
                }
                let start = i - 1;
                while i < bytes.len() && bytes[i].is_ascii_alphabetic() {
                    i += 1;
                    if i - start > 32 {
                        return Err(limit("RTF control word exceeds limit"));
                    }
                }
                let word = &bytes[start..i];
                let number_start = i;
                if bytes.get(i) == Some(&b'-') {
                    i += 1;
                }
                while i < bytes.len() && bytes[i].is_ascii_digit() {
                    i += 1;
                    if i - number_start > 11 {
                        return Err(bad("RTF numeric overflow"));
                    }
                }
                let number = if i > number_start {
                    Some(
                        std::str::from_utf8(&bytes[number_start..i])
                            .unwrap()
                            .parse::<i32>()
                            .map_err(|_| bad("Invalid RTF number"))?,
                    )
                } else {
                    None
                };
                if bytes.get(i) == Some(&b' ') {
                    i += 1;
                }
                if word == b"bin" {
                    fallback = fallback.saturating_sub(1);
                    let n = number
                        .filter(|n| *n >= 0)
                        .ok_or_else(|| bad("Invalid RTF binary length"))?
                        as usize;
                    i = i
                        .checked_add(n)
                        .filter(|n| *n <= bytes.len())
                        .ok_or_else(|| bad("Truncated RTF binary data"))?;
                    continue;
                }
                // Ignore the complete fallback word, including its parameter.
                if fallback > 0 {
                    fallback -= 1;
                    continue;
                }
                if matches!(
                    word,
                    b"fonttbl"
                        | b"colortbl"
                        | b"stylesheet"
                        | b"info"
                        | b"pict"
                        | b"object"
                        | b"fldinst"
                        | b"header"
                        | b"footer"
                        | b"headerl"
                        | b"headerr"
                        | b"footerl"
                        | b"footerr"
                        | b"datafield"
                        | b"themedata"
                        | b"datastore"
                        | b"xmlnstbl"
                ) {
                    state.skip = true;
                    continue;
                }
                if state.skip {
                    continue;
                }
                match word {
                    b"uc" => {
                        state.uc = number
                            .filter(|v| *v >= 0 && *v <= 16)
                            .ok_or_else(|| bad("Invalid RTF Unicode fallback count"))?
                            as usize;
                    }
                    b"u" => {
                        let n = number
                            .filter(|v| *v >= -32768 && *v <= 65535)
                            .ok_or_else(|| bad("Invalid RTF Unicode value"))?;
                        unicode(n as u16, &mut high, &mut text, &mut doc)?;
                        fallback = state.uc;
                    }
                    b"par" | b"line" | b"page" => {
                        if high.is_some() {
                            return Err(bad("Unpaired RTF surrogate"));
                        }
                        doc.paragraph(section, &text);
                        text.clear();
                    }
                    b"row" | b"nestrow" => {
                        doc.paragraph(section, text.trim_end_matches('\t'));
                        text.clear();
                    }
                    b"tab" | b"cell" | b"nestcell" => append(&mut text, "\t", &mut doc.truncated),
                    b"lquote" => append(&mut text, "‘", &mut doc.truncated),
                    b"rquote" => append(&mut text, "’", &mut doc.truncated),
                    b"ldblquote" => append(&mut text, "“", &mut doc.truncated),
                    b"rdblquote" => append(&mut text, "”", &mut doc.truncated),
                    b"emdash" => append(&mut text, "\u{2014}", &mut doc.truncated),
                    b"endash" => append(&mut text, "\u{2013}", &mut doc.truncated),
                    b"bullet" => append(&mut text, "\u{2022}", &mut doc.truncated),
                    b"ansicpg" => {
                        let n = number.ok_or_else(|| bad("Missing RTF codepage"))?;
                        let label = match n {
                            65001 => "utf-8".into(),
                            932 => "shift_jis".into(),
                            936 => "gbk".into(),
                            949 => "euc-kr".into(),
                            950 => "big5".into(),
                            n => format!("windows-{n}"),
                        };
                        state.encoding = Encoding::for_label(label.as_bytes())
                            .ok_or_else(|| unsupported("RTF codepage is unsupported"))?;
                    }
                    b"mac" | b"pc" | b"pca" | b"upr" => {
                        return Err(unsupported("This RTF text encoding variant is unsupported"))
                    }
                    _ => {}
                }
            }
            _ => {
                if stack.is_empty() {
                    return Err(bad("RTF text outside a group"));
                }
                if fallback > 0 {
                    fallback -= 1;
                } else if !state.skip {
                    if high.is_some() {
                        return Err(bad("Unpaired RTF surrogate"));
                    }
                    raw.push(b);
                    if raw.len() >= 16384 && state.encoding.is_single_byte() {
                        flush(&mut raw, &mut text, &mut doc, state.encoding)?;
                        cx.check()?;
                    }
                }
            }
        }
        if raw.len() > 8 * 1024 * 1024 {
            return Err(limit("RTF text run exceeds bounds"));
        }
    }
    if !closed || !stack.is_empty() || high.is_some() {
        return Err(bad("Truncated RTF document"));
    }
    flush(&mut raw, &mut text, &mut doc, state.encoding)?;
    if !text.is_empty() {
        doc.paragraph(section, &text);
    }
    Ok(doc)
}

#[cfg(test)]
mod regression_tests {
    use super::*;
    fn document(input: &[u8]) -> Document {
        let mut cancel = || false;
        parse(input, &mut Context::new(&mut cancel)).unwrap()
    }
    fn text(input: &[u8]) -> String {
        document(input)
            .sections
            .into_iter()
            .flat_map(|s| s.blocks)
            .map(|b| b.text)
            .collect::<Vec<_>>()
            .join("\n")
    }
    #[test]
    fn table_cells_rows_and_typographic_quotes_remain_readable() {
        assert_eq!(
            text(br"{\rtf1\trowd Name\cell Value\cell\row\trowd Sea\cell 12\cell\row}"),
            "Name\tValue\nSea\t12"
        );
        assert_eq!(
            text(br"{\rtf1 \ldblquote Read\rdblquote  and \lquote write\rquote .}"),
            "“Read” and ‘write’."
        );
    }

    #[test]
    fn page_breaks_preserve_semantic_text_boundaries() {
        assert_eq!(
            text(br"{\rtf1 Present view shows one page at a time.\page Second page\par Keep reading.}"),
            "Present view shows one page at a time.\nSecond page\nKeep reading."
        );
        assert_eq!(
            text(br"{\rtf1 First\page\page Second\line Third}"),
            "First\n\nSecond\nThird"
        );
    }

    #[test]
    fn unicode_fallback_counts_whole_tokens() {
        for input in [
            br"{\rtf1\ansi\uc1\u945\tab X}".as_slice(),
            br"{\rtf1\ansi\uc1\u945\b X}",
            br"{\rtf1\ansi\uc1\u945\bin3 abcX}",
            br"{\rtf1\ansi\uc1\u945\*X}",
            br"{\rtf1\ansi\uc1\u945\;X}",
            br"{\rtf1\ansi\uc1\u945\~X}",
            br"{\rtf1\ansi\uc1\u945\'3fX}",
            br"{\rtf1\ansi\uc1\u945\\X}",
            br"{\rtf1\ansi\uc1\u945?X}",
        ] {
            assert_eq!(text(input), "αX", "{input:?}");
        }
        assert_eq!(text(br"{\rtf1\ansi\uc1\u8226\bullet X}"), "•X");
    }
    #[test]
    fn unicode_fallback_ends_at_group_boundaries() {
        for input in [
            br"{\rtf1\ansi{\uc1\u945}X}".as_slice(),
            br"{\rtf1\ansi\uc1\u945{X}}",
            br"{\rtf1\ansi\uc2\u945?{X}}",
        ] {
            assert_eq!(text(input), "αX");
        }
    }
    #[test]
    fn unicode_fallback_preserves_surrogate_pairs() {
        assert_eq!(text(br"{\rtf1\ansi\uc1\u-10179?\u-8704?X}"), "😀X");
        assert_eq!(text(br"{\rtf1\ansi\uc0\u-10179\u-8704X}"), "😀X");
    }
    #[test]
    fn unicode_fallback_does_not_hide_binary_truncation() {
        let mut cancel = || false;
        let error = parse(
            br"{\rtf1\uc1\u945\bin20 abc}",
            &mut Context::new(&mut cancel),
        )
        .unwrap_err();
        assert_eq!(error.code, "MALFORMED");
    }
}
