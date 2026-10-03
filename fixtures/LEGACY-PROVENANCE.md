# Legacy Office fixtures

- `legacy-basic.doc`: Apache POI 5.5.1 `test-data/document/simple.doc`, Apache-2.0.
  Source: https://raw.githubusercontent.com/apache/poi/REL_5_5_1/test-data/document/simple.doc
- `legacy-basic.xls`: generated using Apache POI 5.5.1 HSSFWorkbook with worksheet Forecast,
  row 1 values Hello XLS, 42.25, and formula B1*2. The stored formula cache is intentionally
  not recalculated, verifying the preview labels cached results as potentially stale.
- `legacy-basic.ppt`: generated using Apache POI 5.5.1 HSLFSlideShow with one slide and
  a text box containing Hello PPT. POI's default template also contains master placeholders,
  exercising the distinction between real slide text and template master text.

The XLS/PPT fixture content was created for this repository. No Office rendering or editing
fidelity is implied by these small smoke fixtures.

## Current-slide PPT regression fixture

`legacy-current-slides.ppt` was created for this repository with Apache POI 5.5.1 by
`GenerateLegacyPptFixture.java`. SHA-256:
`c728a9e40f55796bbedee7070b8e67681ba5fa03bef3880178f1347c6ba49df9`.

The generator uses POI's real default presentation/master records, writes an initial
four-slide presentation, then appends a newer edit with a revised title, changed slide
order, and one deleted slide. The original records remain physically present. The
current presentation has exactly three slides:

1. `Hidden current slide`, marked hidden and placed first. Its text lives in the
   current document's SlideListWithText, referenced by OutlineTextRefAtom from an
   OfficeArtClientTextbox with recVer 0. This also tests that shared text is not repeated
2. `CURRENT REVISION: café, Ελληνικά, 日本語`, followed by a two-line ordinary text box
3. `Click to edit Master title style`, intentional, legitimate user slide content
   which must survive even though it matches a common master placeholder phrase

The underlying stream also retains `STALE REVISION: superseded title`,
`DELETED SLIDE: no longer in current list`, and real master placeholders including
`Click to edit Master text styles`. None belongs in the current-slide preview.
The generator independently reopens the completed file with POI's full HSLF model to
verify the three-slide order, hidden flag, and titles. Full HSLF/AWT is used only by
this development-time generator, never by the Android extractor or fixture tests.

Regenerate from the repository root with a desktop JDK and the existing POI 5.5.1
runtime classpath:

```sh
javac -cp "$POI_CP" -d /tmp/alldoc-fixtures fixtures/GenerateLegacyPptFixture.java
java -Xmx256m -cp "/tmp/alldoc-fixtures:$POI_CP" GenerateLegacyPptFixture
```

The extractor follows the current-edit persist directory and current slide list. It
uses bounded record headers/relationships and POI atom decoding, without constructing
the full slide model. Text-box order is record order, not visual reading order;
master-derived text, notes, layout, images, and animations are intentionally omitted.
Malformed or ambiguous references are refused rather than falling back to historical
whole-stream extraction. The 8 MiB input, global text, record, and depth limits remain.

Format references:
- [MS-PPT 2.1.2: live persist objects and edit resolution](https://learn.microsoft.com/en-us/openspecs/office_file_formats/ms-ppt/1fc22d56-28f9-4818-bd45-67c2bf721ccf)
- [MS-PPT 2.4.14.3: current slide list and placeholder text](https://learn.microsoft.com/en-us/openspecs/office_file_formats/ms-ppt/307e6d12-7304-47a8-acbd-3e7b8041ad3c)
- Apache POI 5.5.1 source: `UserEditAtom`, `PersistPtrHolder`, `SlideListWithText`,
  `TextBytesAtom`, `TextCharsAtom`, `PPDrawing`, and `SSSlideInfoAtom`
