//! Bounded resident worksheet reader. Saved workbook values only; no calculation or I/O from content.
use gridcraft_model::{LineIndex, Sheet, Visibility, Workbook};
use serde_json::{Value, json};
use std::io::{BufRead, Read, Write};
use std::path::Path;
use std::time::{Duration, Instant};

pub const ROWS: u32 = 16_384;
pub const COLUMNS: u32 = 512;
const JSON_LIMIT: usize = 512 * 1024;
const COMMAND_LIMIT: usize = 1024;
const FIDELITY: &str = "VISUAL_GRID_BEST_EFFORT";

struct Geometry {
    sheet: usize,
    rows: LineIndex,
    columns: LineIndex,
    cf: Option<gridcraft_engine::cf::CfCache>,
}

pub struct WorkbookReader {
    workbook: Workbook,
    sheets: Vec<Value>,
    geometry: Option<Geometry>,
    warnings: Vec<String>,
}

fn check(start: Instant) -> Result<(), String> {
    if start.elapsed() > Duration::from_secs(15) { Err("Worksheet request time limit".into()) } else { Ok(()) }
}

impl WorkbookReader {
    pub fn open(bytes: &[u8]) -> Result<Self, String> {
        let start = Instant::now();
        if bytes.is_empty() || bytes.len() > super::INPUT_LIMIT { return Err("Worksheet input limit is 16 MiB".into()); }
        let note = super::package(bytes, 3, &mut || check(start))?;
        let (workbook, _) = gridcraft_xlsx::read_xlsx(bytes).map_err(super::fail)?;
        check(start)?;
        Self::from_workbook(workbook, note)
    }

    fn from_workbook(mut workbook: Workbook, note: String) -> Result<Self, String> {
        if workbook.sheets.is_empty() || workbook.sheets.len() > super::MAX_PAGES {
            return Err("Worksheet count exceeds reader budget".into());
        }
        let mut sheets = Vec::new();
        let mut cells = 0usize;
        let mut direction = false;
        for (index, sheet) in workbook.sheets.iter_mut().enumerate() {
            let sheet = std::sync::Arc::make_mut(sheet);
            // Whole-column/row formatting commonly extends beyond actual content. Keep only
            // the bounded viewport's overrides, without changing the actual cell limits.
            sheet.rows.retain(|index, _| *index < ROWS);
            sheet.cols.retain(|index, _| *index < COLUMNS);
            validate_sheet(sheet)?;
            cells = cells.checked_add(sheet.cells.len()).ok_or("Worksheet cell count overflow")?;
            if cells > 131_072 { return Err("Worksheet cells exceed reader budget".into()); }
            if sheet.visibility != Visibility::Visible { continue; }
            let rows = LineIndex::new(&sheet.rows, sheet.default_row_height, ROWS);
            let columns = LineIndex::new(&sheet.cols, sheet.default_col_width, COLUMNS);
            let width = columns.start(COLUMNS);
            let height = rows.start(ROWS);
            if !width.is_finite() || !height.is_finite() || !(1.0..=1e9).contains(&width) || !(1.0..=1e9).contains(&height) {
                return Err("Worksheet geometry exceeds reader budget".into());
            }
            let used = sheet.used_range();
            let mut used_rows = used.map_or(0, |r| r.end.row + 1);
            let mut used_columns = used.map_or(0, |r| r.end.col + 1);
            for range in &sheet.merges {
                used_rows = used_rows.max(range.end.row + 1);
                used_columns = used_columns.max(range.end.col + 1);
            }
            for anchor in sheet.images.iter().map(|v| &v.anchor).chain(sheet.charts.iter().map(|v| &v.anchor))
                .chain(sheet.shapes.iter().map(|v| &v.anchor)) {
                used_rows = used_rows.max(rows.at(rows.start(anchor.cell.row) + anchor.dy as f64 + anchor.height as f64) + 1);
                used_columns = used_columns.max(columns.at(columns.start(anchor.cell.col) + anchor.dx as f64 + anchor.width as f64) + 1);
            }
            let (freeze_rows, freeze_columns) = sheet.freeze.unwrap_or((0, 0));
            direction |= sheet.right_to_left || freeze_rows > 0 || freeze_columns > 0;
            sheets.push(json!({"index":index,"name":sheet.name,"rowCount":ROWS,"columnCount":COLUMNS,
                "usedRows":used_rows.min(ROWS),"usedColumns":used_columns.min(COLUMNS),"width":width,"height":height,
                "showGridlines":sheet.show_gridlines,"showHeadings":sheet.show_headings,"rightToLeft":sheet.right_to_left,
                "freezeRows":freeze_rows.min(ROWS),"freezeColumns":freeze_columns.min(COLUMNS)}));
        }
        if sheets.is_empty() { return Err("Workbook has no visible worksheet".into()); }
        let mut warnings = vec!["Saved values; unsupported chart expressions and formula-only conditional formatting are omitted.".into()];
        if !note.is_empty() { warnings.push(note); }
        if direction { warnings.push("Worksheet direction and frozen panes are retained as metadata; viewport content is not mirrored or frozen.".into()); }
        Ok(Self { workbook, sheets, geometry: None, warnings })
    }

