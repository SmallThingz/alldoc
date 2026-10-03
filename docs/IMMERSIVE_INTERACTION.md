# Immersive interaction, unreleased working tree

The document is the default interface. App bars, file status, search, mode selectors, page counters and export actions are hidden when a document opens. Library navigation stays conventional. Necessary content errors/loading and document context such as spreadsheet headers remain visible.

A non-consuming observer accepts one stationary tap only after the platform double-tap ambiguity window. Controls are sibling overlays, so showing/hiding them does not alter document viewport bounds. A reveal is not a toggle. Scroll, fling-stop taps, multi-pointer scale, long press, selection and canceled gestures are ineligible. Overlay buttons are outside the document observer.

Plain/source files open directly editable. Caret placement coexists with reveal; an actual text mutation hides document overlays, retaining field focus and typing. Sheet cell selection reveals the contextual value editor inside the overlay; Done hides it. Original files remain protected and Save copy is explicit. The compact engine's genuine read-only restrictions are disclosed in controls/details.

Presentations have continuous full-width pages and a horizontal single-slide pager. The selected slide is preserved across switching modes. Explicit navigation requests are distinct from scroll observations, preventing ordinary continuous scrolling from snapping back. Pinch only consumes multi-pointer input, never a normal single-finger scroll. Unsupported rendering remains labeled rather than claimed as full fidelity.

Accessibility retains the document text/cell tree, a Show/Hide document controls custom action and F10/Menu/Escape controls. Automatic overlay hiding honors Android's recommended timeout and is suspended during touch exploration, overlay focus, search and dialogs. Actual TalkBack/device execution is still required.

## Verification scope

The pure recognizer and real Compose pointer tests include single tap, small drift, touch slop, double/triple taps, long press, pinch, cancellation, child control arbitration and stopping an already-moving document. A mixed-clock regression verified the observer must not compare pointer timestamps to Android SystemClock; the previous code fails the test.

Current integrated Robolectric native-graphics tests cover actual editable caret taps/focus/typing, unchanged content bounds, source scrolling/fling stopping, selection-dismiss taps, source Find selection, presentation tapping/pinch/pager/continuous scrolling/navigation/mode retention, fresh keyboard controls and populated-cell taps. Independent native visual review passes the reviewed narrow-font, dark, typing, Find and overlay paths. Final aggregate test/build counts belong in VERIFICATION.md after the final run.

A real source long-press/selection-handle test is explicitly skipped because Robolectric's native Magnifier destroys a null Surface. No production selection workaround is added to hide that platform gap. Real IME, TalkBack, device low-memory behavior, WebView formatted DOCX and native PDF remain unverified. Simulated IME insets are layout evidence only. Any preview artifact still requires the final aggregate test/lint/signing gates.

Evidence directories: `app/build/screenshots/immersive/` and `app/build/screenshots/immersive-regression/`. Historical 0.2.0 images remain unchanged.
