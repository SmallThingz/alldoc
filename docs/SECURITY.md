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

Additional hardening: aggregate XML budget 100,000 nodes/events, depth 128, 128 attributes per element, 4 Mi-character cumulative edit budget; all XML/RELS parts are checked before preview. A SAX preflight compatible with Android rejects DTDs before DOM parsing. Preview packages normalize ZIP names to eliminate local/central-directory parser disagreement. Recovery snapshots carry monotonic revision tokens so late writers cannot resurrect stale drafts. Original and recovery data are fsynced, commit bytes verified, and uncertain commit candidates retained.

## 0.2 upgrade and format safeguards

- Recovery schema 3 records the engine mapping version. Earlier non-PDF drafts are archived before parsing with the new engine. Office edits whose positional IDs changed are not applied automatically. The original and all edits are verified in a separate ZIP before recovery can be replaced; corrupt/reused archives are never trusted only by filename. A visible Recovered drafts action exports these archives, which are excluded from normal recovery pruning.
- PDF form editing is isolated and Android 15+ only. A conservative whole-file, bounded PDF name scanner recognizes encryption and signature markers, including escaped names and incremental revisions. Marker-like comments/strings/streams may cause deliberate false positives. Both initial field access and fresh-copy export enforce it. Read-only fields and unsupported form types are refused; output is reopened to verify page count and requested values.
- Legacy Office extraction uses POI in read-only mode with an 8 MiB input bound, per-allocation limits, bounded output/records/directories and refusal of recognized macro, encryption and signature structures. It never executes code, evaluates formulas or writes OLE files. POI parsing is not a hard aggregate-memory sandbox, and Android device behavior remains to be verified.
- Slide geometry is checked before conversion to Android layout constraints. Pathological dimensions, off-canvas frames or complex geometry fall back to a virtualized text outline instead of allocating an unbounded preview.