    pub fn info(&self) -> Value {
        let active = self.workbook.active_sheet;
        let active = if self.sheets.iter().any(|v| v["index"].as_u64() == Some(active as u64)) { active }
            else { self.sheets[0]["index"].as_u64().unwrap_or(0) as usize };
        json!({"version":1,"fidelity":FIDELITY,"activeSheet":active,"sheets":self.sheets,"warnings":self.warnings})
    }

    fn prepare(&mut self, index: usize) -> Result<(), String> {
        if !self.sheets.iter().any(|v| v["index"].as_u64() == Some(index as u64)) { return Err("Worksheet unavailable".into()); }
        if self.geometry.as_ref().is_some_and(|g| g.sheet == index) { return Ok(()); }
        let sheet = self.workbook.sheet(index).ok_or("Worksheet unavailable")?;
        self.geometry = Some(Geometry { sheet: index,
            rows: LineIndex::new(&sheet.rows, sheet.default_row_height, ROWS),
            columns: LineIndex::new(&sheet.cols, sheet.default_col_width, COLUMNS),
            cf: if sheet.cond_formats.is_empty() { None } else { Some(gridcraft_engine::cf::CfCache::new()) } });
        Ok(())
    }

    pub fn sheet(&mut self, index: usize) -> Result<Value, String> {
        self.prepare(index)?;
        let geometry = self.geometry.as_ref().ok_or("Worksheet geometry unavailable")?;
        let mut result = self.sheets.iter().find(|v| v["index"].as_u64() == Some(index as u64))
            .cloned().ok_or("Worksheet unavailable")?;
        result["rowEdges"] = json!((0..=ROWS).map(|i| geometry.rows.start(i)).collect::<Vec<_>>());
        result["columnEdges"] = json!((0..=COLUMNS).map(|i| geometry.columns.start(i)).collect::<Vec<_>>());
        Ok(result)
    }

    pub fn render(&mut self, index: usize, x: f64, y: f64, scale: f64, width: u32, height: u32) -> Result<(Value, Vec<u8>), String> {
        let start = Instant::now();
        if ![x, y, scale].iter().all(|v| v.is_finite()) || x < 0.0 || y < 0.0 || !(0.0625..=32.0).contains(&scale)
            || width == 0 || height == 0 || width > super::MAX_SIDE || height > super::MAX_SIDE
            || u64::from(width) * u64::from(height) > super::OUTPUT_PIXELS {
            return Err("Invalid worksheet viewport".into());
        }
        self.prepare(index)?;
        let geometry = self.geometry.as_mut().ok_or("Worksheet geometry unavailable")?;
        if x > geometry.columns.start(COLUMNS) || y > geometry.rows.start(ROWS) { return Err("Worksheet origin is out of range".into()); }
        let sheet = self.workbook.sheet(index).ok_or("Worksheet unavailable")?;
        let regions = super::image_regions::sheet_viewport(sheet, &geometry.rows, &geometry.columns, x, y, scale,
            width, height, &mut || check(start))?;
        let image = gridcraft_engine::cmd::print::render_sheet_region(&self.workbook, index, &geometry.rows,
            &geometry.columns, &mut geometry.cf, x, y, width, height, scale, ROWS, COLUMNS)?;
        check(start)?;
        let data = wordcraft_render::Rendered { width: image.width, height: image.height, pixels: image.pixels }.to_png();
        if data.len() > super::OUTPUT_LIMIT { return Err("Worksheet output exceeds reader budget".into()); }
        check(start)?;
        Ok((json!({"sheet":index,"x":x,"y":y,"scale":scale,"width":width,"height":height,
            "rasterRegions":regions,"fidelity":FIDELITY}), data))
    }
}

fn validate_sheet(sheet: &Sheet) -> Result<(), String> {
    let size = |v: f32| v.is_finite() && (0.0..=65_536.0).contains(&v);
    if sheet.name.len() > 256 || !size(sheet.default_col_width) || !size(sheet.default_row_height)
        || sheet.default_col_width == 0.0 || sheet.default_row_height == 0.0
        || sheet.rows.iter().any(|(i, v)| *i >= ROWS || v.size.is_some_and(|s| !size(s)))
        || sheet.cols.iter().any(|(i, v)| *i >= COLUMNS || v.size.is_some_and(|s| !size(s)))
        || sheet.used_range().is_some_and(|r| r.end.row >= ROWS || r.end.col >= COLUMNS)
        || sheet.merges.iter().any(|r| r.end.row >= ROWS || r.end.col >= COLUMNS) {
        return Err("Worksheet dimensions exceed reader budget".into());
    }
    for anchor in sheet.images.iter().map(|v| &v.anchor).chain(sheet.charts.iter().map(|v| &v.anchor))
        .chain(sheet.shapes.iter().map(|v| &v.anchor)) {
        if anchor.cell.row >= ROWS || anchor.cell.col >= COLUMNS ||
            ![anchor.dx, anchor.dy, anchor.width, anchor.height].iter().all(|v| v.is_finite() && v.abs() <= 1e7) {
            return Err("Worksheet drawing geometry exceeds reader budget".into());
        }
    }
    Ok(())
}

