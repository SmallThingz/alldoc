//! Complete, bounded raster-image enclosures from the same pinned public layout models.
//! Nonempty regions require opaque rectangular pictures; unsupported alpha/geometry is unknown.
//! An empty list proves only image absence, never page opacity.
use deckcraft_geom::{Affine, Rect};
use deckcraft_model::{resolve::{self, Ctx}, style::{Fill, PictureFill, PictureMode}, Presentation, Shape, ShapeKind};
pub type ImageRegions = Option<Vec<[f32; 4]>>;
// Header/chunk metadata only: do not decode image pixels again. Alpha-capable PNGs,
// transparent palette/grayscale/RGB PNGs and other encodings remain unknown.
fn opaque_encoding(bytes: &[u8]) -> bool {
    if bytes.starts_with(&[0xff, 0xd8, 0xff]) { return true; }
    if bytes.get(..8) != Some(b"\x89PNG\r\n\x1a\n") || bytes.get(12..16) != Some(b"IHDR")
        || !matches!(bytes.get(25), Some(0 | 2 | 3)) { return false; }
    let mut at = 8usize;
    for _ in 0..4096 {
        let Some(header) = bytes.get(at..at.saturating_add(8)) else { return false; };
        let len = u32::from_be_bytes([header[0], header[1], header[2], header[3]]) as usize;
        let Some(end) = at.checked_add(12).and_then(|v| v.checked_add(len)).filter(|v| *v <= bytes.len()) else { return false; };
        if &header[4..8] == b"tRNS" { return false; }
        if &header[4..8] == b"IEND" { return len == 0; }
        at = end;
    }
    false
}
fn opaque_picture(pres: &Presentation, fill: &PictureFill) -> bool {
    fill.alpha.unwrap_or(1.0) == 1.0 && fill.crop == [0.0; 4]
        && fill.adjust == Default::default()
        && matches!(&fill.mode, PictureMode::Stretch { fill_rect } if *fill_rect == [0.0; 4])
        && pres.media(fill.media).is_some_and(|m| opaque_encoding(&m.data))
}
const LIMIT: usize = 64;
struct Regions { width: f64, height: f64, items: ImageRegions }
impl Regions {
    fn new(width: u32, height: u32) -> Self {
        Self { width: width as f64, height: height as f64, items: Some(Vec::new()) }
    }
    fn add(&mut self, r: Rect, transform: Affine) {
        if self.items.is_none() { return; }
        let a = transform.as_coeffs();
        if !a.iter().all(|v| v.is_finite()) || a[1].abs() > 1e-10 || a[2].abs() > 1e-10
            || ![r.x0, r.y0, r.x1, r.y1].iter().all(|v| v.is_finite()) {
            self.items = None; return;
        }
        if r.width() <= 0.0 || r.height() <= 0.0 { return; }
        let r = transform.transform_rect_bbox(r);
        if ![r.x0, r.y0, r.x1, r.y1].iter().all(|v| v.is_finite()) { self.items = None; return; }
        let l = r.x0.max(0.0); let t = r.y0.max(0.0);
        let right = r.x1.min(self.width); let bottom = r.y1.min(self.height);
        if right <= l || bottom <= t { return; }
        if let Some(items) = &mut self.items {
            if items.len() == LIMIT { self.items = None; return; }
            items.push([(l / self.width) as f32, (t / self.height) as f32,
                (right / self.width) as f32, (bottom / self.height) as f32]);
        }
    }
}

pub fn word_view(doc: &wordcraft_doc::Document, page: &wordcraft_layout::Page, view: Affine,
    width: u32, height: u32, check: &mut impl FnMut() -> Result<(), String>) -> Result<ImageRegions, String> {
    use wordcraft_layout::display::{page_display, DisplayOptions, Draw};
    let mut out = Regions::new(width, height);
    // The public display list also covers floating pictures, headers and footers.
    // Drop this list before rasterization; no second pixel buffer or media decode.
    for (i, item) in page_display(doc, page, &DisplayOptions::default()).into_iter().enumerate() {
        if i % 128 == 0 { check()?; }
        if let Draw::Image { rect, alpha, media, crop } = item {
            if alpha == 0.0 { continue; }
            if alpha != 1.0 || !crop.iter().all(|v| v.is_finite())
                || !doc.media.get(&media).is_some_and(|data| opaque_encoding(data)) { return Ok(None); }
            { out.add(Rect::new(rect.x as f64, rect.y as f64, rect.right() as f64,
                rect.bottom() as f64), view); }
            if out.items.is_none() { break; }
        }
    }
    Ok(out.items)
}

