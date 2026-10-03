# Bounded RTF support

## Capability and fidelity boundary

`RtfDocument` is a small, dependency-free RTF body-text parser and focused editor. It does not load desktop AWT/Swing, decode embedded objects, open links, resolve resources, execute fields, or turn HTML into executable content. It is not a full RTF page-layout engine.

The helper exposes document-order runs with paragraph IDs, bold/italic/underline/strike metadata, and half-point font sizes. Runs retain their original formatting boundaries. Tabs, soft line breaks, paragraph breaks, common punctuation controls, escaped braces/backslashes, Unicode UTF-16 escape pairs, and supported code pages are decoded. Table cells have read-only tab separator runs; rows become paragraph boundaries. The native view can render the supported emphasis metadata using platform fonts.

Not reproduced: original font faces, colours, alignment, pagination, page dimensions, table geometry, automatic numbering, list layout, images, shapes, equations, headers/footers, footnotes and annotations. Blank-paragraph spacing is not represented by the run-only native view. Hidden/deleted content is omitted. The warnings distinguish these preview omissions from byte-preserving editing.

- Font-table code-page overrides and scoped document code-page changes are respected
- Recognized Windows 1250–1258, selected OEM/Macintosh/East Asian code pages and UTF-8 are decoded only when the platform supplies the named charset
- Unknown code pages, symbol-font character sets and invalid encoded byte sequences fail explicitly rather than guessing characters
- Escaped multibyte sequences are decoded together; raw multibyte encodings that conflict with RTF structural bytes may be rejected
- Unknown control words are ignored for preview and preserved in the source; unknown `\*` destinations are omitted as complete, balanced groups
- Unicode/ANSI `\upr` alternatives use the Unicode `\*\ud` branch and remain read-only, avoiding stale alternate copies after edits
- Field instructions are never displayed or evaluated; cached field-result runs are read-only and may be stale
- Composite character-set/font selection is approximate and makes the document read-only
- Embedded-object, editing-protection, tracked-revision and HTML-encapsulated documents are read-only; their unchanged copies still contain the original structures

This is not an antivirus scanner or a general-purpose RTF sanitizer. An unchanged copy is the original file. A focused edited copy retains all bytes outside the edited text spans, including omitted images and unknown destinations. Use a trusted application when reviewing those structures.

## Export safety

Opening clones the input. Empty or text-identical changes return an independent byte-identical copy. Edits target existing visible run IDs only; read-only, unknown and null targets fail. Each export starts from the immutable original.

An edited text span is replaced with a locally scoped Unicode RTF group. Braces and backslashes are escaped, non-ASCII characters use signed UTF-16 `\u` controls with explicit `\uc1` fallback, tabs use `\tab`, and LF uses a soft `\line` break. Existing paragraph and cell controls remain untouched. CR/control-character edits and unpaired surrogates are refused. Formatting, font tables, binary payloads, unknown groups and source line wrapping outside the edited spans retain their exact bytes.

The full exported bytes are independently parsed and the complete visible text is compared against the requested result before returning output. Failed exports cannot change the open document or the original. Full layout round-trip fidelity is not promised.

## Limits

- 8 MiB input and serialized output
- 1 Mi UTF-16 code units of visible text and aggregate changed text
- 12,000 native text/separator runs; nesting depth 128
- 250,000 parsed tokens; 2,048 font definitions
- 32-character control words; checked signed 32-bit numeric parameters
- Unicode fallback count 0–255; checked binary payload lengths, including inside skipped destinations and Unicode fallback
- Iterative group stack, no recursive document parsing and no image allocation

Out-of-budget or malformed input fails instead of yielding partially editable content. Skipped groups still have their braces, controls and binary lengths checked.

## Verification

`RtfDocumentTest` is runnable with the existing core JVM test script. Dedicated tests cover unchanged-byte copies, input/output isolation, focused byte preservation, independent exports, Unicode surrogate pairs, fallback counting/scoping, code-page changes and font overrides, multibyte escapes, destination omission, inert fields/HTML, objects/protection/revision refusal, style state restoration, table boundaries, malformed data, all main bounds, randomized malformed input and randomized edit/reopen escaping.

`fixtures/formatted.rtf` is an original synthetic document with declared provenance in `fixtures/rtf-provenance.txt`. The small picture payload is intentionally synthetic, not a valid decoded image. No third-party binary fixture or dependency was introduced.

At implementation time, all **32 focused RTF JVM tests passed** with JDK 21 and a 256 MiB heap (about 0.2 seconds). An additional bounded offline check used the preinstalled LibreOffice Writer importer to reopen an edited synthetic fixture and export plain text. Its full visible text agreed with the helper's output, ignoring the final newline, including escaped braces/backslash, CJK, emoji, a soft line break, cp1251/cp1252 text and the cached field result. LibreOffice exited successfully; its fontconfig cache warnings did not prevent text conversion. This is one synthetic text interoperability check, not a rendering certification.

Focused JVM checks do not replace Android charset/device testing, real-world interoperability with external rich-text applications, or visual device QA. Full app integration/build results belong in the release verification log after they have actually run.

## Primary format references

- [Microsoft RTF syntax](https://learn.microsoft.com/en-us/previous-versions/office/developer/office2000/aa140284(v=office.10)): control syntax, delimiters, group scoping and escaped literal characters
- [Microsoft RTF reader conventions](https://learn.microsoft.com/en-us/previous-versions/office/developer/office2000/aa140286(v=office.10)): state-stack parsing, destinations and ignorable groups
- [Microsoft MS-OXRTFEX plain-text extraction](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxrtfex/205e1abf-b794-4fd0-b1e4-5210882233ab): current-font code-page handling, Unicode and text separators
- [Microsoft MS-OXRTFEX encapsulation](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxrtfex/fc779a72-0af3-4d17-8a85-e0a6b08ed37b): unknown controls and ignorable destination handling
- [Microsoft RTF specification reference](https://go.microsoft.com/fwlink/?LinkId=120924): full specification, Unicode fallback and binary token rules (Microsoft's archive redirect was unavailable to the research tool during this implementation)

- [Microsoft RTF 1.7 specification, archived by the latex2rtf project](https://latex2rtf.sourceforge.net/RTF-Spec-1.7.pdf): Microsoft-authored Unicode fallback, signed UTF-16 and binary-token rules; consulted as an archive of the primary specification, not as a third-party parser implementation

## Immersive UI integration

Supported paragraphs open directly editable, without a separate read-only Text mode. Paragraphs containing immutable fields stay selectable/read-only as a whole in the compact UI rather than duplicating their editable fragments below the text. Engine-level safe-span preservation remains unchanged. Actual text input hides the app overlay without removing focus; explicit Save copy protects the source.
