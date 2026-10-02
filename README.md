# alldoc

An offline-first Android document workspace by SmallThingz. **Active development; not yet a replacement for a full office suite.**

## Current verified build

- Kotlin / Jetpack Compose, Android 8.0+, system document picker
- Isolated-process Android PDF renderer, page navigation and zoom
- Structured OOXML / ODF content editing with explicit fidelity warnings
- Export-copy only: source provider files are never opened for write
- App-private atomic draft recovery, original-byte preservation for unchanged exports
- No Internet, broad storage, analytics, account, advertising or AI permissions

See [format boundaries](docs/FORMATS.md), [security model](docs/SECURITY.md), and [verification](docs/VERIFICATION.md). A full-layout LibreOfficeKit/Collabora backend is under investigation; it is not part of the initial APK.

## Build

JDK 21, Android SDK platform 37 and build tools 36.0.0; Gradle wrapper 9.6.0.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is for development only. No production signing key is included.

Core parser tests can run without the Android SDK:

```sh
JUNIT_JAR=/path/junit-4.13.2.jar HAMCREST_JAR=/path/hamcrest-core-1.3.jar ./scripts/test-core.sh
```

## Design principles

Quiet, accessible native controls. The original remains yours. Every unsupported feature is visible, never disguised as complete support. All imports and exports happen off the UI thread, and visible content is virtualized. No proprietary WPS code or assets are used.
