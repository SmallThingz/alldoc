# Format capabilities

This file describes the current working tree. The immersive/RTF/presentation changes after 0.2.0 are **unreleased**; the preserved 0.2.0 APK does not contain them.

alldoc is a compact, offline document workspace. Its native views are useful reading and focused-editing tools, **not a full office layout or rendering engine**. DOC and DOCX, XLS and XLSX, and PPT and PPTX are different on-disk formats.

| Format | Display | Edit / export | Important boundaries |
|---|---|---|---|
| PDF | Native page rasterization, pagination and zoom on Android 8+ | Original-byte copy; native text-form filling on Android 15+ | AcroForm text fields only; no arbitrary page-text edits, OCR, search, annotations, page operations, checkbox/dropdown/signature editing, XFA or password entry |
| DOCX | Offline formatted preview; paragraphs, runs, tables, headers/footers/notes | Replace existing run text in a new package | Explicit page breaks are shown; pagination, fonts and complex shapes are approximate; enforced document editing protection is read-only |
| XLSX | Sparse native grid, actual workbook sheet names and relationship order; cell coordinates and merged-range metadata | Replace an existing non-formula cell with an inline string | Formulas and protected sheets are read-only; edits become text, with no calculation; raw numbers and cached formula values are shown; no blank-cell insertion or charts |
| PPTX | Continuous pages or single-slide view; supported text geometry, common text styles, solid rectangle fills and bounded PNG/JPEG images | Replace existing text runs; preserve the other package members | Approximate layout only; limited theme/layout/master text inheritance. Charts, SmartArt, groups, rotations, cropping, vector graphics, media and animation are not rendered faithfully. Unsupported positions fall back to a labeled text outline; omissions are reported |
| ODT / FODT | Paragraphs and headings in document order, including text inside spans | Replace existing text nodes; preserve surrounding elements | Structured reading, not page layout; automatic numbering, fields and advanced text constructs are incomplete |
| ODS / FODS | Named sheets, actual cell addresses, typed/cached values, merged-range and compact repeat metadata | Edit existing plain string cells, retaining cell attributes and style references | Numeric, formula, protected, repeated and rich-text cells are read-only; no recalculation; repeated ranges are represented once, without allocating millions of cells |
| ODP / FODP | Continuous pages or single-slide view; page grouping, common text styles, solid rectangle fills and bounded PNG/JPEG images | Replace existing text nodes within each page | Approximate layout; inherited styles and transform support are limited. Master graphics, charts, transitions and complex transforms remain unsupported; notes are not mixed into slide body text |
| XML / SVG | Inert source with well-formedness validation | Edit source in its original UTF-8 / BOM-detected UTF-16 encoding; validate before export | No schema validation, DTDs, external entities or resource resolution; SVG scripts never execute; an incompatible encoding declaration is rejected |
| HTML / HTM / CSS | Inert source | Edit original text encoding | HTML, scripts, styles and external resources are never executed or rendered |
| TXT / MD / CSV / TSV / LOG / JSON / YAML | Plain source | Edit original UTF-8 / BOM-detected UTF-16 encoding | CSV is not a grid, Markdown is not rendered, and formulas/code are not executed |
| DOC | Read-only OLE2 Word 97–2003 text via Apache POI HWPF | Original-byte copy only | No page layout, formatting, images, field reconstruction or legacy editing |
| XLS | Read-only OLE2/BIFF8 addressed raw cell text via Apache POI HSSF records | Original-byte copy only | Dates remain serial values, cached formula results may be stale; no formatting, formulas evaluated, charts or legacy editing |
| PPT | Read-only text grouped in current presentation slide order using the current persist directory | Original-byte copy only | Master placeholders and historical/deleted slides are excluded structurally; hidden current slides are included and labeled. No rendered layout, images, notes, animation or legacy editing |
| RTF | Safe body-text reading/editing with common run emphasis | Replace supported existing text while retaining untouched source byte ranges; edited output is reparsed | Existing styles are preserved, not editable. Fields/Unicode alternatives and ambiguous control regions are read-only; objects, protection, revisions and unsupported composite-font/HTML constructs can make the whole document read-only. No faithful pagination or image/table rendering |

## Source and package preservation

The source provider is never opened for write. Export creates a copy. An unchanged export returns the original bytes, including flat OpenDocument and source files. Edited exports are reparsed independently; a failed export cannot poison the open session.

Untouched ZIP members retain their uncompressed bytes. Edited XML parts are serialized and may have lexical changes. This is **not** a lossless layout or full office-roundtrip guarantee. Review edited exports in the originating application. ODF mimetype is written first and stored uncompressed; flat FODT/FODS/FODP remains XML rather than becoming ZIP.

OOXML sheet/slide lists determine order through their relationships. Orphan slide parts are not silently inserted. Missing, external, duplicate or ambiguous referenced slide/sheet relationships fail closed. If ordering metadata is entirely absent in a partial synthetic package, numbered-part fallback is reported in session warnings.

## Bounded processing

