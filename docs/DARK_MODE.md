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
| Auto | Uses available image placement information and bounded raster analysis to adapt paper and ink while protecting photographs. |
| Keep | Protects image-like regions; paper and ink still follow the theme. |
| Dim | Softens image-like regions without inverting their detail or changing alpha. |
| Recolor | Applies the page treatment across raster content. |

Original bypasses these policies; Invert flips all RGB values. Auto uses complete image-placement information where a reader can provide it: an empty list means no visible raster images, while unsupported or incomplete information remains unknown. Up to 64 normalized rectangular placements are accepted. Known image areas preserve their already-composited pixels before tile or backdrop guesses; page text and paper outside them follow the selected palette. The PDF metadata path supports direct unmasked JPEG and Flate images with ordinary constant alpha, including ASCII85–Flate page content. Soft masks, clipping, image-bearing forms, unsupported geometry and incomplete metadata still fall back to raster analysis.

Some renderers blend image edges with the original paper before theming, which can leave a thin light rim on dark paper. Auto does not reconstruct those compositing layers.

A bounded scan check can adapt near-white paper and neutral ink inside a single known image when the whole image meets its confidence checks. If those checks fail, the known image stays unchanged, including full-page scans. This cannot recover document layers or perform OCR. Unknown flattened pages still use raster heuristics: up to 64 closed, flat plates keep consistent treatment across tile boundaries, while small or thin marks remain ink. Large rectangular glyphs and photographic detail can remain ambiguous; Original preserves exact source colors.

For an identified opaque flat colored panel with one colored ink/antialias family, Auto retains the panel and adjusts low-contrast ink using WCAG 2.2 relative luminance (linearized sRGB) to at least 4.5:1 endpoint contrast. A monotone 256-color palette strengthens weak antialias edges when their original coverage would reduce contrast, including the classifier’s small quantization tolerance. Known images and uncertain alpha stay untouched by this adjustment. The bounded palettes add about 64 KiB; contrast is calculated once per palette, not per rendered pixel. This is a raster heuristic, not a guarantee for arbitrary layered pages or photographs. Original and explicit image treatments retain their existing behavior.

Processing stays off the UI thread and checks cancellation between rows and tiles. The ordinary path uses a 64-row output buffer and bounded plate/band metadata. Known vector ink also uses an 80-row original-pixel context buffer and an optional packed image mask. Scan analysis is limited to 8 million pixels and 4 MiB of auxiliary storage, including its full-image mask; larger or uncertain images skip that analysis. Original returns the input bitmap; other modes produce a separate bitmap without modifying the source. Covers store bounded original PNGs with small, digest-bound image-region records. Incomplete cache pairs are discarded, and appearance changes reuse the original rather than creating another disk cache for each theme.

Selectable text uses semantic foreground, background and link colors. Invert uses a dark text palette; Android color emoji remain unchanged. Original text view is a light reading theme and cannot restore formatting discarded during extraction. Default text/background pairs meet 4.5:1 contrast; arbitrary raster documents may not.

Paper roughness ranges from 0 to 100 and defaults to zero. The slider commits when the gesture ends, avoiding a page render for every thumb movement. The value is saved per hash or with the Home defaults. Raster texture is deterministic and keeps image-preservation policies; it does not schedule animation frames.

App appearance is separate: System follows Android, Light uses light surfaces, Dark uses charcoal, and AMOLED uses a pure-black main canvas. The other app backgrounds use the static Reverb noise tile; AMOLED omits background grain. Foreground cards and controls use flat fills in every app theme. Changing app appearance does not rewrite document themes or recolor covers independently of the document defaults.

## References

- [WCAG 2.2 contrast and relative luminance](https://www.w3.org/TR/WCAG22/#dfn-relative-luminance): sRGB transfer function and luminance weights for identified ink/background contrast.

- [MJ PDF](https://gitlab.com/mudlej_android/mj_pdf_reader), inspected at `30cca2df37f65c19ff4bf31544fb8ec4e6bc9d2a`: PDF and thumbnail night mode use RGB inversion. Used as a behavioral reference; no source copied.
- [Dark Reader theme modes](https://darkreader.org/help/en/#theme-generation-modes), [role-aware colors](https://github.com/darkreader/darkreader/blob/main/src/inject/dynamic-theme/modify-colors.ts), and [image analysis](https://github.com/darkreader/darkreader/blob/main/src/inject/dynamic-theme/image.ts): references for separating content roles and preserving image-like areas. AllDoc does not bundle the extension or claim equivalent DOM/CSS analysis.

JVM and Android tests exercise palettes, known/unknown image regions, scan controls, partial tiles, alpha, ownership, cancellation and cold/warm previews. Exact-candidate device execution and actual rendered-image review are required for qualification; passing a parser or checking PNG validity alone is insufficient.
