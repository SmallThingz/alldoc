# Full-featured offline Android Office engine: feasibility checkpoint

Checked 2026-10-03. Research only; no SDK purchase, license acceptance, proprietary binary copying, full engine download, build or swap was performed. This is a technical comparison, not a legal opinion.

## Decision in brief

The compact parser/preview improvements cannot satisfy a requirement for complete interactive Word/Excel/PowerPoint features, including chart manipulation and formula recalculation. Stop extending them as if that will converge on a full office suite.

The strongest inspectable open-source route is the LibreOffice/Collabora engine and its existing editing frontend, but its current **single-ABI** Android distribution is already **254.8 MiB APK**, and its native engine alone is **190.2 MiB**. A separate engine download would defer this cost rather than remove it. A materially smaller custom build is a research project, not an established size claim.

A commercial mobile editor SDK, particularly Polaris Mobile SDK, is worth a vendor-qualified evaluation if commercial licensing is acceptable. Its actual offline operation, full editing coverage, distributable module sizes and installed footprint are not publicly established by the inspected material. Aspose is a credible smaller processing/rendering layer, but it does not supply the full interactive editor requested. No verified option presently meets all of: broad interactive Office editing, fully offline, compact total installed footprint, and immediately buildable/licensed integration.

## 1. LibreOfficeKit / Collabora Office

**Capability.** LibreOfficeKit exposes the office core through C/C++; Collabora's Android app integrates native document processing with an existing editing frontend. The upstream app offers document, spreadsheet and presentation editing offline; the credited Android work specifically includes chart interaction and Calc formula-bar functionality. This is materially different from extracting text or rendering a flattened PDF. It remains an independent Office implementation, so exact Microsoft feature/format equivalence must be tested rather than promised.

