//! Read text attached to markup annotations. Never follows actions or embedded files.
use lopdf::{Dictionary, Document};

const MAX_ENTRIES: usize = 4096;
const MAX_NODES: usize = 20_000;
const MAX_FIELD: usize = 65_536;
const MAX_NOTE: usize = 16_384;

#[derive(Debug, PartialEq)]
pub struct Entry {
    pub text: String,
    pub author: String,
    pub kind: &'static str,
    pub page: usize,
}
#[derive(Debug, Default)]
pub struct Notes {
    pub entries: Vec<Entry>,
    pub truncated: bool,
}

fn text(
    doc: &Document,
    node: &Dictionary,
    key: &[u8],
    limit: usize,
    truncated: &mut bool,
) -> Option<String> {
    let value = doc.dereference(node.get(key).ok()?).ok()?.1;
    if value.as_str().ok()?.len() > MAX_FIELD {
        *truncated = true;
        return None;
    }
    let decoded = lopdf::decode_text_string(value).ok()?;
    let decoded = decoded.trim_start_matches('\u{feff}').trim();
    let mut end = decoded.len().min(limit);
    while !decoded.is_char_boundary(end) {
        end -= 1;
    }
    *truncated |= end < decoded.len();
    Some(decoded[..end].to_owned())
}

pub fn read(doc: &Document) -> Notes {
    let mut result = Notes::default();
    let mut nodes = 0;
    let mut remaining = super::MAX_TEXT;
    let pages = doc.get_pages();
    result.truncated = pages.len() > super::MAX_PAGES;
    'pages: for (page, id) in pages.into_iter().take(super::MAX_PAGES) {
        let annotations = doc
            .get_dictionary(id)
            .ok()
            .and_then(|node| node.get(b"Annots").ok())
            .and_then(|value| doc.dereference(value).ok())
            .and_then(|(_, value)| value.as_array().ok());
        let Some(annotations) = annotations else {
            continue;
        };
        for annotation in annotations {
            nodes += 1;
            if nodes > MAX_NODES || result.entries.len() == MAX_ENTRIES || remaining == 0 {
                result.truncated = true;
                break 'pages;
            }
            let Some(node) = doc
                .dereference(annotation)
                .ok()
                .and_then(|(_, value)| value.as_dict().ok())
            else {
                continue;
            };
            let subtype = node
                .get(b"Subtype")
                .ok()
                .and_then(|value| doc.dereference(value).ok())
                .and_then(|(_, value)| value.as_name().ok());
            let kind = match subtype {
                Some(b"Text") => "comment",
                Some(b"FreeText") => "text",
                Some(b"Highlight") => "highlight",
                Some(b"Underline") => "underline",
                Some(b"Squiggly") => "squiggly",
                Some(b"StrikeOut") => "strikeout",
                Some(b"Stamp") => "stamp",
                Some(b"Ink") => "ink",
                Some(b"Line") => "line",
                Some(b"Square") => "rectangle",
                Some(b"Circle") => "ellipse",
                Some(b"Polygon") => "polygon",
                Some(b"PolyLine") => "polyline",
                Some(b"Caret") => "caret",
                Some(b"Redact") => "redaction",
                _ => continue,
            };
            let Some(body) = text(
                doc,
                node,
                b"Contents",
                MAX_NOTE.min(remaining),
                &mut result.truncated,
            )
            .filter(|value| !value.is_empty()) else {
                continue;
            };
            remaining -= body.len();
            let author = text(doc, node, b"T", 512.min(remaining), &mut result.truncated)
                .unwrap_or_default();
            remaining -= author.len();
            result.entries.push(Entry {
                text: body,
                author,
                kind,
                page: page.saturating_sub(1) as usize,
            });
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use lopdf::{dictionary, Object};

    fn document(annotations: Vec<Object>) -> Document {
        let mut doc = Document::with_version("1.7");
        doc.trailer.set("Root", (1, 0));
        doc.objects.insert(
            (1, 0),
            dictionary! {"Type"=>"Catalog", "Pages"=>(2,0)}.into(),
        );
        doc.objects.insert(
            (2, 0),
            dictionary! {"Type"=>"Pages", "Kids"=>vec![Object::Reference((3,0))], "Count"=>1}
                .into(),
        );
        doc.objects.insert(
            (3, 0),
            dictionary! {"Type"=>"Page", "Parent"=>(2,0), "Annots"=>annotations}.into(),
        );
        doc
    }

    #[test]
    fn preserves_markup_unicode_and_order_without_following_actions_or_attachments() {
        let note = |kind: &str, value: &str| -> Object {
            dictionary! {"Subtype"=>kind, "Contents"=>lopdf::text_string(value), "T"=>lopdf::text_string("माया")}.into()
        };
        let mut doc = document(vec![
            note("Text", "  Check 😀\nSecond line  "),
            Object::Reference((4, 0)),
            note("Popup", "Duplicate"),
            note("FileAttachment", "Private attachment"),
            note("Link", "External action"),
            note("Text", " "),
            Object::Reference((99, 0)),
        ]);
        doc.objects.insert((4, 0), note("Highlight", "Keep this"));
        let notes = read(&doc);
        assert!(!notes.truncated);
        assert_eq!(
            notes.entries,
            vec![
                Entry {
                    text: "Check 😀\nSecond line".into(),
                    author: "माया".into(),
                    kind: "comment",
                    page: 0
                },
                Entry {
                    text: "Keep this".into(),
                    author: "माया".into(),
                    kind: "highlight",
                    page: 0
                },
            ]
        );
    }

    #[test]
    fn bounds_output_on_unicode_and_marks_omission() {
        let mut doc = document(vec![
            dictionary! {"Subtype"=>"Text", "Contents"=>lopdf::text_string(&"😀".repeat(5000))}
                .into(),
        ]);
        let notes = read(&doc);
        assert!(notes.truncated);
        assert!(notes.entries[0].text.len() <= MAX_NOTE);
        assert!(notes.entries[0].text.ends_with('😀'));
        doc.get_dictionary_mut((3, 0)).unwrap().set("Annots", vec![
            dictionary! {"Subtype"=>"Text", "Contents"=>Object::string_literal("x".repeat(MAX_FIELD + 1))}.into()
        ]);
        let notes = read(&doc);
        assert!(notes.truncated);
        assert!(notes.entries.is_empty());
        let note: Object =
            dictionary! {"Subtype"=>"Text", "Contents"=>lopdf::text_string("Comment")}.into();
        let notes = read(&document(vec![note; MAX_ENTRIES + 1]));
        assert_eq!(notes.entries.len(), MAX_ENTRIES);
        assert!(notes.truncated);
        let note: Object =
            dictionary! {"Subtype"=>"Text", "Contents"=>lopdf::text_string(&"x".repeat(MAX_NOTE))}
                .into();
        let notes = read(&document(vec![note; super::super::MAX_TEXT / MAX_NOTE + 2]));
        assert_eq!(
            notes
                .entries
                .iter()
                .map(|entry| entry.text.len())
                .sum::<usize>(),
            super::super::MAX_TEXT
        );
        assert!(notes.truncated);
    }

    #[test]
    fn malformed_references_are_skipped_and_node_budget_is_bounded() {
        let mut doc = document(vec![Object::Reference((4, 0)); MAX_NODES + 1]);
        doc.objects.insert((4, 0), Object::Reference((4, 0)));
        let notes = read(&doc);
        assert!(notes.entries.is_empty());
        assert!(notes.truncated);
    }
}
