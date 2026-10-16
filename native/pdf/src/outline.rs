//! PDF outline objects from lopdf; no inferred headings, external actions or title deduplication.
use lopdf::{Dictionary, Document, Object, ObjectId};
use std::collections::{HashMap, HashSet};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
const MAX_ENTRIES: usize = 4096;
const MAX_NODES: usize = 8192;
const MAX_DEPTH: usize = 32;

#[derive(Debug, PartialEq)]
pub struct Entry {
    pub title: String,
    pub page: usize,
    pub level: usize,
}

fn dictionary<'a>(doc: &'a Document, object: &'a Object) -> Result<&'a Dictionary> {
    Ok(doc.dereference(object)?.1.as_dict()?)
}

fn names(doc: &Document) -> Result<HashMap<&[u8], &Object>> {
    let catalog = doc.catalog()?;
    let mut result = HashMap::new();
    if let Ok(legacy) = catalog.get(b"Dests") {
        for (key, value) in dictionary(doc, legacy)? {
            if result.len() == MAX_ENTRIES || key.len() > 4096 {
                return Err("PDF destination limit exceeded".into());
            }
            result.insert(key.as_slice(), value);
        }
    }
    let root = catalog
        .get(b"Names")
        .ok()
        .map(|value| dictionary(doc, value))
        .transpose()?;
    let Some(root) = root.and_then(|value| value.get(b"Dests").ok()) else {
        return Ok(result);
    };
    let mut pending = vec![(root, 0)];
    let mut seen = HashSet::new();
    let mut nodes = 0;
    while let Some((object, depth)) = pending.pop() {
        nodes += 1;
        if nodes > MAX_NODES || depth > MAX_DEPTH {
            return Err("PDF destination tree limit exceeded".into());
        }
        let (id, object) = doc.dereference(object)?;
        if id.is_some_and(|id| !seen.insert(id)) {
            return Err("PDF destination tree contains a cycle".into());
        }
        let node = object.as_dict()?;
        if let Ok(values) = node.get(b"Names") {
            let values = doc.dereference(values)?.1.as_array()?;
            if values.len() % 2 != 0 {
                return Err("PDF destination pair is incomplete".into());
            }
            for pair in values.chunks_exact(2) {
                let key = doc.dereference(&pair[0])?.1.as_str()?;
                if result.len() == MAX_ENTRIES || key.len() > 4096 {
                    return Err("PDF destination limit exceeded".into());
                }
                result.insert(key, &pair[1]);
            }
        }
        if let Ok(kids) = node.get(b"Kids") {
            let kids = doc.dereference(kids)?.1.as_array()?;
            if pending.len() + kids.len() > MAX_NODES {
                return Err("PDF destination tree limit exceeded".into());
            }
            pending.extend(kids.iter().rev().map(|child| (child, depth + 1)));
        }
    }
    Ok(result)
}

fn page<'a>(
    doc: &'a Document,
    value: &'a Object,
    names: &HashMap<&[u8], &'a Object>,
    pages: &HashMap<ObjectId, usize>,
) -> Result<Option<usize>> {
    let mut current = value;
    for _ in 0..MAX_DEPTH {
        current = doc.dereference(current)?.1;
        match current {
            Object::Array(values) => {
                return Ok(values
                    .first()
                    .and_then(|value| value.as_reference().ok())
                    .and_then(|id| pages.get(&id).copied()))
            }
            Object::Dictionary(value) => current = value.get(b"D")?,
            Object::Name(key) | Object::String(key, _) => match names.get(key.as_slice()) {
                Some(value) => current = value,
                None => return Ok(None),
            },
            _ => return Ok(None),
        }
    }
    Err("PDF destination reference limit exceeded".into())
}

pub fn read(doc: &Document) -> Result<Vec<Entry>> {
    let catalog = doc.catalog()?;
    let Some(root) = catalog.get(b"Outlines").ok() else {
        return Ok(Vec::new());
    };
    let root = dictionary(doc, root)?;
    let Some(first) = root.get(b"First").ok() else {
        return Ok(Vec::new());
    };
    let names = names(doc)?;
    let pages: HashMap<_, _> = doc
        .get_pages()
        .into_iter()
        .map(|(page, id)| (id, page as usize - 1))
        .collect();
    let mut pending = vec![(first, 0)];
    let mut seen = HashSet::new();
    let mut nodes = 0;
    let mut entries = Vec::new();
    while let Some((object, level)) = pending.pop() {
        nodes += 1;
        if nodes > MAX_NODES || level > MAX_DEPTH {
            return Err("PDF contents tree limit exceeded".into());
        }
        let (id, object) = doc.dereference(object)?;
        if id.is_some_and(|id| !seen.insert(id)) {
            return Err("PDF contents tree contains a cycle".into());
        }
        let node = object.as_dict()?;
        if let Ok(next) = node.get(b"Next") {
            pending.push((next, level));
        }
        if let Ok(child) = node.get(b"First") {
            pending.push((child, level + 1));
        }
        let destination = if let Ok(value) = node.get(b"Dest") {
            Some(value)
        } else if let Ok(action) = node.get(b"A") {
            let action = dictionary(doc, action)?;
            if action.get(b"S").and_then(Object::as_name).ok() == Some(b"GoTo".as_slice()) {
                action.get(b"D").ok()
            } else {
                None
            }
        } else {
            None
        };
        let Some(destination) = destination else {
            continue;
        };
        let Some(page) = page(doc, destination, &names, &pages)? else {
            continue;
        };
        let Ok(title) = node.get(b"Title") else {
            continue;
        };
        let title = doc.dereference(title)?.1;
        if title.as_str()?.len() > 16384 {
            return Err("PDF contents title exceeds its limit".into());
        }
        let title = lopdf::decode_text_string(title)?;
        let title: String = title
            .trim_start_matches('\u{feff}')
            .trim()
            .chars()
            .take(256)
            .collect();
        if title.is_empty() {
            continue;
        }
        if entries.len() == MAX_ENTRIES {
            return Err("PDF contents entry limit exceeded".into());
        }
        entries.push(Entry { title, page, level });
    }
    Ok(entries)
}

