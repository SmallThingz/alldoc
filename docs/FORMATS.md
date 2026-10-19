# Document formats

AllDoc separates **page rendering** from **text and structure**. A readable file is not necessarily reproduced with its original layout. The session metadata and JSON export include fidelity and warnings; truncated plain-text exports include an excerpt notice. Macros, scripts and remote resources are not executed.

| Format family | Reader | Text / JSON | Limits and omissions |
|---|---|---|---|
| PDF | Android page renderer, Android 9+ | Android 15+ platform extraction; compact Rust fallback on older Android | Scanned pages need OCR, which is not included. Complex reading order and fonts may affect extracted text. |
| DOCX, DOCM, DOTX, DOTM | WordCraft pages; semantic fallback | Paragraphs, headings and table content | Font substitution and unsupported layout features can change appearance. Macros are ignored. |
| XLSX, XLSM, XLTX, XLTM | GridCraft worksheet grid; separate print pages | Sparse sheets and cell coordinates | Saved cell values are not recalculated. Styles, merged cells, charts and drawings are best effort. Supported formatting and chart data use saved values; unsupported formula expressions are omitted. See worksheet limits below. |
| PPTX, PPTM, PPSX, PPSM, POTX, POTM | DeckCraft slides; semantic fallback | Slides in presentation order and text blocks | No animation or transition playback. Font substitution may change layout. |
| DOC, DOT; PPT, PPS, POT | Text / structure | Word 97–2003 and binary PowerPoint | Earlier binary formats, encrypted documents, drawings and embedded objects are not supported. |
| XLS; ODS, OTS, FODS | Structured cell grid; virtual print pages | Saved cell values and sparse coordinates | Original styles, merged cells, charts and drawings are not reconstructed. Semantic extraction limits apply. |
| ODT, OTT, ODM, OTH; ODP, OTP; ODG, OTG | Text / structure | Paragraphs, cells, slide / drawing text | Drawings are not reconstructed; external master-document parts are not loaded. |
| FODT, FODP, FODG | Text / structure | Flat OpenDocument XML | Same semantic limits as packaged OpenDocument. |
| RTF | Text / structure | Unicode paragraphs | Formatting and embedded objects are omitted. |
| EPUB | Text / structure | Reading order and chapter text | No publisher page layout, DRM, external assets or embedded scripting. |
| FictionBook FB2 | Text / structure | Titles, paragraphs and verse | Images and binary attachments omitted. XML document types and entities rejected. |
| HTML, HTM, XHTML | Readable text | Title and visible text | Scripts, styles, images and remote content omitted. |
| Pages, Numbers, Keynote | Saved embedded PDF / JPEG, when present | Saved-preview text / structure when extractable; metadata for image-only or absent previews | Exports retain preview provenance. Original IWA content is not decoded, and previews may be absent, stale or incomplete. Export the current document from iWork as PDF / Office for access to its current content. |
| Markdown: MD, MARKDOWN | One continuous formatted document | Parsed text and structured blocks | Headings, emphasis, lists, quotes, tables and code. Inline images retain their alt text; images and remote resources are not fetched or rendered. Active HTML is omitted. |

Normal scrolls documents vertically, except spreadsheets: their worksheet grid pans horizontally and vertically, with row/column headings, zoom and sheet selection. Present advances intrinsic or virtual pages horizontally, one page at a time; zoomed pages retain internal panning. Markdown has one continuous formatted reading view and a Text alternative, with no Normal/Present chooser or artificial page breaks. Other semantic readers use bounded virtual pages. Extractable documents also offer a Text view, saved per document; search uses that surface temporarily without changing the saved choice. Spreadsheet Present, printing and public page exports retain print pagination. The app’s Visible sheet image export saves the current worksheet viewport instead. Print layout is resolved when needed rather than during normal grid reading. Paginated IPC metadata and page exports use the same logical page count; worksheet preview metadata omits `pageCount`.

Markdown image export and printing render the complete logical page or report a size limit; they do not silently clip the document or create extra logical pages. Raster output is limited to 8 million pixels and 8,192 pixels high. Printing other documents uses bounded raster pages in original colors: source PDF vectors and selectable text are not preserved. Print images are at most 1,600 pixels wide; selected pages, printer paper size, margins and monochrome mode are supported.

Standalone raster/vector images, PSD/PSB, plain text, source code, configuration/data files, email, contacts, calendars, certificates and 3D models are not supported inputs. Embedded images remain part of supported documents. Generic archives, DjVu, XPS / OXPS, MOBI / AZW, CHM, WordPerfect and Apple IWA semantic decoding are not implemented. Unsupported content reports an error instead of falling back to arbitrary source text.

## Bundled examples

Debug builds include Home → Examples with 46 offline samples, one for each supported extension. Release builds omit the examples screen and its sample assets. `.dot` is a Word template. Examples are synthetic, small and searchable; browsing does not copy documents or add them to Library or Recents. Opening a sample copies only that asset into bounded private storage and uses the ordinary reader. iWork samples demonstrate saved embedded previews, not IWA decoding.