- 32 MiB file input; 64 MiB total expanded archive/text; 8 MiB per archive/XML part
- 3,000 archive entries; 12,000 content blocks; 100,000 XML nodes across the package; XML depth 128
- 2 MiB editable source text and per-edit text; 4 MiB combined edited characters
- Worksheet coordinates bounded to 1,048,576 rows and 16,384 columns; ODS repeats stay compact
- Expanded ODF spaces, editing-induced XML complexity and serialized output are also bounded

Documents outside these limits fail explicitly rather than presenting partially editable content. Known macro-enabled, signed, active-script and encrypted package structures are refused. These checks are a defensive parsing policy, not an antivirus scanner. Embedded binary assets are not executed.

Formatted DOCX preview is independent of the edit/export engine. It receives a normalized, validated ZIP copy; unchanged user exports still preserve the original bytes. The preview disables alternate HTML chunks, embedded fonts, active hyperlinks, file/content access, external resources, navigation and application JavaScript bridges.

## PDF form-copy boundaries

Text-form edits use Android 15's native PDF writer and do not rasterize the exported document. Read-only fields are omitted; any signature widget blocks filling, including unsigned signature fields. Detected `/ByteRange`, `/Sig` or `/DocTimeStamp` names (including PDF name escapes) also block form filling. A conservative `/Encrypt` marker check (including PDF name escapes) blocks form filling on encryption-marked files. These conservative checks deliberately may reject files containing those names in comments, strings or binary data; they are not a complete verifier for malformed, encrypted or digitally signed PDFs.

PDF form limits: 32 MiB input/output, 500 pages, 64 widgets/page, 256 edits, 4,096 characters/field and 65,536 aggregate edited characters. A fresh isolated renderer is used for export, and the staged PDF is reopened to verify page count and field values before the destination is written. Native form filling still requires real-device verification.

## Legacy extraction engine

The legitimate compact path is Apache POI's OLE2 infrastructure plus HWPF (DOC), HSSF records (XLS) and a bounded PPT text extractor. It does not require POI's large OOXML schema engine. The [official component map](https://poi.apache.org/components/index.html) documents these modules. Full HSLF slide rendering depends on desktop [Graphics2D APIs](https://poi.apache.org/apidocs/dev/org/apache/poi/hslf/usermodel/HSLFSheet.html), so it is unsuitable as an unmodified Android renderer.

An isolated POI 5.5.1 shrink experiment measured approximately 0.65 MiB compressed output for a small legacy extraction entry point. This is a feasibility measurement, not an integrated APK measurement or a runtime-compatibility guarantee. Full model extraction and convenient number-formatting helpers can still reach unavailable desktop graphics classes; those paths must not be hidden behind blanket warning suppression. The integrated adapter uses raw XLS records without formatting helpers, DOC text pieces without rendering, and validated PPT record boundaries before text decoding. Legacy import is limited to 8 MiB, 2,048 OLE entries, depth 64, 100,000 records and 1 MiB extracted characters. Known macro, signature and encryption structures are rejected. The library can allocate DOC/SST models before output guards; this adapter is not a hard aggregate-memory sandbox. All legacy sessions are read-only, and exported copies retain the original bytes.

Focused JVM tests include loading each format while desktop AWT, Swing and ImageIO classes are deliberately unavailable. This checks the selected extraction paths; it does not replace real Android device verification. No proprietary WPS component is used.

## Interoperability corpus

`scripts/generate-fixtures.py` produces synthetic DOCX, XLSX, PPTX, ODT, ODS, ODP, FODT, XML, TXT and PDF examples. Separate legacy DOC/XLS/PPT fixtures exercise the bounded read-only adapter (see their provenance file). The PPTX intentionally has presentation order that differs from numeric part names. Regression tests cover relationship order, sparse addresses, repeat bounds, protection, whitespace, encoding declarations, hostile XML/archive inputs, independent exports, preserved assets and edit/reopen roundtrips.

Model semantics are based on [Microsoft's PresentationML slide documentation](https://learn.microsoft.com/en-us/office/open-xml/presentation/working-with-presentation-slides) and the [OASIS OpenDocument 1.3 schema](https://docs.oasis-open.org/office/OpenDocument/v1.3/OpenDocument-v1.3-part3-schema.html).

## Full document-engine requirement

These compact fallbacks do **not** fulfill full Word/Excel/PowerPoint functionality. In particular, spreadsheet charts, formula editing/recalculation, full formatting, object insertion and complex layout are missing. The mature offline engine assessment is in `research/android-office-engine-feasibility-2026-10-03.md`. No verified compact full-featured native interactive engine has been integrated. A document-processing library or optional engine download must not be presented as solving interactive editing or total installed footprint.

## Unexecuted real-world corpus

`corpus/manifest.json` records 50 distinct upstream regression fixtures (820,665 bytes), pinned revisions, SHA-256 values, producer evidence and repository license notices. Eighteen files contain recorded producer metadata; 32 are unknown/not embedded. Only fixture/license hashes were verified. Harness execution stopped during dependency lookup before compilation, parser execution, mutations or export/reopen. No corpus operation is a pass; fixture-by-fixture redistribution review is incomplete. Synthetic tests are separate and do not substitute for real-file coverage.
