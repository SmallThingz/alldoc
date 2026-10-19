//! AllDoc bounded visual readers. Third-party format/render code is pinned under ../vendor.
mod image_regions;
pub mod sheets;
use quick_xml::events::Event;
use std::io::{Cursor, Read};
use std::sync::Mutex;
use std::time::{Duration, Instant};
static RENDER_LOCK: Mutex<()> = Mutex::new(());
pub const INPUT_LIMIT: usize = 16 * 1024 * 1024;
pub const OUTPUT_LIMIT: usize = 16 * 1024 * 1024;
pub const MAX_PAGES: usize = 1024;
pub const MAX_SIDE: u32 = 2800;
pub const OUTPUT_PIXELS: u64 = 8_000_000;
pub const REGION_MAX_SIDE: u32 = 4096;

/// Original, unrotated page fractions; output allocation is independent of zoom.
#[derive(Clone, Copy, Debug)]
pub struct Region {
    pub left: f64,
    pub top: f64,
    pub right: f64,
    pub bottom: f64,
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Copy)]
struct RasterView { scale_x: f64, scale_y: f64, x: f64, y: f64, width: u32, height: u32 }
impl RasterView {
    fn affine(self) -> deckcraft_geom::Affine {
        deckcraft_geom::Affine::new([self.scale_x, 0.0, 0.0, self.scale_y, self.x, self.y])
    }
    fn full_page(page_width: f64, page_height: f64, width: u32, height: u32) -> Result<Self, String> {
        // The encoded canvas is the normalized page frame. Fit its rounded dimensions
        // exactly so a source fraction names the same content in a base page and a region.
        Region { left: 0.0, top: 0.0, right: 1.0, bottom: 1.0, width, height }.view(page_width, page_height)
    }
}
impl Region {
    fn checked(self) -> Result<Self, String> {
        if ![self.left, self.top, self.right, self.bottom].iter().all(|v| v.is_finite())
            || self.left < 0.0 || self.top < 0.0 || self.right > 1.0 || self.bottom > 1.0
            || self.right <= self.left || self.bottom <= self.top
            || self.width == 0 || self.height == 0 || self.width > REGION_MAX_SIDE || self.height > REGION_MAX_SIDE
            || u64::from(self.width) * u64::from(self.height) > OUTPUT_PIXELS {
            return Err("Invalid visual page region".into());
        }
        Ok(self)
    }
    fn view(self, page_width: f64, page_height: f64) -> Result<RasterView, String> {
        self.checked()?;
        // Reuse page-dimension admission, without the whole-page raster scale cap.
        fit(page_width, page_height, 128)?;
        let scale_x = f64::from(self.width) / (page_width * (self.right - self.left));
        let scale_y = f64::from(self.height) / (page_height * (self.bottom - self.top));
        let x = -self.left * page_width * scale_x;
        let y = -self.top * page_height * scale_y;
        // Keep transforms representable by the raster backend even for subnormal bounds.
        if ![scale_x, scale_y, x, y].iter().all(|v| v.is_finite()) || scale_x <= 0.0 || scale_y <= 0.0
            || scale_x > 1e6 || scale_y > 1e6 || x.abs() > 1e9 || y.abs() > 1e9 {
            return Err("Visual region geometry exceeds the raster bounds".into());
        }
        Ok(RasterView { scale_x, scale_y, x, y, width: self.width, height: self.height })
    }
}

