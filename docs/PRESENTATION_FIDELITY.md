# Compact presentation preview: implemented boundary

This dependency-free reader is a bounded subset, not a PowerPoint/Impress rendering engine and not a replacement for full Office interoperability. It does not implement spreadsheet charts or recalculate workbooks. The user's request for full format features requires a separate engine/size/licensing decision.

## Supported metadata and visuals

- PPTX slide order and canvas size; text-run font family, finite 1–400 point sizes, bold, italic, underline, strikethrough, direct solid colors and paragraph alignment
- PPTX slide → layout → master placeholder geometry and text defaults, theme Latin fonts and scheme colors, common alpha/tint/shade/luminance color transforms, solid slide backgrounds
- PPTX embedded PNG/JPEG pictures from the slide, layout and master, plus unrotated solid rectangular fills. Shared source z-order is exposed for text, rectangles and images
- ODP/FODP inherited paragraph/span/graphic styles, solid drawing-page backgrounds and basic rectangle/text-frame fills; embedded package PNG/JPEG or flat `office:binary-data` images
- Body text edits retain original DOM targets and b# IDs. Existing blocks are not reordered, added or removed by preview enrichment. Assets, styles and unmodified package members remain preserved; an unchanged export returns the exact imported bytes

## Deliberate omissions

Cropped/tiled/mirrored/rotated/group-transformed images, non-rectangular masks and image effects are omitted rather than shown incorrectly. A known safe image rectangle gets an omission marker; unknown/transformed coordinates get a contextual warning only. External resources are never fetched. SVG, WMF/EMF, animated PNG, embedded fonts, arbitrary scripts, charts, SmartArt, tables, videos, transitions and animations are not rendered. Non-rectangular vector graphics, gradients, borders/shadows, decorative master text, bullets and numbering, vertical text, exact font metrics and full text fitting are not implemented. ODF master-page graphics are not reconstructed. Host fonts can substitute requested fonts and change wrapping.

A preview is not pixel-identical to Office. The UI must keep its geometry guards and show an outline/omission details when the supported layout cannot be rendered reliably. Source content remains preserved during text edits; the application must not call omitted content fully supported.

## Bounds and data safety

- Existing import limits: 32 MiB compressed file, 64 MiB package expansion, 8 MiB each package member, 100,000 XML nodes across the package, 128 XML nesting levels and 12,000 editable/extracted blocks
- Raster source budget: 2 MiB per image, 8 MiB unique accepted image bytes, dimensions at most 8192×8192, 16 Mi pixels per image and 32 Mi pixels aggregate, 512 placements
- PNG signature, bounded chunk framing/CRC and required IDAT/IEND inspected; JPEG bounded segments/SOF inspected. Malformed headers, unsupported image types, non-default EXIF rotation and dimension bombs are rejected before platform decoding
- The Android decoder must independently validate MIME, dimensions and actual decode success, sample to its smaller in-memory budget and decode visible/neighbor slide images off the UI thread. Core metadata inspection is not a substitute for that check
- A shared session raster store caps cached/leased pixels plus decode reservations at 8,000,000 pixels. Equal image content reuses a decode; pinch scales stable rasters rather than restarting decoding. A page retains leases until disposal; only unpublished canceled results recycle. This is not a strict Android native-live-memory ceiling, because already published render-thread references can outlive eviction
- Derived graphics are globally bounded to 1,024 images/fills/omissions and 4,096 shape/enrichment visits. Shared master/layout scans and geometry/theme lookups are cached; exhausted slides retain source text and IDs as a labeled outline
- Relationships are package-local only; schemes, authorities, queries, fragments, escape paths, ambiguous percent-encoded path segments and duplicate relation IDs fail closed or become explicit external-resource omissions
- Theme/layout lookup depth is structurally fixed. ODF style inheritance is capped at 16 and cached; placeholder/z-order and rejected raster lookups are cached
- No java.awt, native renderer, parser downloaded at runtime, network image loading, script evaluation or SVG execution is used

## Reproducible tests and fixtures

`PresentationFidelityTest` covers explicit run styles, source z-order, theme/layout inheritance, per-image and aggregate pixel budgets, unsafe/external/duplicate relationships, PNG/JPEG checks, crop/rotation omission semantics, ODP/FODP style and image preservation, byte-identical unchanged exports and RTF engine adaptation.

`scripts/generate-presentation-fixtures.py` produces the synthetic `presentation-fidelity.pptx`, `.odp`, `.fodp` and `presentation-raster.jpg` fixtures. Their text and procedural gradient artwork are authored for this repository, contain no personal information and use the repository license. Pillow is a fixture regeneration tool only and is not an APK/runtime dependency. Slide 2 of the PPTX deliberately uses cropping and must show an omission marker.