fn picture(fill: Option<&Fill>) -> bool { matches!(fill, Some(Fill::Picture(_))) }
fn slide_shapes(ctx: &Ctx, shapes: &[Shape], parent: Affine, depth: usize,
    out: &mut Regions, visited: &mut usize, check: &mut impl FnMut() -> Result<(), String>) -> Result<(), String> {
    for s in shapes {
        if out.items.is_none() { break; }
        *visited += 1;
        if *visited % 128 == 0 { check()?; }
        if *visited > 32_768 || depth > 32 { out.items = None; break; }
        if s.hidden { continue; }
        let x = resolve::xfrm(ctx, s);
        if !x.is_finite() { continue; } // The renderer skips the same invalid shape.
        let m = parent * x.affine();
        match &s.kind {
            ShapeKind::Group { children, child } => {
                let r = Rect::new(child.x, child.y, child.x + child.w, child.y + child.h);
                slide_shapes(ctx, children, parent * deckcraft_geom::group_child_affine(&x, r), depth + 1, out, visited, check)?;
            }
            ShapeKind::Table(t) => {
                // Table cell layout is private to the renderer. Do not guess its image bounds.
                if t.rows.iter().any(|r| r.cells.iter().any(|c| picture(c.fill.as_ref()))) { out.items = None; }
            }
            ShapeKind::Chart(c) => {
                if picture(c.chart_fill.as_ref()) || picture(c.plot_fill.as_ref()) { out.items = None; }
            }
            ShapeKind::Ink { .. } => {}
            ShapeKind::Opaque { preview: Some(_), .. } => { out.items = None; }
            _ => {
                let (fill, _) = resolve::fill(ctx, s);
                let is_image = matches!(&s.kind, ShapeKind::Picture { .. })
                    || matches!(&s.kind, ShapeKind::Media(media) if media.poster.is_some()) || picture(fill.as_ref());
                if !is_image { continue; }
                let (effects, _) = resolve::effects(ctx, s);
                if effects.as_ref().is_some_and(|e| e.reflection.is_some() || e.soft_edge.is_some()) {
                    out.items = None; continue;
                }
                let opaque = match &s.kind {
                    ShapeKind::Picture { fill } => opaque_picture(ctx.pres, fill),
                    ShapeKind::Media(media) => media.poster.and_then(|id| ctx.pres.media(id))
                        .is_some_and(|m| opaque_encoding(&m.data)),
                    _ => match &fill { Some(Fill::Picture(p)) => opaque_picture(ctx.pres, p), _ => false },
                };
                if !opaque || (!matches!(&s.kind, ShapeKind::Media(_)) && s.geom.preset_name() != Some("rect")) {
                    out.items = None; continue;
                }
                out.add(Rect::new(0.0, 0.0, x.w, x.h), m);
            }
        }
    }
    Ok(())
}

pub fn slide_view(pres: &Presentation, index: usize, view: Affine, width: u32, height: u32,
    check: &mut impl FnMut() -> Result<(), String>) -> Result<ImageRegions, String> {
    let Some(slide) = pres.slides.get(index) else { return Ok(None) };
    let Some(ctx) = Ctx::for_slide(pres, slide) else { return Ok(None) };
    let mut out = Regions::new(width, height);
    let (background, _) = resolve::background(&ctx, Some(slide));
    if let Fill::Picture(fill) = &background {
        if !opaque_picture(pres, fill) { return Ok(None); }
        out.add(Rect::new(0.0, 0.0, pres.slide_size.width, pres.slide_size.height), view);
    }
    let mut visited = 0;
    let (show_layout, show_master) = resolve::show_master_shapes(slide, ctx.layout);
    if show_master {
        let mc = Ctx::for_master(pres, ctx.master);
        for s in &ctx.master.shapes {
            if s.ph.is_none() { slide_shapes(&mc, std::slice::from_ref(s), view, 0, &mut out, &mut visited, check)?; }
        }
    }
    if show_layout && let Some(layout) = ctx.layout {
        let lc = Ctx::for_layout(pres, ctx.master, layout);
        for s in &layout.shapes {
            if s.ph.is_none() { slide_shapes(&lc, std::slice::from_ref(s), view, 0, &mut out, &mut visited, check)?; }
        }
    }
    slide_shapes(&ctx, &slide.shapes, view, 0, &mut out, &mut visited, check)?;
    Ok(out.items)
}