#[derive(Debug)]
pub struct Page {
    pub format: u32, // 1 PNG; 2 PDF containing exactly the requested print page.
    pub width: u32,
    pub height: u32,
    pub page: u32,
    pub pages: u32,
    pub note: String,
    pub data: Vec<u8>,
    pub raster_regions: image_regions::ImageRegions,
}
fn fail(s: impl ToString) -> String {
    s.to_string()
}
fn checked_dimensions(w: u32, h: u32) -> Result<(), String> {
    if w == 0 || h == 0 || w > 16384 || h > 16384 || u64::from(w) * u64::from(h) > 8_000_000 {
        return Err("Image dimensions exceed the bounded visual reader".into());
    }
    Ok(())
}
fn fit(w: f64, h: f64, side: u32) -> Result<(f64, u32, u32), String> {
    if !w.is_finite() || !h.is_finite() || w <= 0.0 || h <= 0.0 || w > 20000.0 || h > 20000.0 {
        return Err("Unsupported document page dimensions".into());
    }
    let k = (side as f64 / w.max(h)).min(if side <= 1400 { 2.0 } else { 4.0 });
    let ow = ((w * k).ceil().max(1.0) as u32).min(side);
    let oh = ((h * k).ceil().max(1.0) as u32).min(side);
    if u64::from(ow) * u64::from(oh) > OUTPUT_PIXELS {
        return Err("Visual output pixel limit".into());
    }
    Ok((k, ow, oh))
}
// Ceil-sized output can extend fractionally beyond the slide's background path.
// Only an actually opaque resolved solid paper is extended to that final edge.
fn clear_opaque_slide_paper(pres: &deckcraft_model::Presentation, index: usize, opts: &mut deckcraft_render::RenderOpts<'_>) {
    let Some(slide) = pres.slides.get(index) else { return; };
    let Some(ctx) = deckcraft_model::resolve::Ctx::for_slide(pres, slide) else { return; };
    let (fill, ph) = deckcraft_model::resolve::background(&ctx, Some(slide));
    if let deckcraft_model::style::Fill::Solid { color } = fill {
        let color = ctx.color(&color, ph);
        if color.a == 255 { opts.clear = Some(color); }
    }
}
fn worksheet_extent(row: u32, col: u32) -> Result<(), String> {
    if !(1..=16_384).contains(&row) || !(1..=512).contains(&col) {
        return Err("Worksheet extent exceeds visual reader budget".into());
    }
    Ok(())
}
fn image_size(data: &[u8]) -> Result<(u32, u32), String> {
    image::ImageReader::new(Cursor::new(data))
        .with_guessed_format()
        .map_err(fail)?
        .into_dimensions()
        .map_err(fail)
}
// Container identification only: DeckCraft displays a clip's poster/glyph, never plays it.
fn is_clip(data: &[u8]) -> bool {
    (data.starts_with(b"RIFF") && matches!(data.get(8..12), Some(b"WAVE" | b"AVI ")))
        || data.starts_with(b"OggS")
        || data.starts_with(b"fLaC")
        || (data.len() >= 10 && data.starts_with(b"ID3") && (2..=4).contains(&data[3]))
        || (data.len() >= 4
            && data[0] == 0xff
            && data[1] & 0xe0 == 0xe0
            && data[1] & 0x18 != 0x08
            && data[1] & 0x06 != 0
            && data[2] & 0xf0 != 0xf0
            && data[2] & 0x0c != 0x0c)
        || data.starts_with(b"\x30\x26\xb2\x75\x8e\x66\xcf\x11\xa6\xd9\x00\xaa\x00\x62\xce\x6c")
        || (data.starts_with(b"\x1a\x45\xdf\xa3")
            && data[..data.len().min(4096)]
                .windows(7)
                .any(|w| w == b"\x42\x82\x84webm"))
        || (data.len() >= 16
            && data.get(4..8) == Some(b"ftyp")
            && matches!(
                data.get(8..12),
                Some(
                    b"isom"
                        | b"iso2"
                        | b"iso3"
                        | b"iso4"
                        | b"iso5"
                        | b"iso6"
                        | b"mp41"
                        | b"mp42"
                        | b"M4V "
                        | b"M4A "
                        | b"qt  "
                        | b"MSNV"
                        | b"3gp4"
                        | b"3gp5"
                        | b"3gp6"
                        | b"3g2a"
                )
            ))
}
fn package(
    bytes: &[u8],
    kind: u32,
    check: &mut dyn FnMut() -> Result<(), String>,
) -> Result<String, String> {
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(fail)?;
    if zip.len() > 2048 {
        return Err("Too many package parts".into());
    }
    let mut expanded = 0u64;
    let mut elements = 0usize;
    let mut text = 0usize;
    let mut external = 0usize;
    let (mut worksheet_cells, mut worksheet_elements, mut worksheet_text) = (0usize, 0usize, 0usize);
    for i in 0..zip.len() {
        check()?;
        let mut f = zip.by_index(i).map_err(fail)?;
        let name = f.name().to_owned();
        let size = f.size();
        if name.len() > 512
            || name
                .split('/')
                .any(|p| p == ".." || p.is_empty() && name.starts_with('/'))
        {
            return Err("Unsupported package part name".into());
        }
        expanded = expanded
            .checked_add(size)
            .ok_or("Package length overflow")?;
        if size > 8 * 1024 * 1024 || expanded > 32 * 1024 * 1024 {
            return Err("Expanded package exceeds visual reader budget".into());
        }
        let is_xml = name.ends_with(".xml") || name.ends_with(".rels");
        let is_image = name.starts_with("word/media/")
            || name.starts_with("ppt/media/")
            || name.starts_with("xl/media/");
        if !is_xml && !is_image {
            let mut actual = 0u64;
            let mut buffer = [0u8; 8192];
            loop {
                check()?;
                let n = f.read(&mut buffer).map_err(fail)?;
                if n == 0 {
                    break;
                }
                actual += n as u64;
                if actual > size {
                    return Err("Package part size mismatch".into());
                }
            }
            if actual != size {
                return Err("Truncated package part".into());
            }
            continue;
        }
        if is_xml && kind != 3 && size > 2 * 1024 * 1024 {
            return Err("XML part exceeds visual reader budget".into());
        }
        let mut data = Vec::with_capacity(size as usize);
        let mut buf = [0u8; 8192];
        loop {
            check()?;
            let n = f.read(&mut buf).map_err(fail)?;
            if n == 0 {
                break;
            }
            data.extend_from_slice(&buf[..n]);
            if data.len() > size as usize {
                return Err("Package part size mismatch".into());
            }
        }
        if data.len() != size as usize {
            return Err("Truncated package part".into());
        }
        if is_image {
            if image::guess_format(&data).is_ok() {
                let (w, h) = image_size(&data)?;
                checked_dimensions(w, h)?;
            } else if !(kind == 2 && is_clip(&data))
                && !name.ends_with(".svg")
                && !name.ends_with(".emf")
                && !name.ends_with(".wmf")
            {
                return Err("Unsupported embedded image encoding".into());
            }
            continue;
        }
        let mut parser = quick_xml::Reader::from_reader(Cursor::new(&data));
        let mut scratch = Vec::new();
        let mut depth = 0usize;
        let (mut sheet_row, mut sheet_col) = (0u32, 0u32);
        let mut worksheet = false;
        loop {
            check()?;
            let event = parser.read_event_into(&mut scratch).map_err(fail)?;
            let starting = matches!(&event, Event::Start(_));
            match event {
                Event::Start(e) | Event::Empty(e) => {
                    if depth == 0 {
                        worksheet = kind == 3 && e.local_name().as_ref() == b"worksheet";
                        if !worksheet && size > 2 * 1024 * 1024 {
                            return Err("XML part exceeds visual reader budget".into());
                        }
                    }
                    // GridCraft streams rows independently of the enclosing root name.
                    let row_element = kind == 3 && e.local_name().as_ref() == b"row";
                    let cell = kind == 3 && e.local_name().as_ref() == b"c";
                    if row_element {
                        sheet_row = sheet_row.saturating_add(1);
                        sheet_col = 0;
                    }
                    if cell {
                        sheet_col = sheet_col.saturating_add(1);
                        worksheet_cells += 1;
                        if worksheet_cells > 131_072 {
                            return Err("Workbook cell count exceeds visual reader budget".into());
                        }
                    }
                    let mut cell_row = sheet_row;
                    let (count, limit) = if worksheet {
                        (&mut worksheet_elements, 524_288)
                    } else {
                        // A layered 96-slide deck uses about 138k elements across slides, notes
                        // and layouts. Keep a finite package-wide budget for the eager importer.
                        (&mut elements, if kind == 2 { 262_144 } else { 64_000 })
                    };
                    *count += 1;
                    if *count > limit {
                        return Err("Document structure exceeds visual reader budget".into());
                    }
                    for a in e.attributes().with_checks(true) {
                        let a = a.map_err(fail)?;
                        if a.value.len() > 8192 {
                            return Err("Document attribute exceeds visual reader budget".into());
                        }
                        if a.key.as_ref() == b"TargetMode" && a.value.as_ref() == b"External" {
                            external += 1;
                        }
                        if row_element && a.key.as_ref() == b"r" {
                            sheet_row = std::str::from_utf8(&a.value)
                                .map_err(fail)?
                                .parse::<u32>()
                                .map_err(fail)?;
                        }
                        if cell && a.key.as_ref() == b"r" {
                            let v = std::str::from_utf8(&a.value).map_err(fail)?;
                            sheet_col = v.bytes().take_while(|c| c.is_ascii_alphabetic()).fold(
                                0u32,
                                |n, c| {
                                    n.saturating_mul(26)
                                        .saturating_add((c.to_ascii_uppercase() - b'A' + 1) as u32)
                                },
                            );
                            cell_row = v
                                .trim_start_matches(|c: char| c.is_ascii_alphabetic())
                                .parse::<u32>()
                                .map_err(fail)?;
                        }
                    }
                    // Formatting-only rows may span Excel's full grid. Actual cells retain
                    // the smaller visual-reader extent, checked independently below.
                    if row_element && !(1..=gridcraft_core::MAX_ROWS).contains(&sheet_row) {
                        return Err("Invalid worksheet row metadata".into());
                    }
                    if cell {
                        worksheet_extent(cell_row, sheet_col)?;
                    }
                    if starting {
                        depth += 1;
                        if depth > 64 {
                            return Err("Document XML depth exceeds visual reader budget".into());
                        }
                    }
                }
                Event::Text(e) => {
                    let (count, limit) = if worksheet {
                        (&mut worksheet_text, 2 * 1024 * 1024)
                    } else {
                        (&mut text, 512 * 1024)
                    };
                    *count = count.checked_add(e.len()).ok_or("Text length overflow")?;
                    if *count > limit {
                        return Err("Document text exceeds visual reader budget".into());
                    }
                }
                Event::CData(e) => {
                    let (count, limit) = if worksheet {
                        (&mut worksheet_text, 2 * 1024 * 1024)
                    } else {
                        (&mut text, 512 * 1024)
                    };
                    *count = count.checked_add(e.len()).ok_or("Text length overflow")?;
                    if *count > limit {
                        return Err("Document text exceeds visual reader budget".into());
                    }
                }
                Event::End(_) => depth = depth.saturating_sub(1),
                Event::DocType(_) => {
                    return Err("XML declarations with document types are unsupported".into());
                }
                Event::Eof => {
                    if !worksheet && size > 2 * 1024 * 1024 {
                        return Err("XML part exceeds visual reader budget".into());
                    }
                    break;
                }
                _ => {}
            }
            scratch.clear();
        }
    }
    Ok(if external > 0 {
        format!(
            "{} external resources/links omitted; offline reader",
            external
        )
    } else {
        String::new()
    })
}
pub fn render(
    bytes: &[u8],
    kind: u32,
    page: u32,
    side: u32,
    cancel: impl FnMut() -> bool,
) -> Result<Page, String> {
    render_target(bytes, kind, page, side, None, cancel)
}

