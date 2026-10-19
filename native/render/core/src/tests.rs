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

fn first_page_extent(input: &[u8], kind: u32) -> (f64, f64) {
    match kind {
        1 => {
            let doc = wordcraft_docx::read(input).unwrap();
            let layout = wordcraft_layout::layout(&doc, &mut wordcraft_layout::LayoutCache::new(),
                &wordcraft_layout::LayoutOptions::default());
            (layout.pages[0].w as f64, layout.pages[0].h as f64)
        }
        2 => {
            let pres = deckcraft_pptx::import(input).unwrap();
            (pres.slide_size.width, pres.slide_size.height)
        }
        3 => {
            let (wb, _) = gridcraft_xlsx::read_xlsx(input).unwrap();
            let layouts = gridcraft_engine::cmd::print::layouts(&wb,
                &gridcraft_engine::cmd::print::PrintOptions { all_sheets: true, ..Default::default() });
            let first = layouts.iter().find(|l| !l.pages.is_empty()).unwrap();
            (first.page_w as f64, first.page_h as f64)
        }
        _ => unreachable!(),
    }
}

#[test]
fn office_regions_match_off_center_crops_and_rasterize_new_deep_detail() {
    for (input, kind) in [
        (&include_bytes!("../../../../app/src/androidTest/assets/office/sample.docx")[..], 1),
        (BASE, 2),
        (&include_bytes!("../../../../app/src/androidTest/assets/office/sample.xlsx")[..], 3),
    ] {
        let (pw, ph) = first_page_extent(input, kind);
        let full = render(input, kind, 0, 1400, || false).unwrap();
        let (x, y) = (full.width / 10, full.height / 10);
        let (width, height) = (full.width / 2, full.height / 2);
        let region = Region { left: x as f64 / full.width as f64, top: y as f64 / full.height as f64,
            right: (x + width) as f64 / full.width as f64, bottom: (y + height) as f64 / full.height as f64, width, height };
        let detail = render_region(input, kind, 0, region, || false).unwrap();
        assert_eq!((detail.width, detail.height, detail.page, detail.pages), (width, height, full.page, full.pages));
        let full_pixels = image::load_from_memory(&full.data).unwrap().into_rgba8();
        let expected = image::imageops::crop_imm(&full_pixels, x, y, width, height).to_image();
        let actual = image::load_from_memory(&detail.data).unwrap().into_rgba8();
        let error: u64 = expected.as_raw().iter().zip(actual.as_raw()).map(|(a, b)| a.abs_diff(*b) as u64).sum();
        assert!(error as f64 / (expected.as_raw().len() as f64) < 1.0,
            "kind {kind}: a translated native region must retain page content and glyph positions");
        let low = Region { left: 0.05, top: 0.05, right: 0.30, bottom: 0.30,
            width: 240, height: (240.0 * ph / pw).round() as u32 };
        let high = Region { width: 1200, height: (1200.0 * ph / pw).round() as u32, ..low };
        assert!(high.view(pw, ph).unwrap().scale_x > 4.0);
        let low = render_region(input, kind, 0, low, || false).unwrap();
        let high = render_region(input, kind, 0, high, || false).unwrap();
        let low = image::load_from_memory(&low.data).unwrap().into_rgba8();
        let high_pixels = image::load_from_memory(&high.data).unwrap().into_rgba8();
        assert!(high_pixels.pixels().filter(|p| p.0[..3].iter().any(|c| *c < 160)).count() > 50);
        let enlarged = image::imageops::resize(&low, high.width, high.height, image::imageops::FilterType::Triangle);
        assert_ne!(high_pixels.as_raw(), enlarged.as_raw(), "kind {kind}: deep detail must rasterize source glyphs");
        assert_eq!(high.raster_regions, Some(vec![]));
    }
}

