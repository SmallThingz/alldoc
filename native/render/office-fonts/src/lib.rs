//! Bounded admission of public Android system font files for the isolated renderer.
//! No user directory, recursive discovery, document-supplied path or network input.
use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};

const MAX_ENTRIES: usize = 512;
const MAX_FILE: usize = 32 << 20;
// Reserve independent capacity for CJK and emoji. A document requesting many
// unrelated families cannot spend the bytes required by these fallback fonts.
const POOL_BYTES: [usize; 3] = [32 << 20, 16 << 20, 16 << 20];

fn pool(path: &Path) -> usize {
    let name = path.file_name().unwrap_or_default().to_string_lossy().to_ascii_lowercase();
    if name.contains("emoji") { 1 }
    else if name.contains("cjk") || name.contains("droidsansfallback")
        || ["notosansjp", "notoserifjp", "notosanskr", "notoserifkr",
            "notosanssc", "notoserifsc", "notosanstc", "notoseriftc",
            "notosanshk", "notoserifhk"].iter().any(|s| name.starts_with(s)) { 0 }
    else { 2 }
}

fn catalog(roots: &[PathBuf]) -> Vec<PathBuf> {
    let mut files = Vec::new();
    let mut examined = 0;
    for root in roots {
        let Ok(entries) = std::fs::read_dir(root) else { continue };
        let remaining = MAX_ENTRIES - examined;
        // Reject an oversized directory, rather than choosing a nondeterministic
        // readdir prefix. The same immutable font tree yields the same catalog.
        let entries: Vec<_> = entries.take(remaining + 1).collect();
        if entries.len() > remaining { break; }
        examined += entries.len();
        for entry in entries.into_iter().flatten() {
            let Ok(path) = std::fs::canonicalize(entry.path()) else { continue };
            if !roots.iter().any(|r| path.parent() == Some(r.as_path())) { continue; }
            let ext = path.extension().and_then(|s| s.to_str()).unwrap_or("").to_ascii_lowercase();
            if !matches!(ext.as_str(), "ttf" | "otf" | "ttc" | "otc") { continue; }
            let Ok(meta) = std::fs::metadata(&path) else { continue };
            if meta.is_file() && meta.len() > 0 && meta.len() <= MAX_FILE as u64 { files.push(path); }
        }
        if examined == MAX_ENTRIES { break; }
    }
    files.sort(); files.dedup(); files
}

/// Deterministic, nonrecursive catalog. Empty on non-Android platforms.
pub fn system_files() -> &'static [PathBuf] {
    static FILES: OnceLock<Vec<PathBuf>> = OnceLock::new();
    FILES.get_or_init(|| {
        #[cfg(target_os = "android")]
        let roots: Vec<_> = ["/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts"]
            .iter().filter_map(|p| std::fs::canonicalize(p).ok()).collect();
        #[cfg(not(target_os = "android"))]
        let roots: Vec<PathBuf> = Vec::new();
        catalog(&roots)
    })
}

#[derive(Default)]
struct Store { bytes: [usize; 3], files: HashMap<PathBuf, Arc<Vec<u8>>> }
impl Store {
    fn read(&mut self, files: &[PathBuf], path: &Path, accept: impl FnOnce(&[u8]) -> bool) -> Option<Arc<Vec<u8>>> {
        if files.binary_search_by(|p| p.as_path().cmp(path)).is_err() { return None; }
        if let Some(data) = self.files.get(path) { return accept(data).then(|| Arc::clone(data)); }
        let bucket = pool(path);
        let allowance = MAX_FILE.min(POOL_BYTES[bucket].saturating_sub(self.bytes[bucket]));
        let file = File::open(path).ok()?;
        let meta = file.metadata().ok()?;
        if !meta.is_file() || meta.len() == 0 || meta.len() > allowance as u64 { return None; }
        let mut data = Vec::with_capacity(meta.len() as usize);
        file.take(allowance as u64 + 1).read_to_end(&mut data).ok()?;
        if data.len() != meta.len() as usize || data.len() > allowance || !accept(&data) { return None; }
        self.bytes[bucket] += data.len();
        let data = Arc::new(data);
        self.files.insert(path.to_path_buf(), Arc::clone(&data));
        Some(data)
    }
}

