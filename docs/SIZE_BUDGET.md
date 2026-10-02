# Small app budget

Shipping preview/release APKs must be at most **5 MiB**. `scripts/check-apk-size.py` enforces this and rejects the abandoned large office engine. No hidden engine download or online document-conversion service is included.

Measured first baseline before compact preview assets:
- Debug APK: 12,120,918 bytes
- R8/resource-shrunk release APK: 1,340,621 bytes

Those are artifact measurements, not promises about later builds. Every delivered artifact gets a fresh size/SHA-256 report. Android installed footprint includes platform-managed optimization/storage overhead and must be measured on a device; ZIP expanded size is not installed size.

The DOCX preview adds 171,871 bytes of minified JavaScript before ZIP compression. It uses the device's WebView, with no browser runtime bundled. PDF pages use the device's system renderer. Font families use platform fonts; no large font packs or stock graphics are bundled.

The optional Collabora experiment remains separate from main. Its official APK was 267 MB and was rejected as incompatible with the product's small-app requirement. Its downloaded native/assets payload was removed; corresponding source/provenance remains on the local research branch. It is not a shipping capability.

## Validated 0.1.0 preview

- APK: **1,433,399 bytes (1.37 MiB)**
- Expanded ZIP entries: 2,699,295 bytes (not installed footprint)
- SHA-256: `368294143b70ab571b1741620d562259e2abe3b02779bd5bc07c9479a16eb3fd`
- APK v2 signature and 16 KiB ZIP alignment checks passed
- Package: `org.smallthingz.alldoc.preview`; development-signed evaluation build
