# Document-first native workspace

The 0.2 redesign uses a shared ink/paper palette, a restrained blue action color, platform sans typography, a 4/8 dp spacing rhythm and small vector icons. Light and dark palettes are authored separately. File-type accents are supplemental, never the sole format label.

## Library

One compact file list replaces the duplicate draft card and oversized opening button. Each row has a file icon, filename, explicit extension, recent time or draft state, and one opening target. Search and type filters appear when there are files. The column is capped on large screens. Large text moves the Open action onto a separate line.

## Workspace

The filename stays at the top. Read/Layout/Text/Edit modes use restrained text tabs rather than large chips. The document gets most of the screen; unchanged files have no persistent Open/Export footer. Save copy appears when the draft changes. At large font scale the footer contains only its action, and secondary editor metadata is removed.

- Text/source: continuous editing, monospace for source formats, virtualized reading chunks and source search
- Sheets: fixed row labels, synchronized horizontal scrolling, standard sheet selection, selected-cell editor, explicit formula/read-only states and repeated-range labels
- Slides: a bounded simplified text-frame preview, honest omitted-visual notice and persistent selected-slide editing with slide labels
- PDF: page viewing and a separate, capability-gated form panel; edits show in the exported copy

All actions have named accessibility targets. Visual controls retain touch hit areas; user text is allowed to reflow. Error copy describes the next action without raw provider exception names. Save operations use separate copies and preserve the original.

## Visual evidence scope

Native Compose captures are generated from the application UI under Robolectric native graphics. They are useful layout/interaction evidence, not physical-device, real-IME, PDF-service or Android WebView proof. DOCX browser rendering could not be inspected in this environment because the supported cloud browser rejected the local HTTP fixture.
