# alldoc

An offline-first Android document workspace by SmallThingz. **Active development; not yet a replacement for a full office suite.**

## 0.2 workspace

- Kotlin / Jetpack Compose, Android 8.0+, system document picker
- Redesigned searchable document library, consistent native controls and light/dark themes
- Isolated-process Android PDF viewing, pagination and zoom; Android 15+ AcroForm text filling
- Offline approximate DOCX preview, horizontally scrolling XLSX/ODS grids, selected-slide PPTX/ODP text layouts
- Genuine read-only legacy DOC/XLS/PPT extraction, separate from modern Office editing
- ODT and flat OpenDocument, validated XML/SVG, and inert HTML/CSS/text source editing
- Structured OOXML / ODF content editing with explicit fidelity warnings
- Export-copy only: source provider files are never opened for write
- Atomic draft recovery, unchanged-byte exports, and visible recovery archives for pre-0.2 Office drafts
- No Internet, broad storage, analytics, account, advertising or AI permissions

See [format boundaries](docs/FORMATS.md), [security model](docs/SECURITY.md), and [verification](docs/VERIFICATION.md). The compact app has a 5 MiB APK budget. A large native-office experiment was rejected and is not part of main. The current feature matrix does not promise full Word/PowerPoint layout fidelity.

## Build

JDK 21, Android SDK platform 37 and build tools 36.0.0; Gradle wrapper 9.6.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Small installable preview:

```sh
./gradlew :app:assemblePreview
python scripts/check-apk-size.py app/build/outputs/apk/preview/app-preview.apk
```

The `alldoc Preview` APK uses R8/resource shrinking and a development signing key, with its own `.preview` application ID. It is an evaluation build, not a production-signed release. Debug builds include test tooling and are larger. No production signing key is included.

The Gradle test task is the supported full verification path. Core parser tests can also run without the Android SDK, using the Apache POI/runtime dependencies pinned in the build:

```sh
JUNIT_JAR=/path/junit-4.13.2.jar HAMCREST_JAR=/path/hamcrest-core-1.3.jar \
POI_CP="/path/to/poi-and-runtime-dependencies/*" ./scripts/test-core.sh
```

## Design principles

Quiet, accessible native controls. The original remains yours. Every unsupported feature is visible, never disguised as complete support. All imports and exports happen off the UI thread, and visible content is virtualized. No proprietary WPS code or assets are used.

## Upgrading from 0.1.x

Office draft IDs changed with improved sheet/slide order and ODS cell modeling. Earlier drafts are never guessed onto new document blocks. alldoc preserves the unchanged original plus all old draft text in a verified recovery archive first. Use **More → Recovered drafts → Save recovered draft** to save the ZIP. Archives remain available after opening other files.

See [design decisions](docs/DESIGN.md) and the verification log for actual test coverage and remaining device-only checks.
