# Office rendering

AllDoc links only the parser, layout and raster crates from Storytold's WordCraft,
DeckCraft and GridCraft. Embedded document images retain the engines' codecs.
No Craft desktop UI, brand marks, network client or automation server is shipped.
The Rust executables run behind `liballrun.so`, with memory limits, parent-death termination and a cancellable
15-second caller deadline. Android's platform renderer handles PDFs separately.

## Build

```sh
git submodule update --init --recursive
python native/render/build.py --abis arm64-v8a x86_64
```

The script checks the exact clean submodule pins, projects the required files into
ignored `native/render/vendor`, then applies the patches in `patches/`. Upstream
working trees are never modified. `projection.json` identifies every included file.
`--host` builds a Linux diagnostic executable; `--prepare-only` checks projection.
Cargo's lockfile pins transitive dependencies. Gradle must retain
`useLegacyPackaging = true` and `extractNativeLibs = true` for these executables.

## Upstream updates

Fetch or update a submodule, rebase its mobile patch, update the pin and projection
manifest, then rebuild both ABIs and rerun document fixtures. An update with a
changed pin fails closed until its patch has been reviewed; changing a remote
branch does not silently change the reader installed on a device.

## Coverage

- DOCX/DOCM/DOTX/DOTM: best-effort Word page layout.
- PPTX/PPTM/PPSX/PPSM/POTX/POTM: static best-effort slide rendering.
- XLSX/XLSM/XLTX/XLTM: worksheet viewports and separate print pages with cached cell values.

Inputs are limited to 16 MiB, expanded packages to 32 MiB, output to 2,800 pixels
on the longer edge, 8 million pixels and 1,024 pages. The reader keeps a 1,400-pixel
base tier and requests the larger tier for detail; embedded raster images cannot
gain detail beyond their source resolution. Further zoom enlarges the bounded raster,
not an unlimited vector surface. Encoded page caching remains bounded
to three entries and 4 MiB. External resources, macros, active content and
presentation playback are not executed. Font substitution and unimplemented
format features can change appearance. Structural reading remains a separate
engine; a page image is not a guarantee of complete text extraction. Worksheet admission is bounded
at 131,072 cells across the workbook, 16,384 rows and 512 columns. Worksheet XML
parts may reach 8 MiB, with at most 524,288 elements and 2 MiB text across those
parts. Other XML parts retain the 2 MiB part and 512 KiB text budgets; total
structure is capped at 262,144 elements for presentations and 64,000 for Word
and spreadsheet metadata. Every uncached page request still imports and lays out the
document; admission is not a guarantee of interactive speed on every device.

Native pages can also report up to 64 complete opaque rectangular image placements
for Auto appearance. Unsupported alpha, geometry or incomplete bounds remain
unknown; an empty list means no raster images. These hints do not change Original
rendering or promise object-level recovery from a flattened scan.

## Stress qualification

Private workloads include a 240-slide photo deck, an independently Writer-readable
240-section Word document, a 120,000-cell workbook and smaller layered/styled
controls. They add no APK assets. Qualification must inspect actual first, middle
and last pages, complete page counts and semantic excerpt warnings; successful
exports alone do not establish visual fidelity or complete text extraction.
Shared-host runs are correctness evidence. Speed or peak-memory claims require
separate measurements against the exact final binaries.

## Provenance

The bounded adapter, memory-limit launcher, Android font discovery and raster-only
mobile patches were extracted from the user's ZFM project. Source attribution
and original third-party license texts are retained under `notices/`; Craft code
is used under its MIT option. `zfm-provenance.json` records the original selected
source hashes, including components since removed from the active build.
`projection.json` and `core/Cargo.lock` define the current dependency set.
`dependencies-upstream.json` records the resolved Android dependencies and bundled notices.
Current executable hashes and submodule pins are emitted into
`.tmp/native/render/receipt-<abi>.json` during each build.


## Worksheet viewports

`liballrender.so --sheets SNAPSHOT` retains one parsed immutable XLSX-family workbook until its owner closes the worker. Its stdin accepts bounded JSON lines; stdout uses `ZSG1` response frames with a 20-byte header (magic, kind, request ID, JSON length, PNG length; integers little-endian). Startup emits workbook metadata with ID 0. `sheet` returns active-sheet row/column edges; `render` returns a translated 2D viewport, and `close` exits. This does not change the existing one-shot print-page protocol. The caller retains the existing memory-limit launcher and enforces a 15-second startup/request deadline.

Viewports reuse GridCraft cell styling, merged cells, wrapping, charts, shapes and image drawing. Gridlines follow the sheet view preference, independently of print settings. The worker uses cached values/reference ranges for charts and sparklines. Formula-only conditional formats and unsupported chart expressions are omitted; no recalculation, macros or content networking occurs. Conditional formatting statistics are sparse and reused while the active sheet remains selected.

The bounded blank grid supports 16,384 rows and 512 columns, including space beyond the used range; headers receive exact variable-size/hidden-line edges. Raster output remains 2,800 pixels per axis and 8 million pixels. Scale 0.0625–32 changes which region is rendered, without creating a whole-sheet bitmap. Only one active sheet's geometry/statistics is retained. Authored frozen panes and right-to-left sheet direction are reported as metadata; content is not yet frozen or mirrored by this viewport renderer. RTL text still uses the font shaper.

Pinned GridCraft has no ODS or legacy XLS visual importer. Those formats may expose existing saved cell coordinates as a structured grid, with their explicit style/chart/excerpt limitations; they do not have XLSX visual-grid fidelity. Apple Numbers documents remain limited to their embedded previews. Reading success is not a claim that unsupported graphs were rendered.
