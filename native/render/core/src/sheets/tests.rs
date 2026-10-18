use super::*;
use gridcraft_core::{CellRef, RangeRef, Value as CellValue};
use gridcraft_model::{Anchor, Chart, ChartKind, Color, Image, LegendPos, LineInfo, Series, Style};
use std::io::Cursor;

fn workbook() -> Workbook {
    let mut workbook = Workbook::new();
    let mut style = Style::default();
    style.align.wrap = true;
    style.font.bold = true;
    let style = workbook.styles.intern(style);
    let sheet = workbook.sheet_mut(0).unwrap();
    sheet.set_value(CellRef::new(0, 0), CellValue::Text("Merged header that wraps across its cells".into()));
    sheet.set_style(CellRef::new(0, 0), style);
    sheet.merges.push(RangeRef::new(CellRef::new(0, 0), CellRef::new(1, 2)));
    sheet.rows.insert(1, LineInfo { hidden: true, ..Default::default() });
    sheet.rows.insert(3, LineInfo { size: Some(42.0), ..Default::default() });
    sheet.cols.insert(1, LineInfo { hidden: true, ..Default::default() });
    sheet.cols.insert(2, LineInfo { size: Some(120.0), ..Default::default() });
    for row in 2..6 {
        sheet.set_value(CellRef::new(row, 0), CellValue::Number((row * 3) as f64));
        sheet.set_value(CellRef::new(row, 2), CellValue::Text(format!("Group{row}").into()));
    }
    sheet.set_value(CellRef::new(12_000, 51), CellValue::Text("FAR_END".into()));
    sheet.charts.push(Chart {
        id: 1, kind: ChartKind::ColumnClustered,
        anchor: Anchor { cell: CellRef::new(8, 0), dx: 0.0, dy: 0.0, width: 400.0, height: 200.0 },
        title: Some("Saved-value chart".into()), series: vec![Series { name: Some("Example".into()),
            categories: Some("Sheet1!$C$3:$C$6".into()), values: "Sheet1!$A$3:$A$6".into(),
            bubble_sizes: None, color: Some(Color::Rgb(0x2255cc)), secondary: false, kind: None }],
        legend: LegendPos::Bottom, data_labels: true, gridlines: true, style: 0,
        x_title: None, y_title: None, source: None, by_rows: false, show_hidden_data: false,
    });
    let mut encoded = Cursor::new(Vec::new());
    image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(16, 16, image::Rgb([230, 45, 20])))
        .write_to(&mut encoded, image::ImageFormat::Png).unwrap();
    sheet.images.push(Image { id: 2, anchor: Anchor { cell: CellRef::new(20, 5), dx: 4.0, dy: 3.0,
        width: 40.0, height: 30.0 }, data: encoded.into_inner(), mime: "image/png".into(), alt: "Color swatch".into() });
    sheet.print.gridlines = false;
    sheet.show_gridlines = true;
    workbook
}

#[test]
fn metadata_is_sparse_blank_space_and_preserves_hidden_geometry() {
    let mut reader = WorkbookReader::from_workbook(workbook(), String::new()).unwrap();
    let info = reader.info();
    assert_eq!(info["sheets"][0]["rowCount"], ROWS);
    assert_eq!(info["sheets"][0]["columnCount"], COLUMNS);
    assert_eq!(info["sheets"][0]["usedRows"], 12_001);
    assert_eq!(info["sheets"][0]["usedColumns"], 52);
    let sheet = reader.sheet(0).unwrap();
    let rows = sheet["rowEdges"].as_array().unwrap();
    let columns = sheet["columnEdges"].as_array().unwrap();
    assert_eq!(rows.len(), ROWS as usize + 1);
    assert_eq!(columns.len(), COLUMNS as usize + 1);
    assert_eq!(rows[1], rows[2]);
    assert_eq!(columns[1], columns[2]);
    assert_eq!(rows[4].as_f64().unwrap() - rows[3].as_f64().unwrap(), 42.0);
    assert_eq!(columns[3].as_f64().unwrap() - columns[2].as_f64().unwrap(), 120.0);
    assert!(sheet["width"].as_f64().unwrap() > columns[52].as_f64().unwrap());
}