pub fn sheet_view(wb: &gridcraft_model::Workbook, lay: &gridcraft_engine::cmd::print::SheetLayout,
    view: Affine, width: u32, height: u32, check: &mut impl FnMut() -> Result<(), String>) -> Result<ImageRegions, String> {
    let Some(sh) = wb.sheet(lay.sheet) else { return Ok(None) };
    let Some(&(ri, ci)) = lay.pages.first() else { return Ok(None) };
    let (Some(&rows), Some(&cols)) = (lay.row_bands.get(ri), lay.col_bands.get(ci)) else { return Ok(None) };
    let mut out = Regions::new(width, height);
    if sh.images.is_empty() { return Ok(out.items); }
    // Same public print layout, repeated-title offsets and f32 accumulation as draw_document.
    let size = |range: (u32, u32), row: bool| -> f32 {
        (range.0..=range.1).map(|i| if row { sh.row_height(i) } else { sh.col_width(i) }).sum()
    };
    let title = |range: Option<(u32, u32)>, band: (u32, u32)| range.filter(|r| r.0 < band.0).map(|r| (r.0, r.1.min(band.0 - 1)));
    let tr = title(lay.title_rows, rows); let tc = title(lay.title_cols, cols);
    let rh = tr.map(|r| size(r, true)).unwrap_or(0.0); let cw = tc.map(|c| size(c, false)).unwrap_or(0.0);
    let k = 0.75 * lay.scale;
    let aw = (lay.page_w - lay.margins[0] - lay.margins[1]).max(36.0);
    let ah = (lay.page_h - lay.margins[2] - lay.margins[3]).max(36.0);
    let content_w = (lay.heading_w + (cw + size(cols, false))) * k;
    let content_h = (lay.heading_h + (rh + size(rows, true))) * k;
    let ox = lay.margins[0] + if lay.settings.center_h { ((aw - content_w) / 2.0).max(0.0) } else { 0.0 };
    let oy = lay.margins[2] + if lay.settings.center_v { ((ah - content_h) / 2.0).max(0.0) } else { 0.0 };
    let bx = ox + lay.heading_w * k + cw * k; let by = oy + lay.heading_h * k + rh * k;
    let ex = (cols.0..=cols.1).fold(bx, |x, c| x + sh.col_width(c) * k);
    let ey = (rows.0..=rows.1).fold(by, |y, r| y + sh.row_height(r) * k);
    let sx = sh.col_left(cols.0); let sy = sh.row_top(rows.0);
    for (i, image) in sh.images.iter().enumerate() {
        if i % 128 == 0 { check()?; }
        let a = &image.anchor;
        let x = bx + ((sh.col_left(a.cell.col) + a.dx as f64 - sx) as f32) * k;
        let y = by + ((sh.row_top(a.cell.row) + a.dy as f64 - sy) as f32) * k;
        let right = x + a.width.max(0.0) * k; let bottom = y + a.height.max(0.0) * k;
        if ![x, y, right, bottom, bx, by, ex, ey].iter().all(|v| v.is_finite()) { return Ok(None); }
        let clipped = Rect::new(x.max(bx) as f64, y.max(by) as f64, right.min(ex) as f64, bottom.min(ey) as f64);
        if clipped.width() <= 0.0 || clipped.height() <= 0.0 { continue; }
        if !opaque_encoding(&image.data) { return Ok(None); }
        out.add(clipped, view);
        if out.items.is_none() { break; }
    }
    Ok(out.items)
}

/// Opaque image rectangles in the same nonpaginated, translated viewport coordinates.
pub fn sheet_viewport(sheet: &gridcraft_model::Sheet, rows: &gridcraft_model::LineIndex,
    columns: &gridcraft_model::LineIndex, x: f64, y: f64, scale: f64, width: u32, height: u32,
    check: &mut impl FnMut() -> Result<(), String>) -> Result<ImageRegions, String> {
    let mut out = Regions::new(width, height);
    for (i, image) in sheet.images.iter().enumerate() {
        if i % 128 == 0 { check()?; }
        let anchor = &image.anchor;
        let left = (columns.start(anchor.cell.col) + anchor.dx as f64 - x) * scale;
        let top = (rows.start(anchor.cell.row) + anchor.dy as f64 - y) * scale;
        let right = left + anchor.width.max(0.0) as f64 * scale;
        let bottom = top + anchor.height.max(0.0) as f64 * scale;
        if right <= 0.0 || bottom <= 0.0 || left >= width as f64 || top >= height as f64 { continue; }
        if !opaque_encoding(&image.data) { return Ok(None); }
        out.add(Rect::new(left, top, right, bottom), Affine::IDENTITY);
        if out.items.is_none() { break; }
    }
    Ok(out.items)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn complete_bounds_clip_without_claiming_unsupported_or_overflowed_geometry() {
        let mut out = Regions::new(100, 200);
        assert_eq!(out.items.as_ref().unwrap().len(), 0);
        out.add(Rect::new(-10.0, 20.0, 50.0, 250.0), Affine::IDENTITY);
        assert_eq!(out.items, Some(vec![[0.0, 0.1, 0.5, 1.0]]));
        out.add(Rect::new(0.0, 0.0, 10.0, 10.0), Affine::rotate(0.3));
        assert!(out.items.is_none());
        let mut out = Regions::new(100, 100);
        for _ in 0..=LIMIT { out.add(Rect::new(0.0, 0.0, 10.0, 10.0), Affine::IDENTITY); }
        assert!(out.items.is_none());
    }
}
