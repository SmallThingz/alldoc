//! Conservative no-raster proof. Unknown pages retain the pixel classifier.
//! No image bytes are decoded; budgets apply across all pages in this metadata call.
use flate2::read::ZlibDecoder;
use lopdf::{content::{Content, Operation}, Dictionary, Document, Object, ObjectId, Stream};
mod placement;
use std::{collections::{BTreeMap, BTreeSet}, io::Read};

const MAX_STREAM: usize = 256 * 1024;
const MAX_BYTES: usize = 16 * 1024 * 1024;
const MAX_NODES: usize = 20_000;
const MAX_OPERATIONS: usize = 200_000;
const MAX_DEPTH: usize = 16;

pub fn pages(doc: &Document) -> Vec<(usize, Vec<[f64; 4]>)> {
    let mut proof = Proof { doc, bytes: MAX_BYTES, nodes: MAX_NODES, operations: MAX_OPERATIONS, regions: 4096,
        forms: BTreeMap::new(), visiting: BTreeSet::new() };
    doc.get_pages().into_iter().take(super::MAX_PAGES)
        .filter_map(|(number, id)| {
            let regions = if proof.page(id) { Some(Vec::new()) } else { proof.image_regions(id) };
            regions.map(|regions| (number as usize - 1, regions))
        }).collect()
}

fn resolved<'a>(doc: &'a Document, value: &'a Object) -> Option<&'a Object> {
    doc.dereference(value).ok().map(|(_, object)| object)
}

