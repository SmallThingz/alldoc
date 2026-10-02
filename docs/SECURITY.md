# Security and data handling

- No Internet or broad-storage permission. Files are selected with Android SAF.
- ZIP entries are never extracted to filesystem paths. Traversal, duplicate names, macro payload names and signature members are rejected. Entry count and expanded byte limits apply.
- XML DOCTYPE, external entities and XInclude are disabled. External relationships are never fetched, macros and embedded objects are never executed.
- PDF parsing/rendering occurs in an Android isolated UID service with only a passed file descriptor. Native vulnerabilities are still possible; keep Android patched.
- Originals are immutable app-private copies. Draft metadata uses AtomicFile. Unchanged export returns the exact imported bytes. Edited XML packages preserve untouched member content, not raw ZIP metadata.
- Exports fully stage and fsync private output before opening the destination. SAF provider writes cannot universally be atomic. Failure may leave an incomplete destination; the original and in-memory edits are retained.
- CreateDocument requests a new file; identical source URI is blocked. Unusual providers may alias paths. Always use a new filename.
- Draft originals and edits remain in private application storage to support crash recovery; Android backup is disabled. Clearing app storage removes them.
- Current XML/render engines have size limits; pathological XML nesting and extreme PDFs require further fuzz/device testing before production distribution.
