use super::*;
use std::io::Write;
use zip::write::SimpleFileOptions;

// Extend the repository's authored Office fixture; no downloaded document/media.
const BASE: &[u8] = include_bytes!("../../../../app/src/androidTest/assets/office/sample.pptx");
const AUDIO: &[u8] = b"RIFF\x2c\0\0\0WAVEfmt \x10\0\0\0\x01\0\x01\0\x40\x1f\0\0\x40\x1f\0\0\x01\0\x08\0data\x08\0\0\0\x80\x80\x80\x80\x80\x80\x80\x80";
const PICTURE: &str = r#"<p:pic><p:nvPicPr><p:cNvPr id="50" name="Short audio clip"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr><a:audioFile r:link="rIdClip"/></p:nvPr></p:nvPicPr><p:blipFill><a:blip r:embed="rIdPoster"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="914400" y="3657600"/><a:ext cx="914400" cy="914400"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"#;

fn picture(width: u32) -> Vec<u8> {
    let mut data = Cursor::new(Vec::new());
    image::RgbaImage::from_pixel(width, 1, image::Rgba([217, 17, 191, 255]))
        .write_to(&mut data, image::ImageFormat::Png)
        .unwrap();
    data.into_inner()
}

fn opaque_picture_bytes() -> Vec<u8> {
    let mut data = Cursor::new(Vec::new());
    image::RgbImage::from_pixel(1, 1, image::Rgb([217, 17, 191]))
        .write_to(&mut data, image::ImageFormat::Png).unwrap();
    data.into_inner()
}

fn presentation(audio: &[u8], poster: &[u8]) -> Vec<u8> {
    let mut source = zip::ZipArchive::new(Cursor::new(BASE)).unwrap();
    let mut output = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for i in 0..source.len() {
        let mut part = source.by_index(i).unwrap();
        let name = part.name().to_owned();
        let mut data = Vec::new();
        part.read_to_end(&mut data).unwrap();
        let insertion = match name.as_str() {
            "ppt/slides/slide1.xml" => Some(("</p:spTree>", PICTURE)),
            "ppt/slides/_rels/slide1.xml.rels" => Some((
                "</Relationships>",
                r#"<Relationship Id="rIdClip" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/audio" Target="../media/clip.wav"/><Relationship Id="rIdPoster" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/poster.png"/>"#,
            )),
            "[Content_Types].xml" => Some((
                "</Types>",
                r#"<Override PartName="/ppt/media/clip.wav" ContentType="audio/wav"/><Override PartName="/ppt/media/poster.png" ContentType="image/png"/>"#,
            )),
            _ => None,
        };
        if let Some((end, text)) = insertion {
            let xml = String::from_utf8(data).unwrap();
            assert_eq!(xml.matches(end).count(), 1);
            data = xml.replace(end, &format!("{text}{end}")).into_bytes();
        }
        output.start_file(name, options).unwrap();
        output.write_all(&data).unwrap();
    }
    for (name, data) in [
        ("ppt/media/clip.wav", audio),
        ("ppt/media/poster.png", poster),
    ] {
        output.start_file(name, options).unwrap();
        output.write_all(data).unwrap();
    }
    output.finish().unwrap().into_inner()
}

#[test]
fn pptx_clip_keeps_static_slide_and_poster() {
    let input = presentation(AUDIO, &opaque_picture_bytes());
    let page = render(&input, 2, 0, 320, || false).unwrap();
    assert_eq!((page.format, page.page, page.pages), (1, 0, 2));
    let pixels = image::load_from_memory(&page.data).unwrap().into_rgba8();
    assert_eq!(pixels.dimensions(), (page.width, page.height));
    assert!(
        pixels.pixels().any(|p| p.0 == [217, 17, 191, 255]),
        "clip poster must render"
    );
    assert!(page.note.contains("playback/animation not rendered"));
    let regions = page.raster_regions.as_ref().expect("axis-aligned poster has complete metadata");
    assert!(!regions.is_empty());
    for (x, y, pixel) in pixels.enumerate_pixels().filter(|(_, _, p)| p.0 == [217, 17, 191, 255]) {
        let _ = pixel;
        let px = (x as f32 + 0.5) / page.width as f32;
        let py = (y as f32 + 0.5) / page.height as f32;
        assert!(regions.iter().any(|r| px >= r[0] && px <= r[2] && py >= r[1] && py <= r[3]), "poster pixel outside source enclosure");
    }
}

