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

## Validated 0.1.1 preview

- APK: **1,433,399 bytes (1.37 MiB)**, unchanged compressed size after alignment
- Expanded ZIP entries: 2,702,081 bytes (+2,786 bytes; not installed footprint)
- SHA-256: `817a9df0788b9f8cae4dbfc3b06fdd501f5626225f7f4fa66da71d91bc82a796`
- VersionCode 2; same package and development signing certificate as 0.1.0
- Signature and 16 KiB ZIP alignment checks passed

## Validated 0.2.0 preview

- APK: **2,060,250 bytes (1.96 MiB)**, +626,851 bytes over 0.1.1
- Expanded ZIP entries: 4,615,044 bytes; Android installed footprint remains unmeasured
- SHA-256: `5a8ad0ac526a0a171c82594cf807905fcc1df45fece2dce257f2c5b7c366a38c`
- VersionCode 3; same package and signing certificate as 0.1.1; APK v2 signature and 16 KiB alignment pass
- Includes the redesigned native UI, bounded legacy POI extraction, simplified slide/ODS/source improvements and platform PDF form tools. No engine download or online conversion service is present
