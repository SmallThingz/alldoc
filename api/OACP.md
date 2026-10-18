# OACP

AllDoc implements OACP 0.3 with the official Android SDK used by the OACP BinaryEye integration. The pinned, unmodified Kotlin sources and license live in `oacp-sdk/`. Discovery is read-only:

- `content://app.smallthingz.alldoc.oacp/manifest` — capability JSON.
- `content://app.smallthingz.alldoc.oacp/context` — assistant context.

No recent-document entities or reading history are published.

| Capability | Activity action suffix | Result |
|---|---|---|
| `read_document` | `ACTION_READ_DOCUMENT` | UTF-8 text |
| `document_structure` | `ACTION_DOCUMENT_STRUCTURE` | Hierarchical JSON |
| `render_page` | `ACTION_RENDER_PAGE` | Print/document page PNG, original colors |
| `render_sheet` | `ACTION_RENDER_SHEET` | Worksheet viewport PNG; no print pagination |

Prefix each suffix with `app.smallthingz.alldoc.oacp.`. Results use the official `org.oacp.ACTION_RESULT` broadcast, targeted only to the reply package. Register a receiver before launch; match `org.oacp.extra.REQUEST_ID` and verify `org.oacp.extra.SOURCE_PACKAGE` alongside the Android sender identity where available. As with any exported broadcast receiver, a claimed source-package extra alone is not authentication.

## Request

A normal Activity Result launch establishes the calling package on all supported Android versions. AllDoc accepts Android 15+ initial-caller identity when the platform shares it, too. Neither path needs an extra confirmation for a caller-readable source:

```kotlin
val request = Intent("app.smallthingz.alldoc.oacp.ACTION_READ_DOCUMENT").apply {
    setPackage("app.smallthingz.alldoc")
    putExtra("org.oacp.extra.REQUEST_ID", UUID.randomUUID().toString())
    putExtra("uri", source.toString())
    clipData = ClipData.newRawUri("Document", source)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
launcher.launch(request) // ActivityResultContracts.StartActivityForResult
```

The caller must own the source provider or have an Android read grant for the URI; AllDoc must also receive read access. The Activity Result contains status and a read-granted URI/MIME on success. An authenticated caller overrides any `recipient_package` extra.

For a foreground launch without authenticated caller identity, provide the installed `recipient_package`; AllDoc shows that recipient and asks the user to choose a document. Supplied URIs never borrow AllDoc's saved permissions. Invalid supplied sources fail rather than silently opening a picker.

Parameters are literal extras (official SDK suffix aliases are also accepted):

| Parameter | Limit/default |
|---|---|
| `org.oacp.extra.REQUEST_ID` | Required, nonblank, 1–128 characters |
| `uri` | Optional `content:` URI, at most 8,192 characters |
| `recipient_package` | Needed only without caller identity; at most 256 characters |
| `display_name`, `mime_type` | Optional, at most 256 / 128 characters |
| `password` | Optional PDF password, at most 256 characters |
| `page` | `render_page` only; zero-based, 0–99,999; default 0 |
| `width` | PNG only; 64–2,048 pixels; default 1,200 |
| `sheet` | `render_sheet` only; original index 0–1,023; omitted selects the active sheet |
| `x`, `y` | Worksheet origin; finite 0–1,000,000,000 in logical 96-dpi units; default 0 |
| `scale` | Worksheet pixels per logical unit; finite 0.0625–32; default 1 |
| `height` | Worksheet viewport; 64–2,048 pixels; default 800 |
| `theme` | Worksheet only: `original` (default), `adaptive`, `adaptive_light`, `system`, `sepia`, `high_contrast`, `invert` |
| `imageTreatment` | Worksheet only: `auto` (default), `keep`, `all` |

Integer extras accept exact integer values or decimal integer strings; fractional/overflowing values fail. Worksheet coordinates and scale accept finite numeric values or numeric strings without integer narrowing. Both viewport dimensions are bounded independently, limiting an image to 4,194,304 pixels. AUTO uses the worksheet renderer’s actual image masks; `all` maps to its RECOLOR policy. SYSTEM resolves from the current device theme. Passwords are not logged. The source is never edited or added to reading history.

Native XLSX-family worksheets render supported styles, charts and drawings on a best-effort basis. XLS/ODS/OTS/FODS and structured fallback grids show saved cell values; they do not reconstruct original styles, merged cells, charts or drawings. Inspect both `fidelity` and `warnings`; worksheet availability does not imply native layout fidelity. Numbers saved previews use `render_page`, not `render_sheet`.

## Result and lifetime

A successful broadcast has status `completed`; `org.oacp.extra.RESULT` is a JSON string containing `uri`, `mimeType`, `bytes`, `format` (`text`, `json`, `png`), `fidelity`, `warnings`, `page` and `continuous`. `render_sheet` keeps the common URI/format/fidelity fields and replaces `page`/`continuous` with `view="worksheet"`, `paginationKnown=false`, `sheet`, `viewport`, `theme` and `imageTreatment`. The sheet descriptor has `index`, `name` (at most 256 Unicode characters), `rowCount`, `columnCount`, `usedRows`, `usedColumns`, `width` and `height` (logical units); viewport has `x`, `y`, `scale`, `width` and `height` (pixel dimensions). No print-page count is requested or implied. Non-worksheet documents return `unsupported_state`. Document content is read from the granted URI, never placed in the broadcast. Inspect fidelity and warnings; JSON may explicitly describe a bounded partial document. Scanned document pages have no OCR. `continuous=true` identifies Markdown’s one logical page; other semantic readers may have virtual pages. Oversized Markdown page images fail explicitly instead of returning a clipped image. Accepted inputs follow the [document format registry](../docs/FORMATS.md); text, JSON and PNG describe output formats, not additional accepted file types.

Text and JSON results describe extraction fidelity independently of the page renderer: native Office text/structure exports remain TEXT_STRUCTURE, and PDF text remains extracted-text. Their warnings include semantic omissions. PNG results retain the page or worksheet renderer's fidelity. Saved Apple previews remain explicitly EMBEDDED_PREVIEW_ONLY.

Known actions with valid request IDs receive correlated errors even when a parameter fails validation. Errors include `invalid_parameters`, `not_authorized`, `not_authenticated`, `unsupported_state`, `cancelled` and `internal_error`. Back/Cancel reports cancellation. There is no remote cancel capability. Process termination interrupts a request; resubmit with a new ID.

Two operations can run at once; admission fails promptly when both workers are occupied. Each has a 60-second cancellation deadline. Existing engine input limits remain in force (at most 128 MiB), output is limited to 32 MiB, and retained results to eight files / 64 MiB. Two active outputs can temporarily add at most 64 MiB. Results expire after one hour or earlier cache eviction; read them promptly. Expired grants are revoked during pruning, and expired files cannot be opened. Cancellation is cooperative around native/provider calls; a reply does not free its worker until cleanup completes.

For an embedded viewer, retain a session through the [AIDL API](PROTOCOL.md) to reuse parsed content and page/worksheet caches; its preview-open/readSheet path also avoids print pagination. OACP is the assistant handoff; no network listener or background service is added.
