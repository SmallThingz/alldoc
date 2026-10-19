# AllDoc

![Android](https://img.shields.io/badge/android-9%2B-3ddc84?logo=android&logoColor=0f172a)
![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-7c3aed?logo=kotlin&logoColor=ffffff)
![Compose](https://img.shields.io/badge/ui-Jetpack%20Compose-2563eb)

Offline document reader for Android. Open documents, keep your place, and share text, structured data or page images with other apps.

## Highlights

- **Reading views.** Scroll documents vertically or pan spreadsheet grids in Normal; move one page at a time in Present. Markdown has one continuous formatted reading view, with a Text alternative. Extractable documents also offer a Text view.
- **Your place, remembered.** Position, zoom, rotation, reading view and appearance follow the document's SHA-256 hash, even when its name or source changes.
- **Recents and Library.** Six recent documents, a three-column cover grid, incremental content-aware indexing, type groups and fuzzy filename filtering. Open individual files or grant shared-storage access. Library keeps its own document-type filters.
- **Document colors.** System, Light, Dark, Paper, Original, Contrast and Invert, with separate image handling. Paper roughness is adjustable; System, Light, Dark and AMOLED app themes are independent of document colors.
- **Notes and navigation.** Search, PDF contents, and supported speaker notes/comments below the page in portrait or alongside it in landscape.
- **Export and integration.** Text, hierarchical JSON, page images and Print to PDF. Descriptor-based previews for Android apps; OACP capabilities for assistants.
- **Debug examples.** Debug builds include 46 small offline documents, one per supported extension. Release builds omit them.

Temporary shares can be retained as deduplicated copies in **Documents/AllDoc**, with an inline storage slider from Off through 4096 MB, plus Unlimited. A separate safety cap bounds the copy index. Source files remain separate from the reader's working cache. [Reading state and storage](docs/STATE.md).

**Android 9+ · `app.smallthingz.alldoc` · arm64 and x86-64**

## Formats

| Family | Reading support |
| --- | --- |
| PDF | Pages, extractable text, contents and annotations |
| Word, PowerPoint, Excel | Best-effort OOXML pages and XLSX worksheet grids; structured reading for legacy binary formats |
| OpenDocument, RTF, EPUB, FictionBook | Text and document structure |
| Pages, Numbers, Keynote | Saved embedded previews, when present |
| Markdown | Formatted headings, lists, emphasis, tables and code in one continuous document |
| HTML, XHTML | Readable document text; scripts and remote resources are ignored |

Rendering fidelity varies by format. Standalone images, plain text, source code, data files, email and 3D models are outside the reader’s scope. Apple IWA content is not decoded; scanned pages have no OCR. Macros and active content do not run. Bounded or omitted content is reported. See the [full format matrix](docs/FORMATS.md) for extensions and limits.

## Build

Requires a **Linux x86-64 host**, JDK 21, Python 3, Git, `patch`, Rust with `rustup`, Android SDK Platform 37 and **NDK 29.0.14206865**. Set `ANDROID_HOME` and `JAVA_HOME` first.

```sh
rustup target add aarch64-linux-android x86_64-linux-android
git submodule update --init --recursive
scripts/build
```

Use `scripts/build --abis arm64-v8a` for a phone-only build or `--debug` for debug APKs. `scripts/build --check` checks prerequisites. Separate ABI APKs are written under `app/build/outputs/apk/release/`.

Release builds enable R8, resource shrinking, and compressed DEX/native libraries. They currently use the local Android debug key; configure a private release key before store distribution. [Native build details](native/render/README.md).

```sh
scripts/check-core
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest :client-test:connectedDebugAndroidTest
```

Build native libraries before running Gradle packaging. `scripts/check-core` exercises the host JNI reader; device tests cover rendering, gestures, persistence, cancellation and cross-app descriptor ownership. Tests use synthetic fixtures.

## Integration

Android apps can bind to `app.smallthingz.alldoc.ipc.DocumentService` through the small `:api` module. Pass a caller-owned file descriptor, retain a session while viewing, and request text, JSON or PNG through bounded descriptor streams. Document bytes stay out of Binder messages; parsing and rendering run on workers.

- [Android API and client example](api/PROTOCOL.md)
- [OACP discovery and assistant capabilities](api/OACP.md)
- [Reader interface](docs/INTERFACE.md)
- [Document color handling](docs/DARK_MODE.md)

## Sources and acknowledgements

[WordCraft](https://github.com/storytold/wordcraft), [DeckCraft](https://github.com/storytold/deckcraft), and [GridCraft](https://github.com/storytold/gridcraft) are pinned submodules. AllDoc uses their parser/rendering components with local mobile adapters; upstream updates require rebuilding and checking the format fixtures. Native source inventories, patches and dependency notices are retained under `native/`.

[Reverb](https://github.com/SmallThingz/reverb), MJ PDF and Dark Reader inform the interface and document colors. [Design references](docs/UI_REFERENCE.md). Dependency notices are available from **Licenses** on Home.

## License

Original AllDoc contributions are [Apache-2.0](LICENSE). Third-party and ZFM-derived
code retains its own terms; the ZFM crate and repository contain conflicting license
declarations. See [NOTICE](NOTICE) for scope, provenance and retained license texts.