#[test]
fn viewport_reuses_workbook_and_renders_charts_images_and_far_cells() {
    let mut reader = WorkbookReader::from_workbook(workbook(), String::new()).unwrap();
    let sheet = reader.sheet(0).unwrap();
    let row = |i: usize| sheet["rowEdges"][i].as_f64().unwrap();
    let column = |i: usize| sheet["columnEdges"][i].as_f64().unwrap();
    let pointer = &reader.workbook as *const Workbook;
    let (_, header) = reader.render(0, 0.0, 0.0, 1.0, 400, 140).unwrap();
    let (_, chart) = reader.render(0, 0.0, row(8), 1.0, 400, 200).unwrap();
    let chart = image::load_from_memory(&chart).unwrap().to_rgb8();
    assert!(chart.pixels().filter(|p| p[2] > p[0].saturating_add(50) && p[2] > p[1].saturating_add(30)).count() > 500);
    let (receipt, picture) = reader.render(0, column(5), row(20), 2.0, 200, 100).unwrap();
    assert_eq!(receipt["rasterRegions"].as_array().unwrap().len(), 1);
    let image = image::load_from_memory(&picture).unwrap().to_rgb8();
    assert_eq!(image.get_pixel(20, 20).0, [230, 45, 20]);
    let bounds = &receipt["rasterRegions"][0];
    assert_eq!(bounds[0].as_f64().unwrap(), 0.04f32 as f64);
    assert_eq!(bounds[1].as_f64().unwrap(), 0.06f32 as f64);
    let (_, far) = reader.render(0, column(51), row(12_000), 2.0, 256, 100).unwrap();
    let far = image::load_from_memory(&far).unwrap().to_rgb8();
    assert!(far.pixels().filter(|p| p.0.iter().all(|c| *c < 100)).count() > 30);
    let (_, header_again) = reader.render(0, 0.0, 0.0, 1.0, 400, 140).unwrap();
    assert_eq!(header, header_again);
    assert_eq!(&reader.workbook as *const Workbook, pointer);
    assert!(reader.render(0, f64::NAN, 0.0, 1.0, 100, 100).is_err());
    assert!(reader.render(0, 0.0, 0.0, 33.0, 100, 100).is_err());
    assert!(reader.render(0, 0.0, 0.0, 1.0, 2801, 100).is_err());
}

#[test]
fn protocol_is_framed_and_commands_are_bounded() {
    let mut bytes = Vec::new();
    frame(&mut bytes, 2, 7, json!({"index":3}), &[]).unwrap();
    assert_eq!(&bytes[..4], b"ZSG1");
    assert_eq!(u32::from_le_bytes(bytes[4..8].try_into().unwrap()), 2);
    assert_eq!(u32::from_le_bytes(bytes[8..12].try_into().unwrap()), 7);
    assert_eq!(u32::from_le_bytes(bytes[16..20].try_into().unwrap()), 0);
    assert_eq!(serde_json::from_slice::<Value>(&bytes[20..]).unwrap()["index"], 3);
    let mut input = Cursor::new(b"{\"id\":1,\"op\":\"sheet\",\"sheet\":0}\n");
    assert_eq!(command(&mut input).unwrap().unwrap()["id"], 1);
    assert!(command(&mut input).unwrap().is_none());
    assert!(command(&mut Cursor::new(vec![b'a'; COMMAND_LIMIT + 1])).is_err());
    assert!(command(&mut Cursor::new(b"{\"id\":1}")).is_err());
    assert!(integer(&json!({"sheet":-1}), "sheet").is_err());
    assert!(integer(&json!({"sheet":1.5}), "sheet").is_err());
}

