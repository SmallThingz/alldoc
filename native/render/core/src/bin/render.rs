use std::io::Read;
fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() < 5 {
        std::process::exit(2)
    }
    let kind = a[1].parse().unwrap_or(0);
    let side = a[4].parse().unwrap_or(0);
    let page = a.get(5).and_then(|s| s.parse().ok()).unwrap_or(0);
    let cancel_after: usize = a.get(6).and_then(|s| s.parse().ok()).unwrap_or(usize::MAX);
    let mut polls = 0usize;
    let start = std::time::Instant::now();
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let file =
            std::fs::File::open(&a[2]).map_err(|_| "Visual input unavailable".to_string())?;
        let mut bytes = Vec::new();
        file.take(alldoc_render::INPUT_LIMIT as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "Visual input read failed".to_string())?;
        alldoc_render::render(&bytes, kind, page, side, || {
            polls += 1;
            polls >= cancel_after
        })
    }))
    .unwrap_or_else(|_| Err("Visual engine rejected an invalid document".into()));
    let successful = result.is_ok();
    let wire = alldoc_render::wire(result);
    if std::fs::write(&a[3], wire).is_err() {
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