pub fn render_region(bytes: &[u8], kind: u32, page: u32, region: Region,
    cancel: impl FnMut() -> bool) -> Result<Page, String> {
    render_target(bytes, kind, page, 0, Some(region), cancel)
}

fn render_target(bytes: &[u8], kind: u32, page: u32, side: u32, region: Option<Region>,
    mut cancel: impl FnMut() -> bool) -> Result<Page, String> {
    let start = Instant::now();
    let mut check = || {
        if cancel() {
            Err("CANCELLED".into())
        } else if start.elapsed() > Duration::from_secs(15) {
            Err("Visual reader time limit".into())
        } else {
            Ok(())
        }
    };
    check()?;
    if bytes.is_empty() || bytes.len() > INPUT_LIMIT {
        return Err("Visual reader input limit is 16 MiB".into());
    }
    if let Some(region) = region { region.checked()?; }
    else if !(128..=MAX_SIDE).contains(&side) {
        return Err("Output side must be 128 through 2800".into());
    }
    let _guard = loop {
        check()?;
        match RENDER_LOCK.try_lock() {
            Ok(g) => break g,
            Err(std::sync::TryLockError::WouldBlock) => {
                std::thread::sleep(Duration::from_millis(5))
            }
            Err(std::sync::TryLockError::Poisoned(e)) => {
                RENDER_LOCK.clear_poison();
                break e.into_inner();
            }
        }
    };
    check()?;
    if !(1..=3).contains(&kind) {
        return Err("No visual renderer for this document format".into());
    }
    let preflight = package(bytes, kind, &mut check)?;
    check()?;
    let mut result = match kind {
        1 => {
            let doc = wordcraft_docx::read(bytes).map_err(fail)?;
            check()?;
            let layout = wordcraft_layout::layout(
                &doc,
                &mut wordcraft_layout::LayoutCache::new(),
                &wordcraft_layout::LayoutOptions::default(),
            );
            check()?;
            if layout.pages.is_empty() || layout.pages.len() > MAX_PAGES {
                return Err("DOCX page limit or empty layout".into());
            }
            if page as usize >= layout.pages.len() {
                return Err("Requested page is outside this document".into());
            }
            let index = page as usize;
            let p = &layout.pages[index];
            let view = if let Some(region) = region { region.view(p.w as f64, p.h as f64)? }
            else {
                let (k, _, _) = fit(p.w as f64, p.h as f64, side)?;
                // Preserve the whole-page f32 output-dimension contract.
                let scale = (k as f32).next_down();
                let w = (p.w * scale).ceil().max(1.0) as u32;
                let h = (p.h * scale).ceil().max(1.0) as u32;
                if w > side || h > side { return Err("DOCX output dimensions exceed the preview size".into()); }
                RasterView::full_page(p.w as f64, p.h as f64, w, h)?
            };
            let (w, h) = (view.width, view.height);
            let raster_regions = image_regions::word_view(&doc, p, view.affine(), w, h, &mut check)?;
            let output = wordcraft_render::render_region(&doc, p, w, h, view.affine(),
                &wordcraft_render::RenderOptions::default());
            check()?;
            if output.width != w
                || output.height != h
                || output.pixels.len() != w as usize * h as usize * 4
            {
                return Err("DOCX renderer output size mismatch".into());
            }
            Page {
                format: 1,
                width: w,
                height: h,
                page: index as u32,
                pages: layout.pages.len() as u32,
                note:
                    "Visual layout; font substitution or unsupported features may affect appearance"
                        .into(),
                data: output.to_png(),
                raster_regions,
            }
        }
        2 => {
            let pres = deckcraft_pptx::import(bytes).map_err(fail)?;
            check()?;
            if pres.slides.is_empty() || pres.slides.len() > MAX_PAGES {
                return Err("PPTX slide limit or empty presentation".into());
            }
            if page as usize >= pres.slides.len() {
                return Err("Requested slide is outside this presentation".into());
            }
            let index = page as usize;
            let view = if let Some(region) = region { region.view(pres.slide_size.width, pres.slide_size.height)? }
            else {
                let (_, width, height) = fit(pres.slide_size.width, pres.slide_size.height, side)?;
                RasterView::full_page(pres.slide_size.width, pres.slide_size.height, width, height)?
            };
            let (w, h) = (view.width, view.height);
            let mut opts = deckcraft_render::RenderOpts {
                scale: view.scale_x,
                offset: (view.x, view.y),
                view: Some(view.affine()),
                size: Some((w, h)),
                threads: 0,
                ..Default::default()
            };
            clear_opaque_slide_paper(&pres, index, &mut opts);
            let raster_regions = image_regions::slide_view(&pres, index, view.affine(), w, h, &mut check)?;
            let output = deckcraft_render::render_slide(&pres, index, &opts);
            check()?;
            if output.width != w
                || output.height != h
                || output.pixels.len() != w as usize * h as usize * 4
            {
                return Err("PPTX renderer output size mismatch".into());
            }
            Page {
                format: 1,
                width: w,
                height: h,
                page: index as u32,
                pages: pres.slides.len() as u32,
                note: "Visual slide; font substitution; playback/animation not rendered".into(),
                data: output.to_png(),
                raster_regions,
            }
        }
        3 => {
            let (wb, _report) = gridcraft_xlsx::read_xlsx(bytes).map_err(fail)?;
            check()?;
            let mut layouts = gridcraft_engine::cmd::print::layouts(
                &wb,
                &gridcraft_engine::cmd::print::PrintOptions {
                    all_sheets: true,
                    ..Default::default()
                },
            );
            check()?;
            let total: usize = layouts.iter().map(|l| l.pages.len()).sum();
            if total == 0 || total > MAX_PAGES {
                return Err("XLSX print page limit or empty workbook".into());
            }
            if page as usize >= total {
                return Err("Requested page is outside this workbook".into());
            }
            let index = page as usize;
            let mut offset = 0usize;
            let mut chosen = None;
            for mut l in layouts.drain(..) {
                if index < offset + l.pages.len() {
                    let p = l.pages[index - offset];
                    l.pages = vec![p];
                    chosen = Some(l);
                    break;
                }
                offset += l.pages.len();
            }
            let chosen = chosen.ok_or("Worksheet print page unavailable")?;
            let view = if let Some(region) = region { region.view(chosen.page_w as f64, chosen.page_h as f64)? }
            else {
                let (_, width, height) = fit(chosen.page_w as f64, chosen.page_h as f64, side)?;
                RasterView::full_page(chosen.page_w as f64, chosen.page_h as f64, width, height)?
            };
            let (w, h) = (view.width, view.height);
            let raster_regions = image_regions::sheet_view(&wb, &chosen, view.affine(), w, h, &mut check)?;
            let output = gridcraft_engine::cmd::print::render_raster_region(
                &wb, &[chosen], "Document", "", w, h, view.scale_x, view.scale_y, view.x, view.y, index + 1, total,
            )?;
            check()?;
            if output.width != w || output.height != h {
                return Err("Worksheet raster output dimensions changed".into());
            }
            let data = wordcraft_render::Rendered {
                width: w,
                height: h,
                pixels: output.pixels,
            }
            .to_png();
            Page{format:1,width:w,height:h,page:index as u32,pages:total as u32,note:"GridCraft worksheet print geometry; cached values; Unicode font fallback; system-font coverage varies".into(),data,raster_regions}
        }
        _ => {
            return Err("No visual renderer for this document format".into());
        }
    };
    check()?;
    if !preflight.is_empty() {
        result.note.push_str("; ");
        result.note.push_str(&preflight)
    }
    if result.data.is_empty() || result.data.len() > OUTPUT_LIMIT {
        return Err("Visual output exceeds budget or is empty".into());
    }
    Ok(result)
}
pub fn wire(result: Result<Page, String>) -> Vec<u8> {
    wire_target(result, None)
}
pub fn wire_region(result: Result<Page, String>, region: Region) -> Vec<u8> {
    wire_target(result, Some(region))
}
fn wire_target(result: Result<Page, String>, region: Option<Region>) -> Vec<u8> {
    let mut out = if region.is_some() { b"ZFR3" } else { b"ZFR2" }.to_vec();
    match result {
        Ok(p) => {
            for n in [
                p.format,
                p.width,
                p.height,
                p.page,
                p.pages,
                p.note.len() as u32,
                p.data.len() as u32,
            ] {
                out.extend_from_slice(&n.to_le_bytes())
            }
            out.extend_from_slice(&p.raster_regions.as_ref().map_or(u32::MAX, |r| r.len() as u32).to_le_bytes());
            if let Some(region) = region {
                for value in [region.left, region.top, region.right, region.bottom] {
                    out.extend_from_slice(&(value as f32).to_le_bytes());
                }
            }
            if let Some(regions) = p.raster_regions {
                for region in regions { for value in region { out.extend_from_slice(&value.to_le_bytes()); } }
            }
            out.extend_from_slice(p.note.as_bytes());
            out.extend_from_slice(&p.data);
        }
        Err(e) => {
            let e = e.chars().take(2048).collect::<String>();
            for n in [0, 0, 0, 0, 0, e.len() as u32, 0] {
                out.extend_from_slice(&n.to_le_bytes())
            }
            out.extend_from_slice(&u32::MAX.to_le_bytes());
            if let Some(region) = region {
                for value in [region.left, region.top, region.right, region.bottom] {
                    out.extend_from_slice(&(value as f32).to_le_bytes());
                }
            }
            out.extend_from_slice(e.as_bytes());
        }
    }
    out
}

#[cfg(test)]
mod tests;
