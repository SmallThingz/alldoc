# Verification log

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