/// Read only an admitted system file. Buffers are shared by collection faces and
/// the Word/Deck databases. A nonmatching fallback candidate does not spend the
/// retained-byte budget. Reads stay inside the parent's cancellable native child.
pub fn read_matching_font(path: &Path, accept: impl FnOnce(&[u8]) -> bool) -> Option<Arc<Vec<u8>>> {
    static STORE: OnceLock<Mutex<Store>> = OnceLock::new();
    STORE.get_or_init(|| Mutex::new(Store::default())).lock().unwrap_or_else(|e| e.into_inner())
        .read(system_files(), path, accept)
}

pub fn read_font(path: &Path) -> Option<Arc<Vec<u8>>> { read_matching_font(path, |_| true) }

#[cfg(test)]
mod tests {
    use super::*;
    struct OwnedDir(PathBuf);
    impl OwnedDir {
        fn new() -> Self {
            static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
            let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
            let root = std::env::current_dir().unwrap().join(".tmp").join(format!("office-fonts-{}-{stamp}-{}",
                std::process::id(), NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)));
            std::fs::create_dir_all(&root).unwrap(); Self(root.canonicalize().unwrap())
        }
        fn file(&self, name: &str, size: u64) -> PathBuf {
            let path = self.0.join(name);
            File::create(&path).unwrap().set_len(size).unwrap(); path
        }
    }
    impl Drop for OwnedDir { fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); } }

    #[test]
    fn catalog_is_sorted_nonrecursive_and_rejects_unlisted_paths() {
        let owned = OwnedDir::new();
        let z = owned.file("z.ttf", 8); let a = owned.file("a.otf", 4);
        std::fs::create_dir(owned.0.join("nested")).unwrap();
        owned.file("nested/hidden.ttf", 4);
        owned.file("not-a-font.txt", 4);
        let outside = OwnedDir::new(); let private = outside.file("private.ttf", 4);
        #[cfg(unix)]
        std::os::unix::fs::symlink(&private, owned.0.join("escape.ttf")).unwrap();
        let files = catalog(&[owned.0.clone()]);
        assert_eq!(files, vec![a.clone(), z]);
        let mut store = Store::default();
        assert!(store.read(&files, &private, |_| true).is_none());
        assert!(store.read(&files, &a, |_| false).is_none());
        assert_eq!(store.bytes, [0; 3]);
        let first = store.read(&files, &a, |_| true).unwrap();
        let again = store.read(&files, &a, |_| true).unwrap();
        assert!(Arc::ptr_eq(&first, &again));
        assert_eq!(first.as_slice(), &[0; 4]);
        assert_eq!(store.bytes, [0, 0, 4]);
    }

    #[test]
    fn unrelated_font_budget_cannot_spend_cjk_or_emoji_capacity() {
        let owned = OwnedDir::new();
        let ordinary = owned.file("Ordinary.ttf", POOL_BYTES[2] as u64);
        let excess = owned.file("Other.ttf", 1);
        let cjk = owned.file("NotoSansCJK-Regular.ttc", 32);
        let emoji = owned.file("NotoColorEmoji.ttf", 16);
        let oversized = owned.file("TooLarge.ttf", MAX_FILE as u64 + 1);
        let files = catalog(&[owned.0.clone()]);
        assert!(!files.contains(&oversized));
        let mut store = Store::default();
        assert!(store.read(&files, &ordinary, |_| true).is_some());
        assert!(store.read(&files, &excess, |_| true).is_none());
        assert_eq!(store.read(&files, &cjk, |_| true).unwrap().len(), 32);
        assert_eq!(store.read(&files, &emoji, |_| true).unwrap().len(), 16);
        assert_eq!(store.bytes, [32, 16, POOL_BYTES[2]]);
    }
}
