# Third-party notices

Initial app: Android framework and AndroidX libraries (Apache-2.0), Kotlin (Apache-2.0), kotlinx.coroutines (Apache-2.0), JUnit4 (EPL-1.0, tests only), Hamcrest (BSD-3-Clause, tests only).

No WPS or Mi Doc Viewer code, binaries, branding or other assets are included. The APKMirror listing was consulted only as a public product reference.

Any optional Collabora/LibreOfficeKit engine must retain its own MPL-2.0 and other component notices and corresponding-source links. It is not included in the initial app artifact.

## Compact formatted preview

- docx-preview 0.3.6 by Volodymyr Baydalka, Apache-2.0: https://github.com/VolodymyrBaydalka/docxjs
- JSZip 3.10.1, used under MIT: https://github.com/Stuk/jszip

Complete license files and pinned tarball/asset hashes are in `third-party/`. `scripts/vendor-preview.py` reproduces the shipped assets with SHA-512 tarball and SHA-256 file checks. No CDN or runtime download is used. Total minified JavaScript before APK compression:171,871 bytes.
