# Document colors

System with Auto image treatment is the initial default. Reader changes follow the document hash; Home changes set defaults for new documents and the appearance of Library/Recents covers. Existing document choices remain unchanged. The API retains its Original default and historical image-preservation flag. Image treatment applies to graphics and scans inside supported documents; it does not add standalone image-file support.

| Mode | Treatment |
| --- | --- |
| System | Light or Dark, matching Android appearance. |
| Light | Light paper and dark ink; normalizes monochrome dark pages. |
| Dark | Dark paper and light ink, retaining some original hue. |
| Paper | Warm paper and dark brown ink, with optional static paper texture. |
| Original | Source page colors unchanged. |
| Contrast | Black paper and white ink, with gradual antialiasing. |
| Invert | Whole-page RGB inversion, including photos; alpha is unchanged. |

| Images | Treatment in adaptive modes |
| --- | --- |
| Auto | Adapts likely opaque, low-color backgrounds and keeps photographic detail. |
| Keep | Protects image-like regions; paper and ink still follow the theme. |
| Dim | Softens image-like regions without inverting their detail or changing alpha. |
| Recolor | Applies the page treatment across raster content. |

Original bypasses these policies; Invert flips all RGB values. Auto first recognizes up to 64 closed, flat plates in the original raster, so a uniform backdrop and its lettering keep the same treatment across tile boundaries. Small or thin neutral marks stay on the ordinary ink path. Other regions use small samples from 64-pixel tiles. These are raster heuristics, not document-object metadata, text layers or OCR: an unusually large rectangular glyph can resemble a panel, and scans or photographs can remain ambiguous.

Processing stays off the UI thread, checks cancellation between rows and tiles and uses a 64-row temporary pixel buffer. Auto adds one streaming scan with at most 32 disjoint candidates, a previous row, 64 accepted rectangles and up to three packed band masks; it does not retain a full-page pixel or region map. Original returns the input bitmap; other modes produce a separate bitmap without modifying the source. Covers keep bounded original disk images and color-treated worker copies, avoiding a separate disk cache for each appearance.

Selectable text uses semantic foreground, background and link colors. Invert uses a dark text palette; Android color emoji remain unchanged. Original text view is a light reading theme and cannot restore formatting discarded during extraction. Default text/background pairs meet 4.5:1 contrast; arbitrary raster documents may not.

Paper roughness ranges from 0 to 100 and defaults to zero. The slider commits when the gesture ends, avoiding a page render for every thumb movement. The value is saved per hash or with the Home defaults. Raster texture is deterministic and keeps image-preservation policies; it does not schedule animation frames.

App appearance is separate: System follows Android, Light uses light surfaces, Dark uses charcoal, and AMOLED uses a pure-black main canvas. AMOLED omits canvas grain while raised controls retain a subtle static texture. Changing app appearance does not rewrite document themes or recolor covers independently of the document defaults.

## References

- [MJ PDF](https://gitlab.com/mudlej_android/mj_pdf_reader), inspected at `30cca2df37f65c19ff4bf31544fb8ec4e6bc9d2a`: PDF and thumbnail night mode use RGB inversion. Used as a behavioral reference; no source copied.
- [Dark Reader theme modes](https://darkreader.org/help/en/#theme-generation-modes), [role-aware colors](https://github.com/darkreader/darkreader/blob/main/src/inject/dynamic-theme/modify-colors.ts), and [image analysis](https://github.com/darkreader/darkreader/blob/main/src/inject/dynamic-theme/image.ts): references for separating content roles and preserving image-like areas. AllDoc does not bundle the extension or claim equivalent DOM/CSS analysis.

JVM tests cover palette contrast, color/alpha behavior and classification. Android tests cover a mixed page, scanned content, partial tiles, ownership and cancellation. Device execution is required to qualify the bitmap adapter.
