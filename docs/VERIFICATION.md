# Verification log

## 2026-10-03 version 0.2.0 redesign and format expansion

Final-source verification:
- **135 tests passed**, zero failures/errors: 62 document-engine, 11 legacy-engine, 11 Android safety, 22 workspace UI, 20 PDF codec/protection, 4 recovery-upgrade, 3 slide-geometry and 2 PDF-form UI-model tests
- Full `lintDebug`: **0 errors, 9 warnings**; final minified preview assembly and lint-vital passed
- APK: **2,060,250 bytes (1.96 MiB)**; expanded ZIP payload 4,615,044 bytes, not installed footprint
- VersionCode 3, `0.2.0-preview`, `org.smallthingz.alldoc.preview`
- APK v2 signature and 16 KiB ZIP alignment passed; signing certificate is identical to 0.1.1
- SHA-256: `5a8ad0ac526a0a171c82594cf807905fcc1df45fece2dce257f2c5b7c366a38c`
- Certificate SHA-256: `8765a087bfa5c0bf0af4440dcbb394bfc4589b90ce8bc957333c6940a89c92c6`

The nine lint warnings cover targeting API36 rather than the latest platform, the pre-API31 applicability of extraction rules (backup remains disabled), six optional KTX suggestions, and a WebView fallback-label localization suggestion. No baseline or suppression was added for errors. One initial assembly daemon disappeared at concurrent R8/lint-vital; a serialized clean process completed, and the final rebuild passed after the last layout cleanup.

### Design and second review

A distinct native library/workspace redesign was reviewed from actual Robolectric native-graphics captures, including mixed formats, long filenames, search/filter/no-results, light/dark, phone/tablet, 320dp/200% font, simulated IME insets, selected-cell editing, ODS, PPTX/ODP selected-slide editing, XML source and export failure. Final error-state tests wait for a settled frame and assert adjacent paragraph bounds do not overlap. Fresh evidence is under `docs/evidence/v0.2/`; older root PNGs are historical.

Independent visual review passed the native UI's main usability gate after iterations. Independent safety review found and verified fixes for oversized slide geometry, repeated ODS range visibility/empty tabs, busy export controls, failed replacement of a PDF during form loading, and upgrade recovery. A final read-only review reported no remaining blockers in those fixes.

### Upgrade recovery and original safety

Recovery tests use the actual 0.1.1 engine and original XLSX fixture to ground old positional IDs. They prove old draft changes are not applied to new block order. Earlier originals and all draft JSON/text are archived and verified before new parsing, including an original accepted by 0.1.1 but rejected by the new relationship checks. Tests cover corruption of an existing archive, archive survival after ordinary recovery cleanup, and the visible ZIP-export action through saved-state restoration of the destination picker.

Modern/open-format tests cover real synthetic PPTX/ODF corpus, relationship order, sparse cells/repeats, protected content, encoding and XML declarations, malformed-save refusal, XML/SVG inertness, hostile XML/archive inputs, immutable assets, and edit/export/reopen. Legacy tests exercise actual DOC/XLS/PPT files, raw typed XLS records, absence of desktop-graphics dependencies on exercised paths, byte-identical copies, macro/signature/encryption refusal (including real default-password encrypted XLS), malformed inputs and size limits.

### Runtime boundaries

No emulator or physical Android device was available. Native PDF AcroForm write/reopen, API26 behavior, device storage/install footprint, low-memory behavior, real IME and TalkBack remain unverified. PDF form panel screenshots are explicitly UI-model tests, not screenshots of a native form roundtrip. PDF fixtures have been independently inspected with pypdf; the code reopens staged native output, but that platform path still needs Android15+ device QA.

DOCX formatted WebView rendering remains visually unverified. The local Chromium process failed to create its required Unix socket. The supported cloud browser's one HTTP attempt to the synthetic self-contained fixture at `http://127.0.0.1:8766/` returned `net::ERR_BLOCKED_BY_CLIENT`, followed by an explicit URL-policy rejection; the attempt stopped. No workaround bypassed that restriction. Native text-canvas captures and WebView resource-policy tests are not evidence of formatted WebView fidelity.

