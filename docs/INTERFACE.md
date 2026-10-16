# Interface

Home separates **Recents**, limited to six documents, from **Library**, a three-column collage grouped by document type. Each tab keeps its scroll position. Names remain alphabetical within groups; fuzzy search filters names without relevance sorting or searching document contents. The type filter narrows the visible group. Excluded types in Settings persist across launches and subsequent scans.

The main app header stays fixed; tabs and secondary controls collapse while scrolling down and return while scrolling up. Open launches the file picker. Examples opens 46 bundled documents. The centered launcher icon opens a compact top panel with version and repository link. Licenses is the top-left action; Settings is top-right.

Settings contains device access and Add folder. All-files access covers readable shared volumes, not other apps' private storage. Opening one document needs no broad storage access. An empty Library offers a path to these settings; pull down to refresh an existing Library.

Known document names appear as candidates before signature checks and complete hashing. Unfamiliar names are probed for supported document signatures during discovery. Bounded package probes retain compatible name/provider hints when inconclusive; unrecognized binary data is excluded. Container contents are validated by the selected reader when opened. Verified equal hashes merge locations. Only visible covers are generated, using document-default colors; unavailable previews retain a fallback icon.

Scanning is finite and runs while Library is visible in the foreground. Leaving Library or stopping the app cancels scanning and previews; there is no background service. Permission denial or an inaccessible folder can leave a partial catalog without removing known files.

New documents start in **Normal**, with continuous vertical pages. **Present** fits pages horizontally and advances one page at a time; zoomed pages remain pannable. Markdown has one continuous formatted reading view; its Reading view action toggles directly to Text and back. Other semantic documents use virtual pages. Where authored text can be extracted, a compact chooser also offers **Text**. Otherwise Reading view toggles Normal/Present directly. Explicit choices are saved per hash; search temporarily uses extracted text without changing that preference.

The displayed page number follows a probe one-third down the document viewport and opens Jump to page. Its counter is separate from the durable first-visible-page scroll anchor. Position, zoom and rotation survive view changes and reopening.

Reader controls overlay the document. Home and the filename are at the top; tapping the filename opens File info from the top. Search replaces that row. The bottom dock contains document actions and Fullscreen. Confirmed-empty Notes and Contents stay hidden; load warnings and retryable failures remain reachable. Export opens from the top and groups text, JSON, page image and Print to PDF. App-wide Settings and Open stay on Home.

Appearance opens a compact bottom panel. System, Light, Dark, Paper, Original, Contrast and Invert share an adaptive grid with four or five cards where width and font size permit. Auto, Keep, Dim and Recolor set image treatment; Original and Invert bypass those policies. Paper exposes a saved roughness slider. Rotation/reset use icons; margin trimming appears where supported. Changes apply while the panel stays open. Reader choices belong to that document; Home settings establish defaults for new documents.

Notes and comments appear below pages in portrait and alongside them in landscape. There is no separate title/close bar; the document's Notes action toggles the pane. Author and useful location metadata accompany each note. A compact filter switches between current-page and all notes; unmapped comments remain visible. Known page locations can be opened directly. Text and JSON exports include notes and report bounded omissions.

The Read document text accessibility action makes an explicit Text choice without opening search or focusing the keyboard. Return to pages or Back restores and saves the selected page view. Search returns to its previous surface when closed.

Fullscreen draws the document beneath system bars and cutouts, while overlay controls keep safe touch areas. Reader chrome hides on forward/downward scrolling and returns on backward/upward scrolling. A deliberate single tap toggles it; pinching, long presses and cancelled gestures do not count as taps. Fullscreen also uses one five-second timeout, adjusted for accessibility preferences. Search, focus, touch exploration and modal interaction keep controls available. Information/export sheets preserve immersive window flags. Back closes search or Text first, then exits fullscreen; an externally opened document then returns to its caller.

System, Light, Dark and AMOLED app themes are independent of document appearance. Neutral surfaces, dynamic accents and static grain keep the document dominant; AMOLED's main canvas stays pure black. Icon actions expose tooltips on hover/long press and retain accessible names and 48 dp touch targets. Texture is cached, with no animation timer. Paging and bounded image caches limit memory; no claim is made of zero idle CPU or instant parsing.

## Design references

- [Stanford CS147: visual design](https://hci.stanford.edu/courses/cs147/2024/au/lectures/09-visual-design-wo-tufte.pdf): hierarchy, alignment, proximity and restrained emphasis.
- MIT 6.813/6.831: [efficiency](https://web.mit.edu/6.813/www/sp18/classes/03-efficiency/), [user-centered design](https://web.mit.edu/6.813/www/sp18/classes/06-user-centered-design/), [learnability](https://web.mit.edu/6.813/www/sp18/classes/09-more-learnability/) and [graphic design](https://web.mit.edu/6.813/www/sp18/classes/13-graphic-design/). Applied to task steps, recognizable actions, feedback and progressive disclosure. These materials informed the design; they do not establish results from a usability study.
- [Reverb](https://github.com/SmallThingz/reverb/tree/0278485cd0b20630900cdd6596ecfcfd3486ea59): inspected surface, theme and toolbar source plus Library/Settings screenshots. No code or assets copied.
- [MJPDF](https://gitlab.com/mudlej_android/mj_pdf_reader): inspected reader layout and official Home/menu screenshots for preview hierarchy and document-first controls. AllDoc retains its own navigation and rendering implementation.
- Android guidance on [Compose performance](https://developer.android.com/develop/ui/compose/performance/bestpractices) and [accessible controls](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Texture provenance and earlier visual references are recorded in [UI_REFERENCE.md](UI_REFERENCE.md).
