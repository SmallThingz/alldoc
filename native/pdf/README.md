# PDF text and contents worker

Android renders PDFs with its bundled PDFium through `PdfRenderer`. Android 15 adds native text extraction and password parameters. On Android 9–14, this worker provides text and page-structured JSON without a second rendering engine.

`pdf-extract` 0.12.1 (MIT), backed by `lopdf` 0.42.0 (MIT), runs in a disposable child through `liballrun.so`, which terminates the worker if its owning app process dies. The caller enforces cancellation, a 15-second deadline, 128 MiB input and 16 MiB wire limits. The worker caps text at 2,000,000 UTF-8 bytes and 2,000 pages. Truncation is reported. Reading order is approximate; OCR is not included.

Build from the project root: `python native/build_pdf.py arm64-v8a x86_64`.

PDF Contents reads actual outline objects lazily through the existing `lopdf` dependency on every supported Android version. Local page destinations, named destinations, hierarchy and duplicate titles are preserved. External actions are ignored; headings are not inferred. Traversal is limited to 4,096 entries, 8,192 nodes and depth 32, with titles capped at 256 Unicode scalars. Passwords are passed to the disposable child through stdin, never command-line arguments or cache files. Contents parsing uses an independent descriptor so it does not hold the platform page-rendering lock.

PDF notes read the plain text of markup annotations lazily through the same descriptor, password and worker limits. Author, annotation kind and page are retained. Popups, links, actions and file attachments are excluded. Malformed or empty annotations are skipped; rich-text-only notes are not interpreted. Results cap at 4,096 notes, 20,000 visited annotations, 2,000 pages and 2,000,000 UTF-8 bytes, with 16 KiB per note. Limit omissions are marked as truncated.

The packaged executable is named `liballpdf.so` so Android installs it in the native-library directory; it is a PIE process, not JNI. Cargo.lock pins upstream dependencies. Notices and dependency provenance are adjacent.

MJ PDF was inspected as a behavior reference, not copied: https://gitlab.com/mudlej_android/mj_pdf_reader at `30cca2df37f65c19ff4bf31544fb8ec4e6bc9d2a`. Its PDFView applies whole-page RGB inversion. AllDoc's theme engine is separate.

Regenerate the Android-only notices with `python native/collect_pdf_notices.py` after fetching locked Cargo dependencies. The collector follows both shipped ABI dependency graphs and verifies exact-version attribution. Three upstream MIT packages publish an SPDX declaration without a standalone license file; their bundled notices explicitly preserve that distinction, author attribution and standard MIT terms.
