# AllDoc

## What this app does

Reads documents offline and exports text, hierarchical JSON or a page image. Source files and reading history stay unchanged.

## Vocabulary

A document is a caller-readable content URI or a fresh picker selection. Page numbers are zero-based. Markdown has one continuous logical page (page=0); other semantic readers may have virtual pages. Fidelity and warnings describe omissions, excerpts and unsupported layout.

## Capabilities

Use read_document for text, document_structure for sections/tables as JSON, and render_page for a PNG in original colors. Open an activity with a unique org.oacp.extra.REQUEST_ID (1–128 characters).

Pass a content URI in uri and ClipData with Android read permission. An authenticated startActivityForResult caller, or Android 15+ caller sharing its identity, receives results automatically. Otherwise supply recipient_package; AllDoc asks the user to select a document for that recipient. Supplied inaccessible sources are rejected. Recent documents and saved source permissions are not exposed to assistants.

Register for org.oacp.ACTION_RESULT before launch. Results target only the reply package. A completed RESULT JSON contains uri, mimeType, bytes, format, fidelity, warnings, page and continuous. Markdown returns continuous=true. Open the read-granted URI promptly; document contents never appear inline in a broadcast.

## Constraints

Outputs are limited to 32 MiB and expire after an hour or earlier cache eviction (64 MiB / eight files). Two requests can run at once; additional requests fail promptly. Cancel with Android Back or Cancel. Each operation has a 60-second cancellation deadline; native/provider calls may finish cleanup later. No network server or background service is created.

Read fidelity and warnings before describing a result as complete. JSON can describe bounded partial content; text excerpts are marked. Scanned document pages have no OCR. Standalone images and plain-text files are not accepted; text, JSON and PNG are output formats. Oversized Markdown page images fail explicitly instead of returning a clipped image. Unsupported files and missing or incorrect PDF passwords fail explicitly.

## Examples

- Read this report: read_document with its granted uri.
- Extract this spreadsheet's tables: document_structure, then inspect JSON fidelity and warnings.
- Show the first slide: render_page with page=0 and width=1200.