fn frame(output: &mut impl Write, kind: u32, id: u32, metadata: Value, image: &[u8]) -> Result<(), String> {
    let json = serde_json::to_vec(&metadata).map_err(super::fail)?;
    if json.len() > JSON_LIMIT || image.len() > super::OUTPUT_LIMIT { return Err("Worksheet response exceeds reader budget".into()); }
    output.write_all(b"ZSG1").map_err(super::fail)?;
    for value in [kind, id, json.len() as u32, image.len() as u32] { output.write_all(&value.to_le_bytes()).map_err(super::fail)?; }
    output.write_all(&json).map_err(super::fail)?;
    output.write_all(image).map_err(super::fail)?;
    output.flush().map_err(super::fail)
}

fn command(input: &mut impl BufRead) -> Result<Option<Value>, String> {
    let mut bytes = Vec::with_capacity(256);
    loop {
        let available = input.fill_buf().map_err(super::fail)?;
        if available.is_empty() {
            return if bytes.is_empty() { Ok(None) } else { Err("Incomplete worksheet command".into()) };
        }
        let count = available.iter().position(|v| *v == b'\n').map_or(available.len(), |i| i + 1);
        if bytes.len() + count > COMMAND_LIMIT { return Err("Worksheet command exceeds reader budget".into()); }
        let end = available[count - 1] == b'\n';
        bytes.extend_from_slice(&available[..count]);
        input.consume(count);
        if end { return serde_json::from_slice(&bytes).map(Some).map_err(|_| "Invalid worksheet command".into()); }
    }
}

fn integer(value: &Value, name: &str) -> Result<u32, String> {
    value[name].as_u64().and_then(|v| u32::try_from(v).ok()).ok_or_else(|| format!("Invalid worksheet {name}"))
}
fn number(value: &Value, name: &str) -> Result<f64, String> {
    value[name].as_f64().filter(|v| v.is_finite()).ok_or_else(|| format!("Invalid worksheet {name}"))
}

fn run(path: &Path, input: &mut impl BufRead, output: &mut impl Write) -> Result<(), String> {
    let file = std::fs::File::open(path).map_err(|_| "Worksheet input unavailable")?;
    let mut bytes = Vec::new();
    file.take(super::INPUT_LIMIT as u64 + 1).read_to_end(&mut bytes).map_err(|_| "Worksheet input read failed")?;
    let mut reader = WorkbookReader::open(&bytes)?;
    drop(bytes);
    frame(output, 1, 0, reader.info(), &[])?;
    while let Some(request) = command(input)? {
        let id = integer(&request, "id")?;
        if id == 0 { return Err("Worksheet command ID must be positive".into()); }
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Option<(u32, Value, Vec<u8>)>, String> {
            match request["op"].as_str() {
                Some("close") => Ok(None),
                Some("sheet") => Ok(Some((2, reader.sheet(integer(&request, "sheet")? as usize)?, vec![]))),
                Some("render") => {
                    let (metadata, image) = reader.render(integer(&request, "sheet")? as usize, number(&request, "x")?,
                        number(&request, "y")?, number(&request, "scale")?, integer(&request, "width")?, integer(&request, "height")?)?;
                    Ok(Some((3, metadata, image)))
                }
                _ => Err("Unknown worksheet command".into()),
            }
        }));
        match result {
            Ok(Ok(None)) => return Ok(()),
            Ok(Ok(Some((kind, metadata, image)))) => frame(output, kind, id, metadata, &image)?,
            Ok(Err(message)) => frame(output, 0, id, json!({"message":message}), &[])?,
            Err(_) => { frame(output, 0, id, json!({"message":"Worksheet renderer stopped safely"}), &[])?; return Ok(()); }
        }
    }
    Ok(())
}

pub fn serve(path: &Path) -> i32 {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(||
        run(path, &mut std::io::stdin().lock(), &mut std::io::stdout().lock())));
    let message = match result { Ok(Ok(())) => return 0, Ok(Err(message)) => message, Err(_) => "Worksheet reader stopped safely".into() };
    let _ = frame(&mut std::io::stdout().lock(), 0, 0, json!({"message":message}), &[]);
    1
}

#[cfg(test)]
mod tests;
