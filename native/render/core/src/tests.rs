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
    let input = presentation(AUDIO, &picture(1));
    let page = render(&input, 2, 0, 320, || false).unwrap();
    assert_eq!((page.format, page.page, page.pages), (1, 0, 2));
    let pixels = image::load_from_memory(&page.data).unwrap().into_rgba8();
    assert_eq!(pixels.dimensions(), (page.width, page.height));
    assert!(
        pixels.pixels().any(|p| p.0 == [217, 17, 191, 255]),
        "clip poster must render"
    );
    assert!(page.note.contains("playback/animation not rendered"));
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