`scripts/build-examples.py --check` verifies the catalog against the format registry, asset sizes and hashes. Device tests open every sample through the document engine, extract available text/JSON and render its first and last page. This checks the bundled files; it does not imply complete support for every feature of their formats. Generation and provenance are documented under `scripts/`.

## Notes and comments

The Notes panel is separate from the reading body. Text exports append a labelled notes section; JSON includes `notes` entries with text, kind and available author/location/page, plus warnings and truncation status.

- PDF: plain-text annotation contents, including comments and highlight notes. Popup duplicates, links/actions, attachments/media and rich-text-only payloads are skipped. An image-only PDF can contain real annotations; pixels themselves are not OCRed.
- OOXML: PowerPoint speaker notes and slide comments; referenced Word comments; Excel cell notes and threaded comments. Both are read; same-cell compatibility duplicates are omitted.
- OpenDocument: annotations and presentation notes in packaged or flat text, spreadsheet, presentation and drawing formats.

Legacy binary Office notes, tracked changes, Word footnotes/endnotes and exact Word page mapping are not implemented. Notes extraction is lazy; failures preserve the readable body and are reported separately. Known page mappings support navigation, while unmapped locations remain labels.

Office notes retain at most 2,048 items and 524,288 UTF-16 code units of note text, with a 16,384-unit per-note limit, bounded XML/package processing and a deadline. PDF notes retain at most 4,096 entries across 2,000 pages, with 2,000,000 UTF-8 bytes of text and author names in total and 16 KiB of text per note. Limits mark omissions rather than presenting the result as complete.

## Bounds

- Semantic Office input: 16 MiB; expanded ZIP content: 32 MiB; individual XML/package parts have additional bounds.
- Native XLSX grid: 16,384 rows, 512 columns and 131,072 stored cells across the workbook. Reader viewport images are bounded to 4,096 pixels per side and 8 million pixels. Visible sheet image exports are at most 2,800 pixels per side; public `readSheet` images are at most 2,048 pixels per side. Worksheets beyond native admission limits may use a structured fallback when opening; later viewport errors remain visible and retryable. Frozen panes and right-to-left sheet placement are not recreated, and session warnings report these limitations.
- Structured XLS grids use BIFF8 coordinate bounds; ODS/FODS grids use up to 1,048,576 rows and 16,384 columns without allocating blank cells. These bounds describe navigation, not unlimited extracted content. Numbers remains an embedded-preview reader, not a worksheet grid.
- Native text content: 512 KiB, at most 4,096 blocks and 64 sections; bounded titles and warnings are additional. Structured exports preserve sparse sheet coordinates; sparse plain-text tables use explicit one-based row/column labels instead of allocating empty-cell padding.
- Text-based document parsers read at most 512 KiB of encoded input. Excerpts and omitted content are reported.
- Text-based documents accept UTF-8 and BOM-labelled UTF-16 / UTF-32. Malformed encodings and binary control data are rejected.
- FB2 XML input: 512 KiB.
- iWork packages: 16 MiB, 4,096 entries; one bounded embedded preview with length and CRC validation.
- Files may reach a format-specific limit before the application input limit. Exceptions and session warnings explain the limit.

Bounded content signatures select known formats even with unfamiliar names. Name/provider hints remain useful when a package probe is inconclusive; each parser still validates its input. Library scans need a compatible legacy Office filename or provider hint for new entries. Previously unknown, unhinted compound files require explicit opening for full DOC/XLS/PPT validation before indexing; existing admitted entries can survive lost hints only when their bytes still match. Compound storage alone does not establish a supported document type. Renaming a file does not convert it. Password-protected Office documents are rejected. PDF password handling is supported by the platform reader on Android 15+; availability on older releases differs.

## Implementation and verification

The semantic Office reader is an attributed fork of ZFM's bounded Rust core, using quick-xml, flate2, cfb and encoding_rs. Source provenance and dependency notices live in `native/documents`; notices are bundled in the app. Visual engines remain separate upstream submodules.

Core JVM tests exercise document admission, Unicode boundaries, Markdown, HTML, FB2, exports and cancellation. Host JNI tests exercise DOCX, ODF / ODG, RTF and malformed packages; temporary Apache POI public samples also verified DOC, XLS and PPT extraction. Rust tests cover legacy Office parsing. `CoreDeviceTest` checks the JNI and XML path on Android. These are representative tests, not a promise that every feature of every document is supported.

For a repeatable host JNI check, run `scripts/check-core` from the project root. It builds the host library, verifies pinned JVM tool dependencies, requires native loading before JUnit, and fails on skipped assumptions. The exact source and library hashes are recorded in `.tmp/core-check/receipt.json`. Default Gradle unit runs can skip JNI-only cases when no host library is configured; use this command or the Android device suite for native-reader evidence.
