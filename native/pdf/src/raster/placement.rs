//! Direct unmasked image placements with ordinary alpha. No image pixels are decoded.
use super::{resolved, Proof, MAX_DEPTH};
use lopdf::{Dictionary, Document, Object, ObjectId};

fn number(value: &Object) -> Option<f64> {
    let n = match value { Object::Integer(n) => *n as f64, Object::Real(n) => *n as f64, _ => return None };
    n.is_finite().then_some(n)
}
fn inherited<'a>(doc: &'a Document, mut node: &'a Dictionary, key: &[u8]) -> Option<Option<&'a Object>> {
    for _ in 0..MAX_DEPTH {
        if let Ok(value) = node.get(key) { return Some(Some(resolved(doc, value)?)); }
        match node.get(b"Parent") {
            Ok(value) => node = resolved(doc, value)?.as_dict().ok()?,
            Err(_) => return Some(None),
        }
    }
    None
}
fn rectangle(value: &Object) -> Option<[f64; 4]> {
    let a = value.as_array().ok()?;
    if a.len() != 4 { return None; }
    let b = [number(&a[0])?, number(&a[1])?, number(&a[2])?, number(&a[3])?];
    (b[0] < b[2] && b[1] < b[3]).then_some(b)
}
fn alpha_only(doc: &Document, state: &Dictionary) -> bool {
    state.iter().all(|(key, value)| match key.as_slice() {
        b"Type" => resolved(doc, value).and_then(|v| v.as_name().ok()) == Some(b"ExtGState"),
        b"ca" | b"CA" => resolved(doc, value).and_then(number).is_some_and(|n| (0.0..=1.0).contains(&n)),
        _ => false,
    })
}
struct Placement {
    matrix: [f64; 6],
    alpha: f64,
    stack: Vec<([f64; 6], f64)>,
    rectangles: Vec<[f64; 4]>,
    text: bool,
}
impl Proof<'_> {
    pub(super) fn image_regions(&mut self, id: ObjectId) -> Option<Vec<[f64; 4]>> {
        if !self.visit() || self.regions == 0 { return None; }
        let doc = self.doc;
        let page = doc.get_dictionary(id).ok()?;
        if page.get(b"Group").is_ok() { return None; }
        if let Ok(a) = page.get(b"Annots") {
            if !resolved(doc, a)?.as_array().ok()?.is_empty() { return None; }
        }
        let media = rectangle(inherited(doc, page, b"MediaBox")??)?;
        if let Some(crop) = inherited(doc, page, b"CropBox")? {
            if rectangle(crop)? != media { return None; }
        }
        if let Some(rotation) = inherited(doc, page, b"Rotate")? {
            if rotation.as_i64().ok()? % 360 != 0 { return None; }
        }
        if let Ok(unit) = page.get(b"UserUnit") {
            if number(resolved(doc, unit)?)? != 1.0 { return None; }
        }
        let resources = inherited(doc, page, b"Resources")??.as_dict().ok()?;
        // These can change coverage, transfer or visibility; the narrow path does not interpret them.
        for key in [b"Pattern".as_slice(), b"Shading", b"Properties"] {
            if let Ok(value) = resources.get(key) {
                if !resolved(doc, value)?.as_dict().ok()?.is_empty() { return None; }
            }
        }
        let states = match resources.get(b"ExtGState") {
            Ok(value) => {
                let states = resolved(doc, value)?.as_dict().ok()?;
                for (_, value) in states {
                    if !self.visit() || !alpha_only(doc, resolved(doc, value)?.as_dict().ok()?) { return None; }
                }
                Some(states)
            },
            Err(_) => None,
        };
        if let Ok(fonts) = resources.get(b"Font") {
            for (_, value) in resolved(doc, fonts)?.as_dict().ok()? {
                if !self.visit() { return None; }
                let font = resolved(doc, value)?.as_dict().ok()?;
                if !matches!(font.get(b"Subtype").and_then(Object::as_name),
                    Ok(b"Type0" | b"Type1" | b"MMType1" | b"TrueType" | b"CIDFontType0" | b"CIDFontType2")) { return None; }
            }
        }
        let xobjects = resolved(doc, resources.get(b"XObject").ok()?)?.as_dict().ok()?;
        let mut placement = Placement { matrix: [1.0,0.0,0.0,1.0,0.0,0.0], alpha: 1.0, stack: Vec::new(), rectangles: Vec::new(), text: false };
        self.placed_content(page.get(b"Contents").ok()?, xobjects, states, media, &mut placement, 0)?;
        if !placement.stack.is_empty() || placement.text { return None; }
        Some(placement.rectangles)
    }
    fn placed_content(&mut self, value: &Object, images: &Dictionary, states: Option<&Dictionary>, media: [f64;4], state: &mut Placement, depth: usize) -> Option<()> {
        if depth > MAX_DEPTH || !self.visit() { return None; }
        match resolved(self.doc, value)? {
            Object::Array(parts) => { for part in parts { self.placed_content(part, images, states, media, state, depth + 1)?; } },
            Object::Stream(stream) => for op in self.operations(stream)? {
                match op.operator.as_str() {
                    "q" if !state.text && op.operands.is_empty() && state.stack.len() < 32 => state.stack.push((state.matrix, state.alpha)),
                    "Q" if !state.text && op.operands.is_empty() => (state.matrix, state.alpha) = state.stack.pop()?,
                    "gs" if op.operands.len() == 1 => {
                        let named = states?.get(op.operands[0].as_name().ok()?).ok()?;
                        let dictionary = resolved(self.doc, named)?.as_dict().ok()?;
                        if let Ok(value) = dictionary.get(b"ca") { state.alpha = number(resolved(self.doc, value)?)?; }
                    },
                    "cm" if !state.text && op.operands.len() == 6 => {
                        let mut m = [0.0;6];
                        for (n, value) in op.operands.iter().enumerate() { m[n] = number(value)?; }
                        let c = state.matrix;
                        state.matrix = [c[0]*m[0]+c[2]*m[1], c[1]*m[0]+c[3]*m[1],
                            c[0]*m[2]+c[2]*m[3], c[1]*m[2]+c[3]*m[3],
                            c[0]*m[4]+c[2]*m[5]+c[4], c[1]*m[4]+c[3]*m[5]+c[5]];
                        if state.matrix.iter().any(|v| !v.is_finite() || v.abs() > 1e9) { return None; }
                    },
                    "Do" if !state.text && op.operands.len() == 1 => {
                        if self.regions == 0 || state.rectangles.len() == 64 { return None; }
                        let image = resolved(self.doc, images.get(op.operands[0].as_name().ok()?).ok()?)?.as_stream().ok()?;
                        let filters = image.filters().ok()?;
                        let encoded_image = filters.as_slice() == [b"DCTDecode".as_slice()] ||
                            filters.as_slice() == [b"FlateDecode".as_slice()] ||
                            filters.as_slice() == [b"ASCII85Decode".as_slice(), b"FlateDecode".as_slice()];
                        if !matches!(image.dict.get(b"Subtype").and_then(Object::as_name), Ok(b"Image")) || !encoded_image { return None; }
                        for key in [b"SMask".as_slice(), b"Mask", b"ImageMask", b"Alternates", b"OC", b"F"] {
                            if image.dict.get(key).is_ok() { return None; }
                        }
                        if image.dict.get(b"Width").ok()?.as_i64().ok()? <= 0 || image.dict.get(b"Height").ok()?.as_i64().ok()? <= 0 ||
                            image.dict.get(b"BitsPerComponent").ok()?.as_i64().ok()? != 8 ||
                            !matches!(image.dict.get(b"ColorSpace").and_then(Object::as_name), Ok(b"DeviceRGB" | b"DeviceGray" | b"DeviceCMYK")) { return None; }
                        let [a,b,c,d,e,f] = state.matrix;
                        if !((b == 0.0 && c == 0.0 && a != 0.0 && d != 0.0) ||
                            (a == 0.0 && d == 0.0 && b != 0.0 && c != 0.0)) { return None; }
                        let x = [e,e+a,e+c,e+a+c]; let y = [f,f+b,f+d,f+b+d];
                        let left = x.into_iter().fold(f64::INFINITY,f64::min);
                        let right = x.into_iter().fold(f64::NEG_INFINITY,f64::max);
                        let bottom = y.into_iter().fold(f64::INFINITY,f64::min);
                        let top = y.into_iter().fold(f64::NEG_INFINITY,f64::max);
                        if left >= right || bottom >= top || left < media[0] || bottom < media[1] || right > media[2] || top > media[3] { return None; }
                        if state.alpha > 0.0 {
                            state.rectangles.push([(left-media[0])/(media[2]-media[0]), (media[3]-top)/(media[3]-media[1]),
                                (right-media[0])/(media[2]-media[0]), (media[3]-bottom)/(media[3]-media[1])]);
                            self.regions -= 1;
                        }
                    },
                    "BT" if !state.text => state.text = true,
                    "ET" if state.text => state.text = false,
                    "Tc" | "Tw" | "Tz" | "TL" | "Tf" | "Ts" | "Td" | "TD" | "Tm" | "T*" | "Tj" | "TJ" | "'" | "\"" if state.text => {},
                    "w" | "J" | "j" | "M" | "d" | "ri" | "i" | "m" | "l" | "c" | "v" | "y" | "h" | "re" |
                    "S" | "s" | "f" | "F" | "f*" | "B" | "B*" | "b" | "b*" | "n" |
                    "G" | "g" | "RG" | "rg" | "K" | "k" if !state.text => {},
                    _ => return None,
                }
            },
            _ => return None,
        }
        Some(())
    }
}