#[test]
fn docx_deep_region_keeps_large_glyph_ink_when_its_origin_is_outside() {
    let input = rewrite_parts(include_bytes!("../../../../app/src/androidTest/assets/office/sample.docx"), |name, data| {
        if name == "word/document.xml" {
            // 600 half-points is a valid 300pt authored run, using the existing open font.
            br#"<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:spacing w:before="0" w:after="0"/></w:pPr><w:r><w:rPr>
<w:rFonts w:ascii="Source Sans 3" w:hAnsi="Source Sans 3"/><w:b w:val="0"/><w:i w:val="0"/>
<w:color w:val="000000"/><w:sz w:val="600"/><w:szCs w:val="600"/>
</w:rPr><w:t>W</w:t></w:r></w:p>
<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
</w:body></w:document>"#.to_vec()
        } else { data }
    });
    let doc = wordcraft_docx::read(&input).unwrap();
    let layout = wordcraft_layout::layout(&doc, &mut wordcraft_layout::LayoutCache::new(),
        &wordcraft_layout::LayoutOptions::default());
    assert_eq!(layout.pages.len(), 1);
    let page = &layout.pages[0];
    assert_eq!((page.w, page.h), (612.0, 792.0));
    let items = wordcraft_layout::display::page_display(&doc, page, &Default::default());
    let origins: Vec<_> = items.iter().filter_map(|item| match item {
        wordcraft_layout::display::Draw::Glyphs { size, glyphs, text, .. } if text == "W" => {
            assert_eq!(*size, 300.0);
            assert_eq!(glyphs.len(), 1);
            Some(glyphs[0].1 as f64)
        }
        _ => None,
    }).collect();
    assert_eq!(origins, vec![72.0]);

    // The whole page is rendered independently at one pixel per source point.
    let full = render(&input, 1, 0, 792, || false).unwrap();
    assert_eq!((full.width, full.height, full.pages), (612, 792, 1));
    let full_pixels = image::load_from_memory(&full.data).unwrap().into_rgb8();
    let (x, width, height, scale) = (232, 96, 128, 16);
    // This exercises the old origin-only cull, including its 40pt viewport inflation
    // and 100pt left allowance. Actual raster ink below, not this predicate, is the oracle.
    assert!(origins[0] < x as f64 - 40.0 - 100.0);
    assert!(full_pixels.enumerate_pixels().filter(|(px, _, p)| *px < x && p.0 == [0, 0, 0]).count() > 256,
        "The crop must include only part of the authored glyph");
    let ink_top = (0..full.height).find(|y| (x..x + width)
        .any(|px| full_pixels.get_pixel(px, *y).0.iter().any(|c| *c < 32)))
        .expect("The whole-page glyph must visibly overlap the off-origin crop");
    let y = ink_top + 16;
    assert!(y + height < full.height);
    let expected = image::imageops::crop_imm(&full_pixels, x, y, width, height).to_image();
    let region = Region { left: x as f64 / full.width as f64, top: y as f64 / full.height as f64,
        right: (x + width) as f64 / full.width as f64, bottom: (y + height) as f64 / full.height as f64,
        width: width * scale, height: height * scale };
    assert!(region.view(page.w as f64, page.h as f64).unwrap().scale_x > 15.0);
    let detail = render_region(&input, 1, 0, region, || false).unwrap();
    assert_eq!((detail.width, detail.height, detail.page, detail.pages), (1536, 2048, 0, 1));
    assert_eq!(detail.raster_regions, Some(vec![]));
    let actual = image::load_from_memory(&detail.data).unwrap().into_rgb8();
    let (mut ink_samples, mut paper_samples) = (0, 0);
    for py in 1..height - 1 {
        for px in 1..width - 1 {
            let color = expected.get_pixel(px, py).0;
            if color != [0, 0, 0] && color != [255, 255, 255] { continue; }
            // Compare strict interior pixels, leaving only scale-dependent antialiasing out.
            if !(py - 1..=py + 1).all(|ny| (px - 1..=px + 1)
                .all(|nx| expected.get_pixel(nx, ny).0 == color)) { continue; }
            assert_eq!(actual.get_pixel(px * scale + scale / 2, py * scale + scale / 2).0, color,
                "Deep DOCX crop lost whole-page ink/paper at source ({}, {})", x + px, y + py);
            if color == [0, 0, 0] { ink_samples += 1; } else { paper_samples += 1; }
        }
    }
    assert!(ink_samples > 256, "The whole-page oracle must contain substantial visible glyph ink");
    assert!(paper_samples > 256, "The crop must also preserve surrounding page paper");
}

#[test]
fn region_bounds_keep_tall_outputs_bounded_and_receipts_identify_the_viewport() {
    let region = Region { left: 0.1, top: 0.1, right: 0.2, bottom: 0.3, width: 1600, height: 3200 };
    assert!(region.view(100.0, 100.0).is_ok());
    for bad in [Region { left: f64::NAN, ..region }, Region { right: 0.1, ..region },
        Region { bottom: 1.1, ..region }, Region { width: 4097, ..region },
        Region { width: 4096, height: 4096, ..region }] {
        assert!(bad.checked().is_err());
    }
    let anisotropic = Region { height: 3100, ..region }.view(100.0, 100.0).unwrap();
    assert_ne!(anisotropic.scale_x, anisotropic.scale_y);
    let (pw, ph) = first_page_extent(BASE, 2);
    let tall = Region { left: 0.0, top: 0.0, right: 0.01,
        bottom: 3000.0 * pw * 0.01 / (256.0 * ph), width: 256, height: 3000 };
    let page = render_region(BASE, 2, 0, tall, || false).unwrap();
    assert_eq!((page.width, page.height), (256, 3000));
    let wire = wire_region(Ok(page), tall);
    assert_eq!(&wire[..4], b"ZFR3");
    assert_eq!(f32::from_le_bytes(wire[44..48].try_into().unwrap()), tall.right as f32);
    assert_eq!(render_region(BASE, 2, 0, tall, || true).unwrap_err(), "CANCELLED");
    assert!(render(BASE, 2, 0, 3000, || false).is_err(), "full-page export retains its old side budget");
}