#[test]
#[ignore = "requires the root-owned pinned 108k-cell workbook and rendered review"]
fn stress_pinned_workbook_first_chart_and_far_sheet() {
    let path = std::env::var("ALLDOC_GRID_FIXTURE").expect("Set pinned workbook fixture path");
    let bytes = std::fs::read(path).unwrap();
    let mut reader = WorkbookReader::open(&bytes).unwrap();
    assert!(reader.workbook.sheets[0].cells.len() >= 100_000);
    assert!(reader.workbook.sheets[0].charts.len() >= 2);
    let (_, first) = reader.render(0, 0.0, 0.0, 1.0, 1080, 1600).unwrap();
    assert!(!first.is_empty());
    let sheet = reader.sheet(1).unwrap();
    let x = sheet["columnEdges"][51].as_f64().unwrap();
    let y = sheet["rowEdges"][11_999].as_f64().unwrap();
    let (_, far) = reader.render(1, x, y, 2.0, 1080, 1600).unwrap();
    assert!(!far.is_empty());
}

#[test]
fn horizontal_pan_keeps_text_overflow_from_right_neighbour() {
    for alignment in [gridcraft_model::HAlign::Right, gridcraft_model::HAlign::Center] {
        let mut workbook = Workbook::new();
        let mut style = Style::default();
        style.align.h = alignment;
        style.font.size = 18.0;
        let style = workbook.styles.intern(style);
        let sheet = workbook.sheet_mut(0).unwrap();
        sheet.default_col_width = 64.0;
        sheet.default_row_height = 36.0;
        let cell = CellRef::new(0, 5);
        sheet.set_value(cell, CellValue::Text("RIGHT NEIGHBOUR OVERFLOWS INTO THIS VIEWPORT".into()));
        sheet.set_style(cell, style);
        let mut reader = WorkbookReader::from_workbook(workbook, String::new()).unwrap();
        let (_, full) = reader.render(0, 0.0, 0.0, 1.0, 512, 64).unwrap();
        let (_, pan) = reader.render(0, 128.0, 0.0, 1.0, 128, 64).unwrap();
        let full = image::load_from_memory(&full).unwrap().to_rgb8();
        let pan = image::load_from_memory(&pan).unwrap().to_rgb8();
        assert!(pan.pixels().filter(|p| p.0.iter().all(|c| *c < 100)).count() > 20);
        // Floating-point text positioning can round raster coverage by one 8-bit
        // level after translation. Missing or displaced overflow ink still differs greatly.
        let mut largest_error = 0;
        for y in 0..64 {
            for x in 0..128 {
                let expected = full.get_pixel(x + 128, y);
                let actual = pan.get_pixel(x, y);
                for channel in 0..3 {
                    largest_error = largest_error.max(actual[channel].abs_diff(expected[channel]));
                }
            }
        }
        assert!(largest_error <= 1, "{alignment:?} overflow crop changed by {largest_error} channel levels");
    }
}