#[cfg(test)]
mod tests {
    use super::*;
    use lopdf::dictionary;

    fn document() -> Document {
        let mut doc = Document::with_version("1.7");
        doc.trailer.set("Root", (2, 0));
        doc.objects.insert((1, 0), dictionary! {"Type"=>"Pages", "Kids"=>vec![Object::Reference((3,0)),Object::Reference((4,0))], "Count"=>2}.into());
        doc.objects.insert((2, 0), dictionary! {"Type"=>"Catalog", "Pages"=>(1,0), "Outlines"=>(5,0), "Dests"=>dictionary!{"child"=>vec![Object::Reference((4,0)),Object::Name(b"Fit".to_vec())]}}.into());
        for id in [3, 4] {
            doc.objects
                .insert((id, 0), dictionary! {"Type"=>"Page","Parent"=>(1,0)}.into());
        }
        doc.objects
            .insert((5, 0), dictionary! {"First"=>(6,0)}.into());
        let target = |id| vec![Object::Reference((id, 0)), Object::Name(b"Fit".to_vec())];
        doc.objects.insert((6,0),dictionary!{"Title"=>lopdf::text_string("Repeated"),"Dest"=>target(3),"First"=>(8,0),"Next"=>(7,0)}.into());
        doc.objects.insert((7,0),dictionary!{"Title"=>lopdf::text_string("Repeated"),"A"=>dictionary!{"S"=>"GoTo","D"=>target(4)}}.into());
        doc.objects.insert((8,0),dictionary!{"Title"=>lopdf::text_string("चरण😀"),"Dest"=>Object::Name(b"child".to_vec())}.into());
        doc
    }
    #[test]
    fn preserves_duplicate_titles_unicode_nesting_and_named_local_destinations() {
        let mut doc = document();
        let expected = vec![
            Entry {
                title: "Repeated".into(),
                page: 0,
                level: 0,
            },
            Entry {
                title: "चरण😀".into(),
                page: 1,
                level: 1,
            },
            Entry {
                title: "Repeated".into(),
                page: 1,
                level: 0,
            },
        ];
        assert_eq!(read(&doc).unwrap(), expected);
        let catalog = doc.get_dictionary_mut((2, 0)).unwrap();
        catalog.remove(b"Dests");
        catalog.set("Names", dictionary! {"Dests" => (9, 0)});
        doc.objects
            .insert((9, 0), dictionary! {"Kids" => (10, 0)}.into());
        doc.objects
            .insert((10, 0), vec![Object::Reference((11, 0))].into());
        doc.objects
            .insert((11, 0), dictionary! {"Names" => (12, 0)}.into());
        doc.objects.insert(
            (12, 0),
            vec![Object::Reference((13, 0)), Object::Reference((14, 0))].into(),
        );
        doc.objects.insert((13, 0), Object::string_literal("child"));
        doc.objects.insert(
            (14, 0),
            vec![Object::Reference((4, 0)), Object::Name(b"Fit".to_vec())].into(),
        );
        assert_eq!(read(&doc).unwrap(), expected);
    }
    #[test]
    fn absent_and_external_destinations_do_not_invent_navigation() {
        let mut doc = document();
        doc.get_dictionary_mut((7, 0)).unwrap().set(
            "A",
            dictionary! {"S"=>"GoToR","F"=>lopdf::text_string("outside.pdf"),"D"=>0},
        );
        assert_eq!(read(&doc).unwrap().len(), 2);
        doc.get_dictionary_mut((2, 0)).unwrap().remove(b"Outlines");
        assert!(read(&doc).unwrap().is_empty());
    }
    #[test]
    fn cycles_are_reported_instead_of_looping() {
        let mut doc = document();
        doc.get_dictionary_mut((7, 0)).unwrap().set("Next", (6, 0));
        assert!(read(&doc).unwrap_err().to_string().contains("cycle"));
    }
}