struct Proof<'a> {
    doc: &'a Document,
    bytes: usize,
    nodes: usize,
    operations: usize,
    regions: usize,
    forms: BTreeMap<ObjectId, bool>,
    visiting: BTreeSet<ObjectId>,
}
impl Proof<'_> {
    fn visit(&mut self) -> bool {
        if self.nodes == 0 { return false; }
        self.nodes -= 1;
        true
    }
    fn page(&mut self, id: ObjectId) -> bool {
        if !self.visit() { return false; }
        let Ok(page) = self.doc.get_dictionary(id) else { return false; };
        // Annotation appearances can contain independent images and forms.
        if let Ok(value) = page.get(b"Annots") {
            if !resolved(self.doc, value).and_then(|v| v.as_array().ok()).is_some_and(|a| a.is_empty()) {
                return false;
            }
        }
        let mut current = page;
        let mut resources = None;
        let mut inheritance_complete = false;
        for _ in 0..MAX_DEPTH {
            if let Ok(value) = current.get(b"Resources") { resources = Some(value); inheritance_complete = true; break; }
            match current.get(b"Parent") {
                Ok(value) => match resolved(self.doc, value).and_then(|v| v.as_dict().ok()) {
                    Some(parent) => current = parent,
                    None => return false,
                },
                Err(_) => { inheritance_complete = true; break; },
            }
        }
        if !inheritance_complete { return false; }
        if let Some(value) = resources {
            let Some(dict) = resolved(self.doc, value).and_then(|v| v.as_dict().ok()) else { return false; };
            if !self.resources(dict, 0) { return false; }
        }
        match page.get(b"Contents") {
            Err(_) => true,
            Ok(value) => self.contents(value, 0),
        }
    }
    fn contents(&mut self, value: &Object, depth: usize) -> bool {
        if depth > MAX_DEPTH || !self.visit() { return false; }
        match resolved(self.doc, value) {
            Some(Object::Stream(stream)) => self.stream(stream),
            Some(Object::Array(streams)) => streams.iter().all(|stream| self.contents(stream, depth + 1)),
            Some(Object::Null) => true,
            _ => false,
        }
    }
    fn resources(&mut self, resources: &Dictionary, depth: usize) -> bool {
        if depth > MAX_DEPTH || !self.visit() { return false; }
        for key in [b"Pattern".as_slice(), b"Shading"] {
            if let Ok(value) = resources.get(key) {
                if !resolved(self.doc, value).and_then(|v| v.as_dict().ok()).is_some_and(|d| d.is_empty()) {
                    return false;
                }
            }
        }
        for key in [b"Font".as_slice(), b"ExtGState", b"XObject"] {
            let Ok(value) = resources.get(key) else { continue; };
            let Some(entries) = resolved(self.doc, value).and_then(|v| v.as_dict().ok()) else { return false; };
            for (_, value) in entries {
                if !self.visit() { return false; }
                let Some(object) = resolved(self.doc, value) else { return false; };
                if key == b"XObject" {
                    if !self.form(value, depth + 1) { return false; }
                } else {
                    let Ok(dict) = object.as_dict() else { return false; };
                    if key == b"Font" {
                        if !matches!(dict.get(b"Subtype").and_then(Object::as_name),
                            Ok(b"Type0" | b"Type1" | b"MMType1" | b"TrueType" | b"CIDFontType0" | b"CIDFontType2")) { return false; }
                    } else if dict.get(b"Font").is_ok() {
                        return false;
                    } else if let Ok(mask) = dict.get(b"SMask") {
                        if resolved(self.doc, mask).and_then(|v| v.as_name().ok()) != Some(b"None") { return false; }
                    }
                }
            }
        }
        true
    }
    fn form(&mut self, value: &Object, depth: usize) -> bool {
        if depth > MAX_DEPTH { return false; }
        let Ok((id, object)) = self.doc.dereference(value) else { return false; };
        let Ok(stream) = object.as_stream() else { return false; };
        if !matches!(stream.dict.get(b"Subtype").and_then(Object::as_name), Ok(b"Form")) ||
            stream.dict.get(b"Ref").is_ok() || stream.dict.get(b"OPI").is_ok() { return false; }
        // A form without local resources can depend on its caller's resource scope.
        let Some(resources) = stream.dict.get(b"Resources").ok()
            .and_then(|v| resolved(self.doc, v)).and_then(|v| v.as_dict().ok()) else { return false; };
        if let Some(id) = id {
            if let Some(proven) = self.forms.get(&id) { return *proven; }
            if !self.visiting.insert(id) { return false; }
        }
        let proven = self.resources(resources, depth) && self.stream(stream);
        if let Some(id) = id { self.visiting.remove(&id); self.forms.insert(id, proven); }
        proven
    }
    // Complete resource checks leave only inline images as a possible raster source.
    // Scan those bytes without allocating or decoding vector operations.
    fn stream(&mut self, stream: &Stream) -> bool {
        let mut previous = 0;
        self.read_content(stream, |data| no_inline(&mut previous, data))
    }
    fn operations(&mut self, stream: &Stream) -> Option<Vec<Operation>> {
        let mut data = Vec::new();
        let mut previous = 0;
        if !self.read_content(stream, |part| {
            if !no_inline(&mut previous, part) { return false; }
            data.extend_from_slice(part); true
        }) { return None; }
        let content = Content::decode_strict(&data).ok()?;
        if content.operations.len() > self.operations { return None; }
        self.operations -= content.operations.len();
        Some(content.operations)
    }
    fn read_content(&mut self, stream: &Stream, mut accept: impl FnMut(&[u8]) -> bool) -> bool {
        if stream.dict.get(b"F").is_ok() || stream.content.len() > MAX_STREAM ||
            stream.content.len() > self.bytes { return false; }
        self.bytes -= stream.content.len();
        if stream.dict.get(b"Filter").is_err() { return accept(&stream.content); }
        let Ok(filters) = stream.filters() else { return false; };
        let ascii85 = filters.as_slice() == [b"ASCII85Decode".as_slice(), b"FlateDecode".as_slice()];
        if !ascii85 && filters.as_slice() != [b"FlateDecode".as_slice()] { return false; }
        if let Ok(params) = stream.dict.get(b"DecodeParms") {
            // The two-filter path admits no predictors or per-filter parameter interpretation.
            if ascii85 { return false; }
            let Some(params) = resolved(self.doc, params).and_then(|v| v.as_dict().ok()) else { return false; };
            if params.get(b"Predictor").and_then(Object::as_i64).unwrap_or(1) != 1 { return false; }
        }
        let intermediate;
        let compressed = if ascii85 {
            // Reuse the locked PDF decoder for ASCII85 only. Its worst case is four
            // output bytes per encoded byte (`z`); reject before allocating that stage.
            // Flate remains streamed below with the original aggregate/output limits.
            let encoded = stream.content.trim_ascii_end();
            if encoded.len() > MAX_STREAM / 4 || encoded.len() > self.bytes / 4 { return false; }
            let Some(body) = encoded.strip_suffix(b"~>") else { return false; };
            if body.iter().any(|b| !b.is_ascii_whitespace() && *b != b'z' && !(b'!'..=b'u').contains(b)) { return false; }
            let mut dictionary = Dictionary::new();
            dictionary.set("Filter", "ASCII85Decode");
            let Ok(decoded) = Stream::new(dictionary, encoded.to_vec()).decompressed_content() else { return false; };
            if decoded.len() > MAX_STREAM || decoded.len() > self.bytes { return false; }
            self.bytes -= decoded.len();
            intermediate = decoded;
            intermediate.as_slice()
        } else { stream.content.as_slice() };
        let mut decoder = ZlibDecoder::new(compressed);
        let mut buffer = [0u8; 4096];
        let mut decoded = 0;
        loop {
            let available = buffer.len().min(MAX_STREAM - decoded + 1).min(self.bytes + 1);
            let Ok(count) = decoder.read(&mut buffer[..available]) else { return false; };
            if count == 0 { return true; }
            decoded += count;
            let fits = count <= self.bytes && decoded <= MAX_STREAM;
            self.bytes = self.bytes.saturating_sub(count);
            if !fits || !accept(&buffer[..count]) { return false; }
        }
    }

}

