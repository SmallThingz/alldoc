#![forbid(unsafe_code)]
use pdf_extract::{MediaBox, OutputDev, OutputError, PlainTextOutput, Transform};
use serde_json::{json, Value};
use std::{cell::RefCell, env, fmt, fs, io::Read, path::Path, rc::Rc};
mod notes;
mod outline;
mod raster;

const MAX_INPUT: u64 = 128 * 1024 * 1024;
const MAX_TEXT: usize = 2_000_000;
const MAX_PAGES: usize = 2000;

struct Pages {
    text: Vec<String>,
    left: usize,
    truncated: bool,
}
#[derive(Clone)]
struct PageWriter(Rc<RefCell<Pages>>);
impl fmt::Write for PageWriter {
    fn write_str(&mut self, value: &str) -> fmt::Result {
        let mut pages = self.0.borrow_mut();
        if value.len() > pages.left {
            pages.truncated = true;
            return Err(fmt::Error);
        }
        pages.left -= value.len();
        pages.text.last_mut().ok_or(fmt::Error)?.push_str(value);
        Ok(())
    }
}
impl pdf_extract::ConvertToFmt for PageWriter {
    type Writer = Self;
    fn convert(self) -> Self {
        self
    }
}
struct PageOutput {
    pages: PageWriter,
    output: PlainTextOutput<PageWriter>,
}
impl OutputDev for PageOutput {
    fn begin_page(
        &mut self,
        page: u32,
        media: &MediaBox,
        art: Option<(f64, f64, f64, f64)>,
    ) -> Result<(), OutputError> {
        {
            let mut pages = self.pages.0.borrow_mut();
            if pages.text.len() >= MAX_PAGES || pages.left == 0 {
                pages.truncated = true;
                return Err(fmt::Error.into());
            }
            pages.text.push(String::new());
        }
        self.output = PlainTextOutput::new(self.pages.clone());
        self.output.begin_page(page, media, art)
    }
    fn end_page(&mut self) -> Result<(), OutputError> {
        self.output.end_page()
    }
    fn output_character(
        &mut self,
        trm: &Transform,
        width: f64,
        spacing: f64,
        size: f64,
        value: &str,
    ) -> Result<(), OutputError> {
        self.output
            .output_character(trm, width, spacing, size, value)
    }
    fn begin_word(&mut self) -> Result<(), OutputError> {
        self.output.begin_word()
    }
    fn end_word(&mut self) -> Result<(), OutputError> {
        self.output.end_word()
    }
    fn end_line(&mut self) -> Result<(), OutputError> {
        self.output.end_line()
    }
}

fn extract(input: &Path) -> Result<Value, Box<dyn std::error::Error>> {
    if fs::metadata(input)?.len() > MAX_INPUT {
        return Err("PDF exceeds 128 MiB text export limit".into());
    }
    let mut doc = lopdf::Document::load(input)?;
    if doc.is_encrypted() {
        doc.decrypt("").map_err(|_| "PDF needs a password")?;
    }
    let page_count = doc.get_pages().len();
    let pages = PageWriter(Rc::new(RefCell::new(Pages {
        text: Vec::new(),
        left: MAX_TEXT,
        truncated: false,
    })));
    let mut output = PageOutput {
        pages: pages.clone(),
        output: PlainTextOutput::new(pages.clone()),
    };
    let result = pdf_extract::output_doc(&doc, &mut output);
    let data = pages.0.borrow();
    if !data.truncated {
        result?;
    }
    let sections = data
        .text
        .iter()
        .enumerate()
        .map(|(index, text)| {
            json!({
        "kind":"page","index":index,"title":format!("Page {}",index+1),
        "blocks":[{"kind":"paragraph","text":text}]})
        })
        .collect::<Vec<_>>();
    let mut warnings =
        vec!["Text reading order is approximate. Scanned pages need OCR, which is not included."];
    if data.truncated {
        warnings.push("Text export limit reached; remaining content is omitted.");
    }
    Ok(
        json!({"version":1,"format":"pdf","fidelity":"extracted-text","pageCount":page_count,
        "sections":sections,"truncated":data.truncated,"warnings":warnings}),
    )
}

fn metadata(input: &Path, password: &str, mode: &str) -> Result<Value, Box<dyn std::error::Error>> {
    if fs::metadata(input)?.len() > MAX_INPUT {
        return Err("PDF exceeds 128 MiB metadata limit".into());
    }
    let doc = lopdf::Document::load_with_password(input, password)?;
    if mode == "outline" {
        let entries = outline::read(&doc)?
            .into_iter()
            .map(|entry| json!({"title":entry.title,"page":entry.page,"level":entry.level}))
            .collect::<Vec<_>>();
        Ok(json!({"version":1,"format":"pdf-outline","entries":entries,
            "rasterPageCount":doc.get_pages().len(),"rasterRegions":raster::pages(&doc).into_iter().map(|(page, regions)|
                json!({"page":page,"regions":regions})).collect::<Vec<_>>()}))
    } else {
        let notes = notes::read(&doc);
        let entries = notes.entries.into_iter()
            .map(|entry| json!({"text":entry.text,"author":entry.author,"kind":entry.kind,"page":entry.page})).collect::<Vec<_>>();
        Ok(json!({"version":1,"format":"pdf-notes","entries":entries,"truncated":notes.truncated}))
    }
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args = env::args_os().collect::<Vec<_>>();
    if !(3..=4).contains(&args.len()) {
        return Err("Expected input/output paths and optional metadata mode".into());
    }
    let result = if args.len() == 4 && (args[3] == "outline" || args[3] == "notes") {
        let mut password = Vec::new();
        std::io::stdin().take(4097).read_to_end(&mut password)?;
        if password.len() > 4096 {
            password.fill(0);
            return Err("PDF password exceeds its limit".into());
        }
        let result = (|| {
            metadata(
                Path::new(&args[1]),
                std::str::from_utf8(&password)?,
                args[3].to_str().unwrap(),
            )
        })();
        password.fill(0);
        result?
    } else if args.len() == 3 {
        extract(Path::new(&args[1]))?
    } else {
        return Err("Unknown PDF worker mode".into());
    };
    let file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(&args[2])?;
    serde_json::to_writer(file, &result)?;
    Ok(())
}

fn main() {
    if let Err(error) = run() {
        eprintln!("PDF text extraction failed: {error}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fmt::Write;
    #[test]
    fn encrypted_contents_load_objects_before_reading_the_catalog() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
        for (name, password) in [
            ("app/src/androidTest/assets/encrypted.pdf", "alldoc-test"),
            (
                "client-test/src/androidTest/assets/space-password.pdf",
                "   ",
            ),
        ] {
            let input = root.join(name);
            for mode in ["outline", "notes"] {
                assert!(metadata(&input, password, mode).unwrap()["entries"]
                    .as_array()
                    .unwrap()
                    .is_empty());
                assert!(metadata(&input, "incorrect", mode).is_err());
                assert!(metadata(&input, "", mode).is_err());
            }
        }
    }

    #[test]
    fn writer_never_splits_utf8_or_exceeds_limit() {
        let mut writer = PageWriter(Rc::new(RefCell::new(Pages {
            text: vec![String::new()],
            left: 4,
            truncated: false,
        })));
        writer.write_str("é").unwrap();
        assert!(writer.write_str("🇦").is_err());
        let data = writer.0.borrow();
        assert_eq!(data.text[0], "é");
        assert!(data.truncated);
    }
}