#[test]
fn rotated_slide_picture_has_unknown_regions_instead_of_incomplete_enclosures() {
    let input = presentation(AUDIO, &opaque_picture_bytes());
    let rotated = rewrite_parts(&input, |name, data| {
        if name != "ppt/slides/slide1.xml" { return data; }
        let xml = String::from_utf8(data).unwrap();
        assert!(xml.contains(PICTURE));
        xml.replace(PICTURE, &PICTURE.replace("<a:xfrm>", "<a:xfrm rot=\"1800000\">" )).into_bytes()
    });
    let page = render(&rotated, 2, 0, 320, || false).unwrap();
    assert!(page.raster_regions.is_none());
    assert!(image::load_from_memory(&page.data).unwrap().into_rgba8().pixels().any(|p| p.0 == [217, 17, 191, 255]));
    let rounded = rewrite_parts(&input, |name, data| {
        if name != "ppt/slides/slide1.xml" { return data; }
        let picture = PICTURE.replace("prst=\"rect\"", "prst=\"roundRect\"")
            .replace(r#"<a:audioFile r:link="rIdClip"/>"#, "");
        String::from_utf8(data).unwrap().replace(PICTURE, &picture).into_bytes()
    });
    let parsed = deckcraft_pptx::import(&rounded).unwrap();
    assert!(parsed.slides[0].shapes.iter().any(|s| matches!(&s.kind, deckcraft_model::ShapeKind::Picture { .. })
        && s.geom.preset_name() == Some("roundRect")), "control must exercise actual picture clipping");
    assert!(render(&rounded, 2, 0, 320, || false).unwrap().raster_regions.is_none());
    // RGBA permits transparency even when this tiny image happens to have opaque pixels.
    assert!(render(&presentation(AUDIO, &picture(1)), 2, 0, 320, || false).unwrap().raster_regions.is_none());
}

#[test]
fn clip_name_does_not_bypass_image_or_poster_limits() {
    let small = picture(1);
    let wide = picture(16_385); // 65 KiB decoded, just outside the existing edge bound.
    for (audio, poster) in [(&wide[..], &small[..]), (AUDIO, &wide[..])] {
        let input = presentation(audio, poster);
        assert_eq!(
            package(&input, 2, &mut || Ok(())).unwrap_err(),
            "Image dimensions exceed the bounded visual reader"
        );
    }
    let input = presentation(b"ordinary non-media text", &small);
    assert_eq!(
        package(&input, 2, &mut || Ok(())).unwrap_err(),
        "Unsupported embedded image encoding"
    );
}

#[test]
fn clip_recognition_does_not_claim_riff_or_bmff_images() {
    assert!(is_clip(AUDIO));
    assert!(is_clip(b"\x00\x00\x00\x18ftypmp42\0\0\0\0mp42isom"));
    for data in [
        &b"RIFF\0\0\0\0WEBP"[..],
        &b"\x00\x00\x00\x18ftypavif\0\0\0\0avifmif1"[..],
        &b"\x00\x00\x00\x18ftypheic\0\0\0\0heicmif1"[..],
        &b"\xff\xd8\xff\xe0"[..],
    ] {
        assert!(!is_clip(data));
    }
}

fn rewrite_parts(base: &[u8], mut edit: impl FnMut(&str, Vec<u8>) -> Vec<u8>) -> Vec<u8> {
    let mut source = zip::ZipArchive::new(Cursor::new(base)).unwrap();
    let mut output = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for i in 0..source.len() {
        let mut part = source.by_index(i).unwrap();
        let name = part.name().to_owned();
        let mut data = Vec::new();
        part.read_to_end(&mut data).unwrap();
        output.start_file(&name, options).unwrap();
        output.write_all(&edit(&name, data)).unwrap();
    }
    output.finish().unwrap().into_inner()
}

fn repeated_presentation(count: usize) -> Vec<u8> {
    rewrite_parts(BASE, |name, data| match name {
        "ppt/presentation.xml" => {
            let mut xml = String::from_utf8(data).unwrap();
            let start = xml.find("<p:sldIdLst>").unwrap() + "<p:sldIdLst>".len();
            let end = xml.find("</p:sldIdLst>").unwrap();
            let ids: String = (0..count)
                .map(|i| format!(r#"<p:sldId id="{}" r:id="rIdStress{i}"/>"#, 10000 + i))
                .collect();
            xml.replace_range(start..end, &ids);
            xml.into_bytes()
        }
        "ppt/_rels/presentation.xml.rels" => {
            let links: String = (0..count).map(|i| format!(r#"<Relationship Id="rIdStress{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide{}.xml"/>"#, 1 + i % 2)).collect();
            String::from_utf8(data)
                .unwrap()
                .replace("</Relationships>", &format!("{links}</Relationships>"))
                .into_bytes()
        }
        _ => data,
    })
}

#[test]
fn pptx_pages_above128_render_and_page_overflow_is_rejected() {
    let input = repeated_presentation(240);
    let mut first = Vec::new();
    for index in [0, 119, 239] {
        let page = render(&input, 2, index, 128, || false).unwrap();
        assert_eq!((page.format, page.page, page.pages), (1, index, 240));
        assert!(page.width <= 128 && page.height <= 128);
        let pixels = image::load_from_memory(&page.data).unwrap().into_rgba8();
        assert_eq!(pixels.dimensions(), (page.width, page.height));
        assert!(pixels.pixels().any(|p| p.0[..3].iter().any(|c| *c < 200)));
        if index == 0 {
            first = page.data;
        } else {
            assert_ne!(
                first, page.data,
                "the selected authored slide must be rendered"
            );
        }
    }
    assert_eq!(
        render(&repeated_presentation(1025), 2, 0, 128, || false)
            .err()
            .unwrap(),
        "PPTX slide limit or empty presentation"
    );
}


// Ordinary separately serialized slides, with text, fill and line properties.
// Reuse the owned template/media/layout rather than adding a binary test asset.
fn dense_presentation(slides: usize) -> Vec<u8> {
    let seed = repeated_presentation(slides);
    let mut source = zip::ZipArchive::new(Cursor::new(&seed)).unwrap();
    let mut slide = String::new();
    source.by_name("ppt/slides/slide1.xml").unwrap().read_to_string(&mut slide).unwrap();
    let mut relations = Vec::new();
    source.by_name("ppt/slides/_rels/slide1.xml.rels").unwrap().read_to_end(&mut relations).unwrap();
    let shapes: String = (0..32).map(|i| format!(r#"<p:sp><p:nvSpPr><p:cNvPr id="{}" name="Ordinary annotated panel {i}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="914400" y="914400"/><a:ext cx="1828800" cy="914400"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="E4EDF4"/></a:solidFill><a:ln><a:solidFill><a:srgbClr val="334455"/></a:solidFill></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr/><a:r><a:rPr lang="en-US" sz="1200"><a:solidFill><a:srgbClr val="182838"/></a:solidFill><a:latin typeface="Liberation Sans"/></a:rPr><a:t>Ordinary checked values {i}</a:t></a:r><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>"#, 1000 + i)).collect();
    slide = slide.replace("</p:spTree>", &format!("{shapes}</p:spTree>"));
    let mut output = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for i in 0..source.len() {
        let mut part = source.by_index(i).unwrap();
        let name = part.name().to_owned();
        if name.starts_with("ppt/slides/") { continue; }
        let mut data = Vec::new(); part.read_to_end(&mut data).unwrap();
        if name == "ppt/_rels/presentation.xml.rels" {
            let mut xml = String::from_utf8(data).unwrap();
            for n in 0..slides {
                let old = format!(r#"<Relationship Id="rIdStress{n}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide{}.xml"/>"#, 1 + n % 2);
                let new = old.replace(&format!("slide{}.xml", 1 + n % 2), &format!("slide{}.xml", n + 1));
                assert!(xml.contains(&old)); xml = xml.replace(&old, &new);
            }
            data = xml.into_bytes();
        } else if name == "[Content_Types].xml" {
            let types: String = (3..=slides).map(|n| format!(r#"<Override PartName="/ppt/slides/slide{n}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"#)).collect();
            data = String::from_utf8(data).unwrap().replace("</Types>", &format!("{types}</Types>")).into_bytes();
        }
        output.start_file(name, options).unwrap(); output.write_all(&data).unwrap();
    }
    for n in 1..=slides {
        output.start_file(format!("ppt/slides/slide{n}.xml"), options).unwrap(); output.write_all(slide.as_bytes()).unwrap();
        output.start_file(format!("ppt/slides/_rels/slide{n}.xml.rels"), options).unwrap(); output.write_all(&relations).unwrap();
    }
    output.finish().unwrap().into_inner()
}

#[test]
fn ordinary_dense_slides_have_a_separate_bounded_structure_budget() {
    let input = dense_presentation(96);
    package(&input, 2, &mut || Ok(())).unwrap();
    let parsed = deckcraft_pptx::import(&input).unwrap();
    assert_eq!(parsed.slides.len(), 96);
    assert!(parsed.slides.iter().all(|slide| slide.shapes.len() >= 32));
    drop(parsed);
    for kind in [1, 3] {
        assert_eq!(package(&input, kind, &mut || Ok(())).unwrap_err(),
            "Document structure exceeds visual reader budget");
    }
    package(&dense_presentation(160), 2, &mut || Ok(())).unwrap();
    assert_eq!(package(&dense_presentation(320), 2, &mut || Ok(())).unwrap_err(),
        "Document structure exceeds visual reader budget");
    let mut checks = 0;
    assert_eq!(package(&input, 2, &mut || { checks += 1; if checks > 1024 { Err("CANCELLED".into()) } else { Ok(()) } }).unwrap_err(), "CANCELLED");
}

#[test]
fn worksheet_page_overflow_is_not_silently_truncated() {
    let input = rewrite_parts(
        include_bytes!("../../../../app/src/androidTest/assets/office/sample.xlsx"),
        |name, data| {
            if name != "xl/worksheets/sheet1.xml" {
                return data;
            }
            let mut xml = String::from_utf8(data).unwrap();
            let start = xml.find("<sheetData>").unwrap() + "<sheetData>".len();
            let end = xml.find("</sheetData>").unwrap();
            let rows: String = (1..=1025)
                .map(|i| format!(r#"<row r="{i}"><c r="A{i}" t="n"><v>{i}</v></c></row>"#))
                .collect();
            xml.replace_range(start..end, &rows);
            xml = xml.replace("A1:B3", "A1:A1025");
            let breaks: String = (1..1025)
                .map(|i| format!(r#"<brk id="{i}" min="0" max="16383" man="1"/>"#))
                .collect();
            xml.replace("</worksheet>", &format!(r#"<rowBreaks count="1024" manualBreakCount="1024">{breaks}</rowBreaks></worksheet>"#)).into_bytes()
        },
    );
    let (workbook, _) = gridcraft_xlsx::read_xlsx(&input).unwrap();
    let layouts = gridcraft_engine::cmd::print::layouts(
        &workbook,
        &gridcraft_engine::cmd::print::PrintOptions {
            all_sheets: true,
            ..Default::default()
        },
    );
    assert_eq!(
        layouts
            .iter()
            .map(|layout| layout.pages.len())
            .sum::<usize>(),
        1025
    );
    assert_eq!(
        render(&input, 3, 0, 128, || false).err().unwrap(),
        "XLSX print page limit or empty workbook"
    );
}

#[test]
fn detail_tier_rasterizes_more_pixels_without_upscaling_the_base_image() {
    assert_eq!(fit(300.0, 200.0, 1400).unwrap(), (2.0, 600, 400));
    assert_eq!(fit(300.0, 200.0, 2800).unwrap(), (4.0, 1200, 800));
    for (input, kind) in [
        (
            &include_bytes!("../../../../app/src/androidTest/assets/office/sample.docx")[..],
            1,
        ),
        (BASE, 2),
        (
            &include_bytes!("../../../../app/src/androidTest/assets/office/sample.xlsx")[..],
            3,
        ),
    ] {
        let base = render(input, kind, 0, 1400, || false).unwrap();
        let detail = render(input, kind, 0, 2800, || false).unwrap();
        assert_eq!((detail.page, detail.pages), (base.page, base.pages));
        assert_eq!(base.raster_regions, Some(vec![]), "authored fixture contains no images");
        assert_eq!(detail.raster_regions, Some(vec![]));
        assert!(detail.width > base.width && detail.height > base.height);
        assert!(detail.width <= 2800 && detail.height <= 2800);
        assert!(u64::from(detail.width) * u64::from(detail.height) <= 8_000_000);
        let low = image::load_from_memory(&base.data).unwrap().into_rgba8();
        let high = image::load_from_memory(&detail.data).unwrap().into_rgba8();
        let enlarged = image::imageops::resize(
            &low,
            detail.width,
            detail.height,
            image::imageops::FilterType::Triangle,
        );
        assert_ne!(
            high.as_raw(),
            enlarged.as_raw(),
            "detail must be rasterized from the document"
        );
    }
    assert_eq!(
        render(BASE, 2, 0, 2801, || false).err().unwrap(),
        "Output side must be 128 through 2800"
    );
}

#[test]
fn ordinary_inferred_worksheet_coordinates_match_explicit_cells() {
    for (row, col) in [(1, 1), (16_384, 512)] {
        worksheet_extent(row, col).unwrap();
    }
    for (row, col) in [(0, 1), (1, 0), (16_385, 1), (1, 513)] {
        assert!(worksheet_extent(row, col).is_err());
    }
    let mut images = Vec::new();
    for rows in [
        r#"<row r="3"><c r="C3"><v>7</v></c><c><v>8</v></c></row><row><c><v>9</v></c></row>"#,
        r#"<row r="3"><c r="C3"><v>7</v></c><c r="D3"><v>8</v></c></row><row r="4"><c r="A4"><v>9</v></c></row>"#,
    ] {
        let input = rewrite_parts(
            include_bytes!("../../../../app/src/androidTest/assets/office/sample.xlsx"),
            |name, data| {
                if name != "xl/worksheets/sheet1.xml" {
                    return data;
                }
                let mut xml = String::from_utf8(data).unwrap();
                let start = xml.find("<sheetData>").unwrap() + "<sheetData>".len();
                let end = xml.find("</sheetData>").unwrap();
                xml.replace_range(start..end, rows);
                xml.replace("A1:B3", "A3:D4").into_bytes()
            },
        );
        let page = render(&input, 3, 0, 128, || false).unwrap();
        assert_eq!((page.format, page.page, page.pages), (1, 0, 1));
        images.push(page.data);
    }
    assert_eq!(images[0], images[1]);
}

// Admission fixtures use ordinary numeric cells; full first/middle/last raster
// qualification uses the separately pinned 10,000-by-12 authored workbook.
fn worksheet_budget_fixture(parts: &[usize], digits: &str) -> Vec<u8> {
    let mut output = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
    for (sheet, count) in parts.iter().copied().enumerate() {
        output.start_file(format!("xl/worksheets/sheet{}.xml", sheet + 1), options).unwrap();
        output.write_all(br#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>"#).unwrap();
        for row in 0..count.div_ceil(12) {
            write!(output, r#"<row r="{}">"#, row + 1).unwrap();
            for _ in 0..(count - row * 12).min(12) {
                write!(output, "<c><v>{digits}</v></c>").unwrap();
            }
            output.write_all(b"</row>").unwrap();
        }
        output.write_all(b"</sheetData></worksheet>").unwrap();
    }
    output.finish().unwrap().into_inner()
}

#[test]
fn worksheet_admission_budget_is_shared_across_the_workbook() {
    let exact = worksheet_budget_fixture(&[65_536, 65_536], "1234567890");
    package(&exact, 3, &mut || Ok(())).unwrap();
    let over = worksheet_budget_fixture(&[65_536, 65_537], "1234567890");
    assert_eq!(package(&over, 3, &mut || Ok(())).unwrap_err(),
        "Workbook cell count exceeds visual reader budget");
    let text_over = worksheet_budget_fixture(&[65_536, 65_536], "12345678901234567");
    assert_eq!(package(&text_over, 3, &mut || Ok(())).unwrap_err(),
        "Document text exceeds visual reader budget");
}

#[test]
fn larger_worksheet_budget_does_not_expand_other_office_formats() {
    let input = worksheet_budget_fixture(&[120_012], "1234567890");
    package(&input, 3, &mut || Ok(())).unwrap();
    for kind in [1, 2] {
        assert_eq!(package(&input, kind, &mut || Ok(())).unwrap_err(),
            "XML part exceeds visual reader budget");
    }
    assert_eq!(package(&input, 3, &mut || Err("CANCELLED".into())).unwrap_err(), "CANCELLED");
}

#[test]
fn opaque_slide_paper_covers_fractional_output_edge_without_flattening_transparent_slides() {
    let mut pres = deckcraft_pptx::import(BASE).unwrap();
    let (scale, w, h) = fit(pres.slide_size.width, pres.slide_size.height, 317).unwrap();
    assert!((pres.slide_size.height * scale).fract() > 0.01, "exercise a fractional final row");
    let page = render(BASE, 2, 0, 317, || false).unwrap();
    let image = image::load_from_memory(&page.data).unwrap().into_rgba8();
    assert!(image.pixels().all(|p| p[3] == 255), "opaque paper must cover the output edge");
    std::sync::Arc::make_mut(&mut pres.slides[0]).background = Some(deckcraft_model::Background::Fill { fill: deckcraft_model::style::Fill::None });
    let mut opts = deckcraft_render::RenderOpts { scale, size: Some((w, h)), threads: 0, ..Default::default() };
    clear_opaque_slide_paper(&pres, 0, &mut opts);
    assert!(opts.clear.is_none());
    let transparent = deckcraft_render::render_slide(&pres, 0, &opts);
    assert!(transparent.pixels.chunks_exact(4).any(|p| p[3] == 0));
    std::sync::Arc::make_mut(&mut pres.slides[0]).background = Some(deckcraft_model::Background::Fill { fill: deckcraft_model::style::Fill::Group });
    clear_opaque_slide_paper(&pres, 0, &mut opts);
    assert!(opts.clear.is_none(), "non-solid fills must not be replaced with opaque paper");
}


#[test]
#[ignore = "requires the pinned layered-96.pptx corpus via ALLDOC_PPTX_FIXTURE"]
fn stress_pinned_layered_presentation_renders_first_and_last() {
    let path = std::env::var("ALLDOC_PPTX_FIXTURE").expect("set ALLDOC_PPTX_FIXTURE to layered-96.pptx");
    let input = std::fs::read(path).unwrap();
    assert_eq!(input.len(), 443_070, "use the pinned 96-slide fixture");
    package(&input, 2, &mut || Ok(())).unwrap();
    let parsed = deckcraft_pptx::import(&input).unwrap();
    assert_eq!(parsed.slides.len(), 96);
    assert!(parsed.slides.iter().all(|slide| slide.shapes.len() >= 43));
    drop(parsed);
    for index in [0, 95] {
        let page = render(&input, 2, index, 320, || false).unwrap();
        assert_eq!((page.format, page.page, page.pages), (1, index, 96));
        let pixels = image::load_from_memory(&page.data).unwrap().into_rgb8();
        assert_eq!(pixels.dimensions(), (page.width, page.height));
        assert_eq!(page.width.max(page.height), 320);
        let colored = pixels.pixels().filter(|p| {
            let [r, g, b] = p.0;
            r.max(g).max(b) - r.min(g).min(b) > 32
        }).count();
        assert!(colored > 1000, "authored colored layers must render on slide {index}");
        let regions = page.raster_regions.as_ref().expect("fixture images have complete placement metadata");
        assert_eq!(regions.is_empty(), index != 0);
    }
}