fn no_inline(previous: &mut u8, data: &[u8]) -> bool {
    if (*previous == b'B' && data.first() == Some(&b'I')) || data.windows(2).any(|p| p == b"BI") { return false; }
    if let Some(last) = data.last() { *previous = *last; }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use lopdf::dictionary;
    fn fixture(resources: Dictionary, content: &[u8]) -> Document {
        let mut doc = Document::with_version("1.7");
        doc.trailer.set("Root", (1, 0));
        doc.objects.insert((1,0), dictionary!{"Type"=>"Catalog", "Pages"=>(2,0)}.into());
        doc.objects.insert((2,0), dictionary!{"Type"=>"Pages", "Kids"=>vec![Object::Reference((3,0))], "Count"=>1, "Resources"=>resources}.into());
        doc.objects.insert((3,0), dictionary!{"Type"=>"Page", "Parent"=>(2,0), "Contents"=>(4,0)}.into());
        let mut stream = Stream::new(Dictionary::new(), content.to_vec());
        stream.compress().unwrap();
        doc.objects.insert((4,0), Object::Stream(stream));
        doc
    }
    #[test]
    fn ordinary_compressed_nested_forms_and_optional_layers_are_vector_only() {
        let mut doc = fixture(dictionary!{"XObject"=>dictionary!{"F"=>(5,0)}, "ExtGState"=>dictionary!{"Alpha"=>dictionary!{"ca"=>0.3}}}, b"/OC /Layer BDC q /Alpha gs /F Do Q EMC");
        let child = Stream::new(dictionary!{"Subtype"=>"Form", "Resources"=>Dictionary::new()}, b"0.2 0.4 0.6 rg 0 0 10 10 re f".to_vec());
        let mut parent = Stream::new(dictionary!{"Subtype"=>"Form", "Resources"=>dictionary!{"XObject"=>dictionary!{"Nested"=>(6,0)}}}, b"q /Nested Do Q".to_vec());
        parent.compress().unwrap();
        doc.objects.insert((5,0), Object::Stream(parent));
        doc.objects.insert((6,0), Object::Stream(child));
        assert_eq!(pages(&doc), vec![(0, vec![])]);
        doc.get_object_mut((6,0)).unwrap().as_stream_mut().unwrap().dict.set("Subtype", "Image");
        assert!(pages(&doc).is_empty());
    }
    #[test]
    fn inline_images_and_image_capable_appearances_remain_unknown() {
        let inline = fixture(Dictionary::new(), b"q BI /W 1 /H 1 /BPC 8 /CS /RGB ID abc EI Q");
        assert!(pages(&inline).is_empty());
        let mut annotation = fixture(Dictionary::new(), b"0 0 20 20 re f");
        annotation.get_object_mut((3,0)).unwrap().as_dict_mut().unwrap().set("Annots", vec![Object::Dictionary(dictionary!{"Subtype"=>"Stamp"})]);
        assert!(pages(&annotation).is_empty());
        let type3 = fixture(dictionary!{"Font"=>dictionary!{"F"=>dictionary!{"Subtype"=>"Type3"}}}, b"BT /F 12 Tf (Hello) Tj ET");
        assert!(pages(&type3).is_empty());
    }
    #[test]
    fn inline_byte_detection_crosses_streaming_chunk_edges() {
        let mut previous = 0;
        assert!(no_inline(&mut previous, b"q B"));
        assert!(!no_inline(&mut previous, b"I /W 1"));
    }
    fn jpeg_fixture(content: &[u8]) -> Document {
        let mut doc = fixture(dictionary!{
            "XObject"=>dictionary!{"Photo"=>(5,0)},
            "Font"=>dictionary!{"F"=>dictionary!{"Subtype"=>"Type1", "BaseFont"=>"Helvetica"}}
        }, content);
        doc.get_object_mut((3,0)).unwrap().as_dict_mut().unwrap()
            .set("MediaBox", vec![Object::Integer(0), 0.into(), 300.into(), 400.into()]);
        // The metadata proof does not decode pixels. Android tests use an actual encoded JPEG.
        doc.objects.insert((5,0), Object::Stream(Stream::new(dictionary!{
            "Subtype"=>"Image", "Width"=>2, "Height"=>2, "ColorSpace"=>"DeviceRGB",
            "BitsPerComponent"=>8, "Filter"=>"DCTDecode"
        }, Vec::new())));
        doc
    }
    #[test]
    fn opaque_scan_and_inset_placements_preserve_the_actual_footprint() {
        let full = jpeg_fixture(b"q 300 0 0 400 0 0 cm /Photo Do Q 1 1 1 rg 10 10 100 20 re f BT /F 9 Tf 10 20 Td (Footer) Tj ET");
        assert_eq!(pages(&full), vec![(0, vec![[0.0, 0.0, 1.0, 1.0]])]);
        let inset = jpeg_fixture(b"q 1 0 0 1 30 40 cm q 240 0 0 320 0 0 cm /Photo Do Q Q");
        let result = pages(&inset);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].1.len(), 1);
        for (actual, expected) in result[0].1[0].iter().zip([0.1, 0.1, 0.9, 0.9]) {
            assert!((actual - expected).abs() < 1e-12);
        }
    }
    #[test]
    fn clipped_rotated_or_masked_placements_remain_unknown() {
        for content in [
            b"q 10 10 200 200 re W n 300 0 0 400 0 0 cm /Photo Do Q".as_slice(),
            b"q 300 0 0 400 10 0 cm /Photo Do Q",
            b"q 240 10 0 320 30 40 cm /Photo Do Q",
        ] { assert!(pages(&jpeg_fixture(content)).is_empty()); }
        let mut rotated = jpeg_fixture(b"q 300 0 0 400 0 0 cm /Photo Do Q");
        rotated.get_object_mut((3,0)).unwrap().as_dict_mut().unwrap().set("Rotate", 90);
        assert!(pages(&rotated).is_empty());
        let mut masked = jpeg_fixture(b"q 300 0 0 400 0 0 cm /Photo Do Q");
        masked.get_object_mut((5,0)).unwrap().as_stream_mut().unwrap().dict.set("Mask", vec![Object::Integer(0); 6]);
        assert!(pages(&masked).is_empty());
    }
    fn ascii85_content(doc: &mut Document, encoded: &[u8]) {
        doc.objects.insert((4,0), Stream::new(dictionary!{
            "Filter"=>vec![Object::Name(b"ASCII85Decode".to_vec()), Object::Name(b"FlateDecode".to_vec())]
        }, encoded.to_vec()).into());
    }
    #[test]
    fn reportlab_alpha_and_flate_images_have_complete_footprints() {
        let mut doc = jpeg_fixture(b"");
        doc.get_object_mut((3,0)).unwrap().as_dict_mut().unwrap()
            .set("MediaBox", vec![Object::Integer(0), 0.into(), 720.into(), 960.into()]);
        doc.get_object_mut((2,0)).unwrap().as_dict_mut().unwrap()
            .get_mut(b"Resources").unwrap().as_dict_mut().unwrap()
            .set("ExtGState", dictionary!{"Layer"=>dictionary!{"Type"=>"ExtGState", "ca"=>0.66, "CA"=>0.2}});
        let image = &mut doc.get_object_mut((5,0)).unwrap().as_stream_mut().unwrap().dict;
        image.set("Filter", vec![Object::Name(b"ASCII85Decode".to_vec()), Object::Name(b"FlateDecode".to_vec())]);
        image.set("Width", 1400); image.set("Height", 800);
        // Python zlib + ASCII85, matching ReportLab's ordinary content filter chain.
        ascii85_content(&mut doc, b"Gap(=9a\\`k(dtRuQFb1fb[/ea$^FtifX*-X?kgQ6qp@/]Ase.sa6m\"Iiu@)V*Yi?57V'b1.h('5Z32VI@3[bHCtjn484t!0;*lK$X=g@8n'@+s^MECmIHH7V<k13iVas?EH#[c4:?^0/IO_tPq%$E',G+~>");
        let result = pages(&doc);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].1.len(), 1);
        let expected = [474.375 / 720.0, 855.0 / 960.0, 605.625 / 720.0, 930.0 / 960.0];
        for (actual, expected) in result[0].1[0].iter().zip(expected) { assert!((actual - expected).abs() < 1e-12); }
        // Photo-less pages keep the same alpha states but have a complete no-raster proof.
        doc.get_object_mut((2,0)).unwrap().as_dict_mut().unwrap()
            .get_mut(b"Resources").unwrap().as_dict_mut().unwrap().remove(b"XObject");
        ascii85_content(&mut doc, b"Gap(:_$WFm'LdN5/D5t:#(+>39(*\"L0sRCk6N5i\":8`!m(?>=G7(b2`;dhd;@[5C@VSGh\"To[gh/</qps6G>Id#+q\">\"=K%A5ek^m>.S0cB4Z(?<9W7qu~>");
        assert_eq!(pages(&doc), vec![(0, vec![])]);
    }
    #[test]
    fn image_alpha_is_saved_restored_and_zero_alpha_has_no_footprint() {
        let mut doc = jpeg_fixture(b"/Invisible gs q /Half gs q 120 0 0 160 30 200 cm /Photo Do Q Q q 120 0 0 160 30 40 cm /Photo Do Q /Opaque gs q 120 0 0 160 150 40 cm /Photo Do Q");
        doc.get_object_mut((2,0)).unwrap().as_dict_mut().unwrap()
            .get_mut(b"Resources").unwrap().as_dict_mut().unwrap().set("ExtGState", dictionary!{
                "Invisible"=>dictionary!{"ca"=>0.0}, "Half"=>dictionary!{"ca"=>0.5,"CA"=>0.0}, "Opaque"=>dictionary!{"ca"=>1.0}
            });
        assert_eq!(pages(&doc), vec![(0, vec![[0.1, 0.1, 0.5, 0.5], [0.5, 0.5, 0.9, 0.9]])]);
        doc.objects.insert((4,0), Stream::new(Dictionary::new(), b"/Invisible gs q 300 0 0 400 0 0 cm /Photo Do Q".to_vec()).into());
        assert_eq!(pages(&doc), vec![(0, vec![])]);
    }
    #[test]
    fn other_graphics_effects_and_incomplete_ascii85_remain_unknown() {
        for state in [dictionary!{"ca"=>0.5,"BM"=>"Multiply"}, dictionary!{"ca"=>0.5,"SMask"=>"None"}, dictionary!{"ca"=>1.1}] {
            let mut doc = jpeg_fixture(b"/Effect gs q 300 0 0 400 0 0 cm /Photo Do Q");
            doc.get_object_mut((2,0)).unwrap().as_dict_mut().unwrap()
                .get_mut(b"Resources").unwrap().as_dict_mut().unwrap().set("ExtGState", dictionary!{"Effect"=>state});
            assert!(pages(&doc).is_empty());
        }
        let mut doc = fixture(Dictionary::new(), b"");
        ascii85_content(&mut doc, b"not complete");
        assert!(pages(&doc).is_empty());
        let mut too_large = vec![b'z'; MAX_STREAM / 4 + 1]; too_large.extend_from_slice(b"~>");
        ascii85_content(&mut doc, &too_large);
        assert!(pages(&doc).is_empty());
    }
    #[test]
    fn optional_proof_stops_at_its_stream_budget_without_changing_document_data() {
        let content = b"0 0 1 1 re f\n".repeat(MAX_STREAM / 13 + 10);
        let doc = fixture(Dictionary::new(), &content);
        let original = doc.get_object((4,0)).unwrap().as_stream().unwrap().content.clone();
        assert!(pages(&doc).is_empty());
        assert_eq!(doc.get_object((4,0)).unwrap().as_stream().unwrap().content, original);
    }
}