Legacy Office is a bounded read-only extraction fallback, not layout rendering or editing. PPT may contain master/older text; XLS shows raw addressed values. Modern slides omit images/themes/animations; spreadsheet formulas are not recalculated. RTF remains unsupported. See FORMATS.md for per-format capability boundaries.

## 2026-10-02 version 0.1.1 follow-up

- Full suite: **68 passed** (41 engine, 11 Android safety/recovery, 16 UI), zero failures/errors
- Full lint: **0 errors, 8 warnings**; fresh minified preview assembly and lint-vital passed
- APK remains **1,433,399 bytes**; expanded payload 2,702,081 bytes
- VersionCode 2, same package and signing certificate as 0.1.0; signature and 16 KiB ZIP alignment verified
- SHA-256: `817a9df0788b9f8cae4dbfc3b06fdd501f5626225f7f4fa66da71d91bc82a796`
- Independent read-only review found two Revert-state defects; both were fixed and regression-tested before packaging

Additional tests cover live cell draft persistence, same-cell reselection, global discard clearing editor state, Back/outside-cell tap/sheet/home navigation, both open-document guards, export before Done, search focus and saved-state restoration when no document is available. Tests include committed recovery metadata, not a real killed-device process. PDF forms remain research only. Device/IME/TalkBack/WebView/native PDF limitations below still apply.

## 2026-10-02 compact preview milestone

Current-source tests passed on Linux / JDK21 / Android SDK37:
- 41 core document-engine tests
- 11 Android safety/recovery tests (Robolectric API35)
- 11 Compose workspace interaction/visual tests (Robolectric native graphics)
- **63 total, zero failures or errors**

Coverage includes byte-identical unchanged export; repeated exports; UTF8/UTF16 BOM, CRLF, Unicode and RTL; XML escaping; untouched assets; shared-string isolation; formula refusal; ZIP traversal/expansion limits; XML depth/node limits, XXE/DOCTYPE and nonstandard relationship-selected XML; signatures and macros; ODF spans/assets/mimetype; numeric slide ordering; preview archive normalization; original-preserving recovery, stale writers and backup recovery; denied WebView navigation/resource schemes; incoming-intent unsaved confirmation; spreadsheet cancel/apply; actual export-result callback and provider output; provider failure; discard and resumed drafts.

Rendered Android UI captures cover light/dark home, 320dp and tablet widths, populated recents, document text canvas, editor, spreadsheet, export review and error. The 320dp/200% font-scale test checks bottom actions with **simulated IME insets**. The blank reserved keyboard region is not a keyboard rendering. Parent visual review confirmed the previously clipped Save copy action is now visible.

The combined test/R8/lint build exhausted or lost its Gradle daemon during lint-vital after tests and R8 completed. A fresh assembly-only process completed successfully, including lint-vital. The preview APK is 1,433,399 bytes; APK v2 signing and 16 KiB ZIP alignment checks pass. Full `lintDebug` passed in a separate process: **0 errors, 8 warnings** (target API36 versus latest platform; pre-API31 extraction-rule applicability; five optional KTX suggestions; one fallback-label localization warning). Backup is disabled on all supported versions; real-device backup behavior remains untested. See SIZE_BUDGET.md for the SHA-256 and expanded size.

### Runtime limitations

No emulator or physical-device run has been completed. Real IME interaction, Android API26 compatibility, isolated native PDF service behavior/lifecycle, device low-memory behavior and installed footprint remain unverified. The DOCX JavaScript layout renderer has not been visually exercised: local Chromium sandbox startup and cloud-browser localhost access were blocked. Android WebView policy tests pass, but do not substitute for an actual rendered WebView or cross-provider test. DOCX formatted preview is approximate and separate from existing-text-run editing; spreadsheet formulas are not recalculated; legacy binary Office and RTF formats are refused. See FORMATS.md.

## Initial milestone

Earlier source passed assembleDebug, lintDebug and 18 engine tests. Those results are historical and do not establish final artifact validation.
