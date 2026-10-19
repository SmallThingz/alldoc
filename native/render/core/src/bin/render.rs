use std::io::Read;
fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.get(1).is_some_and(|v| v == "--sheets") {
        std::process::exit(a.get(2).map_or(2, |path| alldoc_render::sheets::serve(std::path::Path::new(path))));
    }
    let regional = a.get(1).is_some_and(|v| v == "--region");
    if a.len() < if regional { 12 } else { 5 } {
        std::process::exit(2)
    }
    let shift = usize::from(regional);
    let kind = a[1 + shift].parse().unwrap_or(0);
    let side = if regional { 0 } else { a[4].parse().unwrap_or(0) };
    let page = a.get(5).and_then(|s| s.parse().ok()).unwrap_or(0);
    let region = regional.then(|| alldoc_render::Region {
        left: a[6].parse().unwrap_or(f64::NAN), top: a[7].parse().unwrap_or(f64::NAN),
        right: a[8].parse().unwrap_or(f64::NAN), bottom: a[9].parse().unwrap_or(f64::NAN),
        width: a[10].parse().unwrap_or(0), height: a[11].parse().unwrap_or(0),
    });
    let cancel_after: usize = a.get(if regional { 12 } else { 6 }).and_then(|s| s.parse().ok()).unwrap_or(usize::MAX);
    let mut polls = 0usize;
    let start = std::time::Instant::now();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let file =
            std::fs::File::open(&a[2 + shift]).map_err(|_| "Visual input unavailable".to_string())?;
        let mut bytes = Vec::new();
        file.take(alldoc_render::INPUT_LIMIT as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "Visual input read failed".to_string())?;
        let cancel = || {
            polls += 1;
            polls >= cancel_after
        };
        if let Some(region) = region { alldoc_render::render_region(&bytes, kind, page, region, cancel) }
        else { alldoc_render::render(&bytes, kind, page, side, cancel) }
    }))
    .unwrap_or_else(|_| Err("Visual engine rejected an invalid document".into()));
    let successful = result.is_ok();
    let wire = if let Some(region) = region { alldoc_render::wire_region(result, region) }
        else { alldoc_render::wire(result) };
    if std::fs::write(&a[3 + shift], wire).is_err() {
        std::process::exit(3)
    }
    println!(
        "render_ms={} polls={} success={}",
        start.elapsed().as_millis(),
        polls,
        successful
    );
    if !successful {
        std::process::exit(1)
    }
}
