# Verification log

## 2026-10-02 initial milestone

Passed on Linux / JDK21 / Android SDK37:
- `assembleDebug`
- `testDebugUnitTest`: 18 tests
- `lintDebug`
- Independent direct `javac` / JUnit core run: 18 tests

Coverage: original byte identity; independent repeated exports; Unicode/RTL text; XML escaping; untouched asset preservation; shared-string isolation; formula edit refusal; UTF16 BOM and CRLF; invalid UTF8 and binary refusal; ZIP traversal, XML XXE/DOCTYPE, macro and signature refusal; unknown edits; expanded-entry limits; ODF spans/assets/mimetype; numeric slide-part ordering; invalid archive and legacy format refusal.

Not yet verified: Android emulator/physical-device interaction, actual Compose screenshots, isolated PDF service behavior on-device, provider cancellation/failure variants, full Office rendering, full Office roundtrips, crash-recovery fault injection. Build/lint success does not replace these tests.
