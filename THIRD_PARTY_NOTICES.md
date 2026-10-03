# Third-party notices

Initial app: Android framework and AndroidX libraries (Apache-2.0), Kotlin (Apache-2.0), kotlinx.coroutines (Apache-2.0), JUnit4 (EPL-1.0, tests only), Hamcrest (BSD-3-Clause, tests only).

No WPS or Mi Doc Viewer code, binaries, branding or other assets are included. The APKMirror listing was consulted only as a public product reference.

Any optional Collabora/LibreOfficeKit engine must retain its own MPL-2.0 and other component notices and corresponding-source links. It is not included in the initial app artifact.

## Compact formatted preview

- docx-preview 0.3.6 by Volodymyr Baydalka, Apache-2.0: https://github.com/VolodymyrBaydalka/docxjs
- JSZip 3.10.1, used under MIT: https://github.com/Stuk/jszip

Complete license files and pinned tarball/asset hashes are in `third-party/`. `scripts/vendor-preview.py` reproduces the shipped assets with SHA-512 tarball and SHA-256 file checks. No CDN or runtime download is used. Total minified JavaScript before APK compression:171,871 bytes.

## Legacy Office read-only text preview

- Apache POI core and scratchpad 5.5.1, Apache-2.0: https://poi.apache.org/
- Apache Commons Codec 1.20.0, Collections4 4.5.0, Math3 3.6.1 and IO 2.21.0, Apache-2.0: https://commons.apache.org/
- Apache Log4j API 2.24.3, Apache-2.0: https://logging.apache.org/log4j/2.x/
- SparseBitSet 1.3, Apache-2.0: https://github.com/brettwooldridge/SparseBitSet

Dependency artifact SHA-256 hashes are in `third-party/apache-poi/DEPENDENCIES.txt`.
Complete upstream license and notice files ship in `app/src/main/assets/licenses/apache-poi/`;
duplicate JAR metadata and Java module descriptors are excluded from APK resource merging.
The Apache POI `simple.doc` test fixture is retained as `fixtures/legacy-basic.doc`;
its source and fixture-generation details are in `fixtures/LEGACY-PROVENANCE.md`.