#[test]
fn translated_picture_regions_enclose_the_actual_detail_pixels() {
    let input = presentation(AUDIO, &opaque_picture_bytes());
    let (pw, ph) = first_page_extent(&input, 2);
    // The authored poster occupies [72,288]..[144,360] points.
    let region = Region { left: 60.0 / pw, top: 280.0 / ph, right: 160.0 / pw, bottom: 380.0 / ph,
        width: 800, height: 773 };
    let page = render_region(&input, 2, 0, region, || false).unwrap();
    let bounds = page.raster_regions.unwrap();
    assert!(!bounds.is_empty());
    let pixels = image::load_from_memory(&page.data).unwrap().into_rgba8();
    let mut count = 0;
    for (x, y, _) in pixels.enumerate_pixels().filter(|(_, _, p)| p.0 == [217, 17, 191, 255]) {
        count += 1;
        let (x, y) = ((x as f32 + 0.5) / region.width as f32, (y as f32 + 0.5) / region.height as f32);
        assert!(bounds.iter().any(|r| x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]));
    }
    assert!(count > 1000);
}

#[test]
fn region_affines_map_all_four_requested_corners_without_base_rounding_loss() {
    for (pw, ph) in [(39.0, 11.0), (39.3, 11.2), (612.35, 792.1), (720.0, 540.0)] {
        let region = Region { left: 0.17, top: 0.23, right: 0.83, bottom: 0.91, width: 731, height: 2003 };
        let view = region.view(pw, ph).unwrap();
        for (x, y, ox, oy) in [(region.left, region.top, 0.0, 0.0),
            (region.right, region.top, region.width as f64, 0.0),
            (region.left, region.bottom, 0.0, region.height as f64),
            (region.right, region.bottom, region.width as f64, region.height as f64)] {
            let mapped = view.affine() * deckcraft_geom::Point::new(x * pw, y * ph);
            assert!((mapped.x - ox).abs() < 1e-8 && (mapped.y - oy).abs() < 1e-8);
        }
    }
}

#[test]
fn odd_slide_aspect_keeps_both_bottom_corners_at_base_rounded_detail_dimensions() {
    let (cx, cy) = (499_110, 142_240); // 39.3 x 11.2 points; base ceil dimensions are79x23.
    let colors = ["E02030", "20B040", "3040D0", "D0A020"];
    let mut shapes = String::new();
    for (i, color) in colors.iter().enumerate() {
        let (x, y) = ((i % 2) as i32 * cx / 2, (i / 2) as i32 * cy / 2);
        shapes.push_str(&format!(r#"<p:sp><p:nvSpPr><p:cNvPr id="{}" name="Corner"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{}" cy="{}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="{color}"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>"#, 100 + i, cx / 2, cy / 2));
    }
    let input = rewrite_parts(BASE, |name, data| {
        if name == "ppt/presentation.xml" {
            String::from_utf8(data).unwrap().replace(
                r#"<p:sldSz cx="9144000" cy="6858000" type="screen4x3"/>"#,
                &format!(r#"<p:sldSz cx="{cx}" cy="{cy}" type="custom"/>"#)).into_bytes()
        } else if name == "ppt/slides/slide1.xml" {
            String::from_utf8(data).unwrap().replace("</p:spTree>", &format!("{shapes}</p:spTree>")).into_bytes()
        } else { data }
    });
    let base = render(&input, 2, 0, 1400, || false).unwrap();
    assert_eq!((base.width, base.height), (79, 23));
    let crop = Region { left: 10.0 / base.width as f64, top: 3.0 / base.height as f64,
        right: 69.0 / base.width as f64, bottom: 20.0 / base.height as f64, width: 59, height: 17 };
    let cropped = render_region(&input, 2, 0, crop, || false).unwrap();
    let base_pixels = image::load_from_memory(&base.data).unwrap().into_rgb8();
    let expected = image::imageops::crop_imm(&base_pixels, 10, 3, 59, 17).to_image();
    let actual = image::load_from_memory(&cropped.data).unwrap().into_rgb8();
    let error: u64 = expected.as_raw().iter().zip(actual.as_raw()).map(|(a, b)| a.abs_diff(*b) as u64).sum();
    assert!(error as f64 / (expected.as_raw().len() as f64) < 1.0,
        "Ceil-sized base canvas and normalized detail must place the same source edges identically");
    let region = Region { left: 0.0, top: 0.0, right: 1.0, bottom: 1.0,
        width: 1600, height: (1600.0 * base.height as f64 / base.width as f64).round() as u32 };
    let page = render_region(&input, 2, 0, region, || false).unwrap();
    let image = image::load_from_memory(&page.data).unwrap().into_rgb8();
    for (x, y, color) in [(10, 10, [224, 32, 48]), (region.width - 11, 10, [32, 176, 64]),
        (10, region.height - 11, [48, 64, 208]), (region.width - 11, region.height - 11, [208, 160, 32])] {
        assert_eq!(image.get_pixel(x, y).0, color, "Requested corners must not lose source pixels");
    }
    assert_eq!(page.raster_regions, Some(vec![]));
}
