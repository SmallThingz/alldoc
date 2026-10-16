# Office examples

`../generate-office-examples.py` authors the files in
`app/src/main/assets/examples/office`. All prose, data, diagrams and PDF content
were created for AllDoc; there is no downloaded document content or external
asset licence. The files use the project's licence.

Regenerate from the repository root with Python 3 and a development installation
of LibreOffice. LibreOffice uses an isolated profile under `.tmp/examples-office`;
it is not an application dependency. Conversion logs and payload hashes go to
`.tmp/examples-office/generation.json`. The catalog input is
`scripts/fixtures/examples-office.json`.

- PDF has three pages; presentations have five slides and speaker notes.
- OpenDocument packages have a stored first `mimetype` member and a manifest;
  flat variants use the corresponding OpenDocument XML body.
- OOXML is exported from the authored flat documents. Template, slideshow and
  macro-capable examples use their actual package content types. Macro-capable
  examples contain no macros. Author identity and timestamps are normalized.
- DOC, DOT, XLS, PPT, PPS and POT are actual binary LibreOffice exports, not
  renamed ZIP files. DOT is the Word template example.
- EPUB is a small EPUB 3 book with a container, package, navigation and chapter.
- Pages, Numbers and Keynote entries are **saved-preview demonstrations**:
  packages containing a real `QuickLook/Preview.pdf`. They deliberately contain
  no editable Apple document representation and are labelled accordingly. They
  exercise AllDoc's advertised embedded-preview support, not full iWork parsing.

The generator checks ZIP CRCs and XML well-formedness. These structural checks
complement, rather than replace, opening and rendering every entry through the
application's document engine.
