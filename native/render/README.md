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
- XLSX/XLSM/XLTX/XLTM: worksheet print pages with cached cell values.

Inputs are limited to 16 MiB, expanded packages to 32 MiB, output to 1400 pixels
on the longer edge and 128 pages. External resources, macros, active content and
presentation playback are not executed. Font substitution and unimplemented
format features can change appearance. Structural reading remains a separate
engine; a page image is not a guarantee of complete text extraction.

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