fn formatted_sparse_package(cell: &str) -> Vec<u8> {
    let worksheet = format!(r#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="16384" width="12" customWidth="1" style="1"/></cols><sheetData><row r="1"><c r="{cell}" t="inlineStr"><is><t>One cell</t></is></c></row><row r="1048576" s="1" customFormat="1"/></sheetData></worksheet>"#);
    let files = [
        ("[Content_Types].xml", r#"<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"#),
        ("_rels/.rels", r#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"#),
        ("xl/workbook.xml", r#"<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"#),
        ("xl/_rels/workbook.xml.rels", r#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"#),
        ("xl/styles.xml", r#"<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font/><font><b/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" applyFont="1"/></cellXfs></styleSheet>"#),
        ("xl/worksheets/sheet1.xml", worksheet.as_str()),
    ];
    let mut package = zip::ZipWriter::new(Cursor::new(Vec::new()));
    for (name, xml) in files {
        package.start_file(name, zip::write::SimpleFileOptions::default()).unwrap();
        package.write_all(xml.as_bytes()).unwrap();
    }
    package.finish().unwrap().into_inner()
}

#[test]
fn whole_grid_formatting_does_not_expand_or_reject_sparse_content() {
    let mut reader = WorkbookReader::open(&formatted_sparse_package("A1")).unwrap();
    let sheet = &reader.workbook.sheets[0];
    assert_eq!(sheet.cells.len(), 1);
    assert_eq!(sheet.cols.len(), COLUMNS as usize);
    assert!(sheet.rows.keys().all(|index| *index < ROWS));
    let style = sheet.cols[&0].style.unwrap();
    assert!(reader.workbook.styles.get(style).font.bold);
    assert_eq!(reader.info()["sheets"][0]["usedRows"], 1);
    let geometry = reader.sheet(0).unwrap();
    assert_eq!(geometry["columnEdges"].as_array().unwrap().len(), COLUMNS as usize + 1);
    assert!(WorkbookReader::open(&formatted_sparse_package("A16385")).is_err());
    assert!(WorkbookReader::open(&formatted_sparse_package("SS1")).is_err());
}

#[test]
fn chart_raster_uses_saved_formula_value() {
    let mut plain = workbook();
    plain.sheet_mut(0).unwrap().set_value(CellRef::new(2, 0), CellValue::Number(19.0));
    let mut formula = workbook();
    let mut cell = gridcraft_model::Cell::formula(gridcraft_model::Formula::new("1+1"));
    cell.value = CellValue::Number(19.0);
    formula.sheet_mut(0).unwrap().cells.set(CellRef::new(2, 0), cell);
    let mut plain = WorkbookReader::from_workbook(plain, String::new()).unwrap();
    let mut formula = WorkbookReader::from_workbook(formula, String::new()).unwrap();
    let top = plain.sheet(0).unwrap()["rowEdges"][8].as_f64().unwrap();
    let baseline = plain.render(0, 0.0, top, 1.0, 400, 200).unwrap().1;
    let cached = formula.render(0, 0.0, top, 1.0, 400, 200).unwrap().1;
    let image = image::load_from_memory(&baseline).unwrap().to_rgb8();
    assert!(image.pixels().filter(|p| p[2] > p[0].saturating_add(50) && p[2] > p[1].saturating_add(30)).count() > 500,
        "Chart must contain the authored blue data marks");
    assert!(image.pixels().filter(|p| p.0.iter().all(|c| *c < 100)).count() > 20,
        "Chart must contain dark labels or axis text");
    assert_eq!(baseline, cached);
    let mut recalculated = workbook();
    recalculated.sheet_mut(0).unwrap().set_value(CellRef::new(2, 0), CellValue::Number(2.0));
    let mut recalculated = WorkbookReader::from_workbook(recalculated, String::new()).unwrap();
    assert_ne!(baseline, recalculated.render(0, 0.0, top, 1.0, 400, 200).unwrap().1,
        "Fixture must distinguish the cached value 19 from a recomputed 1+1 result");
}

#[test]
fn conditional_formats_use_sparse_saved_values() {
    use gridcraft_engine::cf::CfCache;
    use gridcraft_model::{CfOperator, CfRule, CfValueKind, CondFormat};
    let mut workbook = Workbook::new();
    let first = CellRef::new(0, 0);
    let far = CellRef::new(12_000, 500);
    let sheet = workbook.sheet_mut(0).unwrap();
    sheet.set_value(first, CellValue::Number(1.0));
    sheet.set_value(far, CellValue::Number(9.0));
    sheet.cond_formats.push(CondFormat {
        ranges: vec![RangeRef::new(first, CellRef::new(16_383, 511))],
        rule: CfRule::ColorScale { stops: vec![(CfValueKind::Min, Color::Rgb(0xff0000)),
            (CfValueKind::Max, Color::Rgb(0x0000ff))] }, priority: 0, stop_if_true: false,
    });
    let mut cache = CfCache::new();
    for _ in 0..16 {
        assert_eq!(cache.look(&workbook, 0, far).unwrap().scale_fill, Some([0, 0, 255]));
        assert_eq!(cache.look(&workbook, 0, first).unwrap().scale_fill, Some([255, 0, 0]));
    }
    let mut style = Style::default(); style.font.bold = true;
    workbook.sheet_mut(0).unwrap().cond_formats[0] = CondFormat { ranges: vec![RangeRef::cell(far)],
        rule: CfRule::CellIs { op: CfOperator::Greater, a: "3".into(), b: None, style: Box::new(style) },
        priority: 0, stop_if_true: false };
    let mut cache = CfCache::new();
    assert!(cache.look(&workbook, 0, far).unwrap().style.unwrap().font.bold);
    workbook.sheet_mut(0).unwrap().cond_formats[0].rule = CfRule::Expression {
        formula: "1=1".into(), style: Box::new(Style::default()) };
    assert!(cache.look(&workbook, 0, far).is_none());
}

#[test]
fn duplicate_rules_keep_case_insensitive_counts_without_numeric_statistics() {
    use gridcraft_engine::cf::CfCache;
    use gridcraft_model::{CfRule, CondFormat};
    let mut workbook = Workbook::new();
    let sheet = workbook.sheet_mut(0).unwrap();
    sheet.set_value(CellRef::new(0, 0), CellValue::Text("Same".into()));
    sheet.set_value(CellRef::new(1, 0), CellValue::Text("same".into()));
    sheet.set_value(CellRef::new(2, 0), CellValue::Text("Different".into()));
    let mut style = Style::default(); style.font.bold = true;
    sheet.cond_formats.push(CondFormat {
        ranges: vec![RangeRef::new(CellRef::new(0, 0), CellRef::new(2, 0))],
        rule: CfRule::Duplicate { style: Box::new(style.clone()) }, priority: 0, stop_if_true: false,
    });
    let mut cache = CfCache::new();
    assert!(cache.look(&workbook, 0, CellRef::new(0, 0)).unwrap().style.unwrap().font.bold);
    assert!(cache.look(&workbook, 0, CellRef::new(1, 0)).unwrap().style.unwrap().font.bold);
    assert!(cache.look(&workbook, 0, CellRef::new(2, 0)).is_none());
    workbook.sheet_mut(0).unwrap().cond_formats[0].rule = CfRule::Unique { style: Box::new(style) };
    let mut cache = CfCache::new();
    assert!(cache.look(&workbook, 0, CellRef::new(0, 0)).is_none());
    assert!(cache.look(&workbook, 0, CellRef::new(2, 0)).unwrap().style.unwrap().font.bold);
}

#[test]
fn overlapping_conditional_ranges_count_each_cell_once() {
    use gridcraft_engine::cf::CfCache;
    use gridcraft_model::{CfRule, CfValueKind, CondFormat};
    let mut workbook = Workbook::new();
    let sheet = workbook.sheet_mut(0).unwrap();
    for (column, value) in [1.0, 5.0, 9.0].into_iter().enumerate() {
        sheet.set_value(CellRef::new(0, column as u32), CellValue::Number(value));
    }
    let mut style = Style::default(); style.font.bold = true;
    sheet.cond_formats.push(CondFormat {
        ranges: vec![RangeRef::new(CellRef::new(0, 0), CellRef::new(0, 1)),
            RangeRef::new(CellRef::new(0, 1), CellRef::new(0, 2)),
            RangeRef::cell(CellRef::new(0, 0)), RangeRef::cell(CellRef::new(0, 0))],
        rule: CfRule::Duplicate { style: Box::new(style) }, priority: 0, stop_if_true: false,
    });
    let mut cache = CfCache::new();
    for column in 0..3 { assert!(cache.look(&workbook, 0, CellRef::new(0, column)).is_none()); }
    workbook.sheet_mut(0).unwrap().cond_formats[0].rule = CfRule::ColorScale { stops: vec![
        (CfValueKind::Min, Color::Rgb(0xff0000)),
        (CfValueKind::Percentile(50.0), Color::Rgb(0x00ff00)),
        (CfValueKind::Max, Color::Rgb(0x0000ff)),
    ] };
    let mut cache = CfCache::new();
    assert_eq!(cache.look(&workbook, 0, CellRef::new(0, 1)).unwrap().scale_fill, Some([0, 255, 0]));
}

#[test]
fn fragmented_conditional_ranges_match_their_union() {
    use gridcraft_engine::cf::CfCache;
    use gridcraft_model::{CfRule, CfValueKind, CondFormat};
    let mut workbook = Workbook::new();
    let sheet = workbook.sheet_mut(0).unwrap();
    for column in 0..128 { sheet.set_value(CellRef::new(0, column), CellValue::Number(column as f64 + 1.0)); }
    sheet.cond_formats.push(CondFormat {
        ranges: vec![RangeRef::new(CellRef::new(0, 0), CellRef::new(0, 127))],
        rule: CfRule::ColorScale { stops: vec![(CfValueKind::Min, Color::Rgb(0xff0000)),
            (CfValueKind::Percentile(50.0), Color::Rgb(0x00ff00)), (CfValueKind::Max, Color::Rgb(0x0000ff))] },
        priority: 0, stop_if_true: false,
    });
    let columns = [0, 31, 63, 95, 127];
    let mut cache = CfCache::new();
    let expected: Vec<_> = columns.iter().map(|column| cache.look(&workbook, 0, CellRef::new(0, *column)).unwrap().scale_fill).collect();
    let mut fragments: Vec<_> = (0..128).map(|column| RangeRef::cell(CellRef::new(0, column))).collect();
    for _ in 0..4 { fragments.extend((0..64).map(|column| RangeRef::cell(CellRef::new(0, column)))); }
    workbook.sheet_mut(0).unwrap().cond_formats[0].ranges = fragments;
    let mut cache = CfCache::new();
    for (column, expected) in columns.iter().zip(expected) {
        assert_eq!(cache.look(&workbook, 0, CellRef::new(0, *column)).unwrap().scale_fill, expected);
    }
    let mut style = Style::default(); style.font.bold = true;
    workbook.sheet_mut(0).unwrap().cond_formats[0].rule = CfRule::Duplicate { style: Box::new(style) };
    let mut cache = CfCache::new();
    for column in columns { assert!(cache.look(&workbook, 0, CellRef::new(0, column)).is_none()); }
}

#[test]
fn xlsx_chart_hidden_sources_roundtrip_and_do_not_affect_pixels() {
    let mut source = workbook();
    let sheet = source.sheet_mut(0).unwrap();
    sheet.rows.get_mut(&3).unwrap().hidden = true;
    let mut hidden = sheet.charts[0].series[0].clone();
    hidden.name = Some("Hidden series".into());
    hidden.values = "Sheet1!B3:B6".into();
    hidden.color = Some(Color::Rgb(0xcc2222));
    sheet.charts[0].series.push(hidden);
    sheet.set_value(CellRef::new(2, 1), CellValue::Number(1_000.0));
    let render = |wb: &Workbook| {
        let encoded = gridcraft_xlsx::write_xlsx(wb).unwrap();
        let (decoded, _) = gridcraft_xlsx::read_xlsx(&encoded).unwrap();
        assert_eq!(decoded.sheets[0].charts[0].show_hidden_data, wb.sheets[0].charts[0].show_hidden_data);
        let mut reader = WorkbookReader::from_workbook(decoded, String::new()).unwrap();
        let top = reader.sheet(0).unwrap()["rowEdges"][8].as_f64().unwrap();
        reader.render(0, 0.0, top, 1.0, 400, 200).unwrap().1
    };
    let baseline = render(&source);
    source.sheet_mut(0).unwrap().set_value(CellRef::new(3, 0), CellValue::Number(7_000.0));
    source.sheet_mut(0).unwrap().set_value(CellRef::new(2, 1), CellValue::Number(9_000.0));
    assert_eq!(render(&source), baseline, "Hidden row and hidden series must not change chart pixels");
    source.sheet_mut(0).unwrap().charts[0].show_hidden_data = true;
    assert_ne!(render(&source), baseline, "plotVisOnly=false must retain authored hidden data");
}
