# Edge-case evidence and gaps

This is a scope ledger, not an “all files pass” claim. Final counts and artifact verification are recorded in VERIFICATION.md.

| Area | Exercised in regular local tests | Not established |
|---|---|---|
| Source text / XML / SVG | UTF-8 and BOM-detected UTF-16, CRLF, Unicode/bidi, escaping, declaration mismatch, malformed-save refusal, inert markup, DOCTYPE/entities rejected, original-byte unchanged export | Every producer/encoding; schemas; real device file providers |
| DOCX / ODT / flat ODF | Existing-text edits, whitespace, part order/IDs, protected/active structures, archive limits, unchanged members, edit/export/reopen | Full page layout, every OOXML/ODF feature/version, current WebView rendered pixels |
| XLSX / ODS / flat sheets | Shared-string isolation, actual sheet relationships, sparse addresses, repeat/merge bounds, protected/formula refusal, immutable formula/assets, text edit/export/reopen | Charts rendered or edited, formula recalculation, numeric formatting, full spreadsheet editing |
| PPTX / ODP / flat slides | Relationship order, source-preserving text edits, bounded styles/images/fills, inherited font/paragraph rules, grouped/unsupported geometry, byte/pixel/derived-object budgets, shared-master expansion, omission warnings, edit/export/reopen | Full charts/SmartArt/masters, media, animation, exact typography, every transform or producer |
| RTF | 32 parser tests: Unicode surrogate/fallback/codepage handling, safe-span edits, protected/ambiguous structures, unknown-destination byte preservation, malformed/size limits, export/reopen; native direct-edit Unicode roundtrip | Faithful page/table/image layout; every RTF control/version; rich style editing |
| DOC / XLS / PPT | Bounded read-only extraction, no desktop graphics classes, encryption/macro/signature refusal, malformed input, current PPT persist chain/order and master/historical exclusion, unchanged copies | Faithful rendering or editing; broad producer/version compatibility |
| PDF | Native-interface/model validation, bounded fields/pages, escaped protection markers, unsigned/signed/encrypted/XFA restrictions, codec tests and isolated-export staging code | Actual Android15+ fill/save/reopen, rendering on device, password entry, general page-text editing |
| Archive / resource safety | ZIP/path/size/XML node/depth bounds, repeated cell/shape expansion, image signature/dimensions, decode reservations, shared cache/lease cancellation tests | Hard native-RSS sandbox; physical-device low-memory behavior; complete malware detection |
| Data lifecycle | Provider export/cancellation/failure simulations, unchanged original provider access, atomic recovery, real 0.1.1 draft fixtures, retained recovery archives, saved picker destination, global discard, repeated cell edits and navigation | Real disk-full/revoked-provider permutations, killed Android processes/device rotation/OS variants beyond modeled tests |
| Immersive interaction | Non-consuming actual Compose taps, caret focus/typing, slop, double tap, pinch, continuous scroll, pager swipe, stopping real source fling, selection-dismiss tap, keyboard controls, unchanged overlay viewport bounds, 200% font, dark and simulated IME insets | Real IME/TalkBack/device timing; API35 text long-press/selection-handle execution is explicitly skipped due Robolectric native Magnifier null-Surface failure |

## Real-world corpus status

The newly staged 50-file upstream corpus has **zero observed open/render/edit/export/reopen results**. Hashes/provenance were checked; harness execution stopped before compilation/parser execution. Intended outcomes in the manifest are not test results. Existing regular fixtures and generated interaction fixtures are separate. No corpus mutation or minimization run completed, and per-file redistribution review is unfinished.

## Full-engine boundary

A compact fallback is not a complete Office engine. Formulas, charts, complex layout and feature-level parity remain unfulfilled. The mature-engine feasibility report records package-size and licensing/build constraints without treating processing libraries, server WebViews or optional downloads as a proven compact offline editor.
