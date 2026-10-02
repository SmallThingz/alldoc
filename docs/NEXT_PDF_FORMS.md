# Next compact capability: system PDF forms

Research date: 2026-10-02. **Not shipped or advertised as supported.**

## Recommended scope

Use Android's existing `PdfRenderer` form APIs on API35+ only. Start with unencrypted AcroForm text fields, checkboxes and choice lists. Older Android versions retain existing rendering/copy behavior. XFA, unknown widgets, buttons/actions, read-only fields and signatures stay unsupported for editing. Do not flatten pages or substitute text extraction for form editing.

The API35 platform exposes `getPdfFormType`, page `getFormWidgetInfos`, `applyEdit(FormEditRecord)` and renderer `write(ParcelFileDescriptor, false)`. `FormWidgetInfo` supplies bounds, type, read-only status, current values, maximum length and accessibility labels. Installed SDK37 signatures were independently checked using javap. API documentation availability is not proof that every OEM implementation works.

## Footprint and licensing evidence

No external engine is adopted. The Android framework runtime is provided by the device: **0 bytes of additional redistributed engine binary**. This is not a measurement of implementation glue; that code and its APK delta have not been built. Current delivered baseline is 1,433,399 bytes, with 2,699,295 bytes of expanded ZIP members. Installed footprint remains unmeasured.

Original application glue remains Apache-2.0. Calling the system API does not embed or redistribute Android/PDFium binaries. If a bundled PDFium fallback were proposed, it would need a separate complete dependency/license/size audit; PDFium's Android build explicitly carries multiple third-party licensing conditions. No such fallback is planned for this scope.

## Safety and export design

- Keep immutable original bytes and SHA identity; never grant the renderer a writable original FD.
- Send only bounded widget descriptors and edit records through the existing isolated service; read-only source FD and writable staging FD are capabilities supplied by the app. No network/storage permission is required by this design. Verify actual API35 isolated-service rendering/editing/writing on a device before support claims.
- Track versioned form edits in recovery metadata, replay against the original after service/process restart; avoid duplicating transient renderer state as authoritative data.
- `write` closes its supplied FD. Pass a duplicate staging FD, retain another for explicit fsync and validation, then re-open exported bytes and verify edited widget values before exposing export success.
- Always use `removePasswordProtection=false`; initially keep encrypted/password-protected documents noneditable. No password collection or protection removal is proposed.
- Reject any document with a signature widget for editing. This does **not** prove absence of all document-level/certification signatures: API35 does not expose a complete signature-verification interface in the examined public surface. A conservative export warning can state that signatures/certifications may become invalid without claiming the file is unsigned. However, the examined surface also does not expose complete document-level certification/editing restrictions; enforcing those rather than silently bypassing them remains a release gate. Signature metadata preservation versus invalidation must be tested explicitly.
- Fail safely on read-only/unknown widgets, unsupported form types, failed edits or export, service death and timeout; original and recoverable draft remain available. SAF destination failure may still leave an incomplete separate copy.
- Form calculations/scripts are not a promised capability. Validate cross-field effects and refuse unsupported operations rather than silently claiming full form fidelity.

## Required evidence before shipping

Synthetic multi-page AcroForm fixtures; text/checkbox/list edits; Unicode and long text; read-only and maximum-length fields; XFA/unknown/signature refusal; failed or cancelled provider writes; renderer process death and replay; reopening saved PDF in an independent viewer. Run API35/36 device tests and API26 fallback tests. Measure final APK delta and installed size separately. A Robolectric mock does not establish native PDF correctness.

## Sources

- [PdfRenderer reference](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [Page editing reference](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)
- [FormWidgetInfo reference](https://developer.android.com/reference/android/graphics/pdf/models/FormWidgetInfo)
- [FormEditRecord builder](https://developer.android.com/reference/android/graphics/pdf/models/FormEditRecord.Builder)
- [API35 framework source](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/main/android-35/android/graphics/pdf/PdfRenderer.java)
- [Android PDFium licensing inventory](https://android.googlesource.com/platform/external/pdfium/+/refs/heads/main/Android.bp)