Primary sources: [LibreOfficeKit architecture](https://docs.libreoffice.org/libreofficekit.html), [Android architecture](https://docs.libreoffice.org/android.html), [official Android app repository](https://www.collaboraoffice.com/downloads/fdroid/repo/), [Android implementation credits](https://www.collaboraonline.com/collabora-office-for-android-ios-credits/).

**Actual size.** Read the publisher's [F-Droid index](https://www.collaboraoffice.com/downloads/fdroid/repo/index.xml), then read only the APK ZIP trailer/central-directory metadata with HTTP ranges. The metadata read was 656,939 bytes; the APK payload was not downloaded or executed.

| Publisher artifact | ABI | APK bytes | MiB |
|---|---|---:|---:|
| Stable 26.04.3.1, version code 155, September 3 artifact | arm64-v8a only | 267,216,449 | 254.8 |
| Snapshot 26.04.5.0, version code 933, October 2 artifact | arm64-v8a only | 254,630,099 | 242.8 |

The stable APK's 6,375 ZIP entries total 364,017,627 bytes (347.2 MiB) uncompressed. Its largest entries are:

- `lib/arm64-v8a/liblo-native-code.so`: 199,472,488 bytes, stored uncompressed
- `lib/arm64-v8a/libandroidapp.so`: 6,322,632 bytes, stored uncompressed
- `assets/dist/bundle.js`: 10,465,815 expanded / 3,057,413 compressed bytes
- `assets/dist/l10n-all.js`: 7,140,356 expanded / 1,922,605 compressed bytes

[Exact measured publisher APK](https://www.collaboraoffice.com/downloads/fdroid/repo/collabora-office-mobile-26.04.3.1-155-release-arm64-v8a-2026-09-03.apk).

These are package and ZIP-entry sizes, **not a device-measured installed footprint**. Installed storage includes the delivered APK plus any extracted assets, ART compilation and runtime caches. The uncompressed ZIP total must not be added blindly to the APK, nor quoted as the actual installation size. Nevertheless, the current package and its native core alone establish the “hundreds of MB” problem.

**Build/split feasibility.** Current official instructions build the native engine first on Linux, select one of four Android ABI configurations, then build the frontend/app with the Android NDK and Gradle. The current guide identifies a Gerrit monorepo with `engine/`; old two-repository instructions are stale. It also distinguishes unstripped debug symbols in the build tree from the stripped native code in the APK. [Official build guide](https://www.collaboraoffice.org/post/build-code-android/).

Inference: trimming dictionaries, templates, translations and unused import/export filters can be investigated, but ABI splitting is already reflected in the measured artifact and cannot remove the 190.2 MiB native core. There is no demonstrated compact build here preserving the entire requested format-feature set. Feature modules can reduce initial download only; a complete installation must still count all mandatory engine/fonts/resources. [Android App Bundle delivery](https://developer.android.com/guide/app-bundle).

**License.** Collabora identifies MPL 2.0 and other component licenses. MPL-covered modifications must retain the applicable notices and corresponding covered source obligations; the license permits larger works, but dependency licenses and branding/trademarks need a full redistribution audit. This is not blanket permission to copy/rebrand a publisher's APK. [Collabora license/source statement](https://www.collaboraonline.com/collabora-office-for-android-ios-credits/), [Mozilla MPL FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/).

## 2. ONLYOFFICE

The standalone Android application genuinely supports local/offline documents. However, that does not establish an embeddable open-source offline SDK for another app. The former official `documents-app-android` repository returned 404 at the time of this check. Official support previously stated that its Android build depended on private repositories; that dated statement is useful background, not proof of the current commercial SDK contract. [Android app overview](https://helpcenter.onlyoffice.com/mobile/android/documents/overview.aspx), [official support explanation](https://forum.onlyoffice.com/t/about-android-app/520).

The current documented mobile integration is **WebView plus ONLYOFFICE Docs**, with document management/storage and the Docs integration architecture. The documentation says mobile web editors require commercial Enterprise or Developer builds. This should not be represented as an on-device offline native editing engine. The open `sdkjs` repository is the client-side JavaScript editor component for Docs/Desktop, not a complete standalone Android office stack. [Official mobile integration](https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/mobile-integration/), [SDKJS](https://github.com/ONLYOFFICE/sdkjs), [Android WebView demo](https://github.com/ONLYOFFICE/editors-webview-android).

SDKJS is AGPLv3, with current distribution/attribution terms requiring review. ONLYOFFICE announced licensing clarifications in May 2026. Do not infer that every mobile binary or private native component is available under the same license. A viable proprietary native SDK license, source/build availability, redistribution rights, size and offline contract must be confirmed with the vendor before proposing integration. No current embeddable-native artifact size was verified. [May 2026 license update](https://www.onlyoffice.com/blog/2026/05/onlyoffice-docs-9-4).

## 3. Aspose Android via Java

Aspose's actual Android classifiers use Android graphics types rather than desktop `java.awt`. Words processes and renders word-processing documents; Cells provides charts, worksheet rendering, pivots and formula calculation; Slides processes/render presentations, charts, masters, layouts and other slide objects. These are programmatic document-processing APIs. A full interactive document canvas, selections, editing gestures, cell/grid UI, chart editor, undo/redo and toolbars would still have to be implemented. Aspose's own support explicitly distinguishes Words from a UI/control.

Sources: [Words Android product API](https://docs.aspose.com/words/androidjava/), [Cells Android capabilities](https://products.aspose.com/cells/android-java/), [Slides Android API](https://docs.aspose.com/slides/androidjava/), [vendor UI clarification](https://forum.aspose.com/t/demo-aspose-words-for-android-problem/52764).

Public publisher repository sizes verified without downloading proprietary JARs:

| Android classifier | Published size |
|---|---:|
| Slides 26.9 Android JAR | 31.2 MB |
| Cells 26.9 Android JAR | 8.9 MB |
| Cells 26.9 Android resources JAR | 1.4 MB |
| Words 25.9 Android JAR, verified available version | 16.1 MB |

[Slides index](https://releases.aspose.com/java/repo/com/aspose/aspose-slides/26.9/), [Cells index](https://releases.aspose.com/java/repo/com/aspose/aspose-cells/26.9/), [Words index](https://releases.aspose.com/java/repo/com/aspose/aspose-words/25.9/).

Those selected artifacts total approximately **57.6 MB of compressed JARs** across mixed verified versions. That is not an APK estimate or installed-size measurement; DEX output, resources, fonts, any dependencies and the necessary editor UI must be measured in an actual authorized build. Do not substitute desktop Java artifacts when the Android classifier is absent. Aspose's Slides product page reports 145,426 methods for its 26.6 Android artifact, illustrating multidex considerations. [Slides Android product details](https://products.aspose.com/slides/android-java/).

It is commercial software; trials add watermarks/other limits. An offline deployment should use a suitable non-metered license and block external resource retrieval. Words' Android limitations include missing image/printing features and its docs state metered licensing or HTTP/FTP access needs internet permission. These limitations refute a literal “every format feature” promise even with a mature SDK. [Words licensing](https://docs.aspose.com/words/java/licensing/), [Words Android limitations](https://docs.aspose.com/words/java/aspose-words-for-android-via-java-limitations-and-api-differences/), [Slides licensing](https://docs.aspose.com/slides/androidjava/licensing/).

## 4. Commercial interactive-editor candidates and false equivalents

**Polaris Mobile SDK:** the current vendor page explicitly offers its mobile document editor as a kit with editing/layout preservation and business-app integration. That makes it a more relevant full-editor lead than a conversion API. Public SDK module sizes, native ABI splits, installed footprint, chart/recalc compatibility details, offline license behavior and redistribution pricing were not disclosed in the reviewed material. The consumer subscription app's offline policy cannot be used as the SDK's policy. Requires an authorized vendor evaluation and a real feature corpus before selection. [Polaris Mobile SDK](https://www.polarisoffice.com/en/solution/kit).

**Apryse:** the native mobile platform supports direct Office viewing/conversion and rich PDF editing. Its old public sample-size comparison quotes a 16 MB single-arm64 full sample APK, but omits version/complete contemporary module accounting and is not evidence of a full Office editor. The separate Web Spreadsheet Editor has real chart editing, but documented exclusions (several chart types; 3D rendered as 2D) and is a Web SDK. Do not conflate those products or claim full native source-format editing from a PDF editor. [Mobile feature scope](https://docs.apryse.com/android/guides/flutter?lang=en), [sample-size comparison](https://docs.apryse.com/android/faq/full-vs-standard), [Web chart support/limitations](https://docs.apryse.com/web/guides/spreadsheet-editor/charts).

**Artifex SmartOffice:** a 2023 vendor announcement offered it as an SDK, but the current SmartOffice product URL redirects to the broader product list, where the current Office offerings are primarily processing/rendering products. Availability and supported licensing cannot be assumed from historic claims or APK mirrors. [Historical announcement](https://artifex.com/blog/smartoffice-removed-from-app-stores-focus-sdk-enterprise), [current product list](https://artifex.com/products).

## Recommended acceptance gate before integration

1. Set separate explicit budgets for initial download, total engine-inclusive download, installed storage and peak RAM. Never hide mandatory bytes in an “optional” pack
2. Require a legally redistributable Android artifact or reproducible source build, known ABI list, offline licensing contract and no document upload
3. Test real DOC/DOCX/ODT/RTF, XLS/XLSX/ODS and PPT/PPTX/ODP corpora: charts and their data, formula recalculation, images, tables, masters, fonts, page/slide layout, edit→save→reopen in both the engine and a trusted office app
4. Distinguish full source-format editing from rendering, text extraction, PDF editing and API-only manipulation. Record specific unsupported constructs rather than saying “full” from a file-extension list
5. Keep macro execution, external connections, signatures, encryption and untrusted embedded objects behind the existing safety boundary; richer document support does not imply executing active content
