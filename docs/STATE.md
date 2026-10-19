# Reading state and source copies

## Identity

The reader hashes the complete admitted document with SHA-256 while making its bounded working snapshot. Parsing and hashing therefore refer to the same bytes, including when an external provider changes later. Names, URI paths and modification times are not identity keys. Equal content shares a record; edits that change content create a different record.

The state database uses the 32-byte digest as its primary key. It stores the first visible page and fractional scroll anchor, vertical/horizontal direction, explicit reading mode, color and image policies, Paper roughness, margin and notes-pane visibility, quarter-turn rotation, zoom, normalized center in unrotated document coordinates, and a character anchor with independent text zoom. Markdown also retains one horizontal source-glyph anchor for the last panned code block or table; it does not replace the vertical reading position. The displayed page counter follows a probe one-third down the document viewport; that counter does not replace the restoration anchor. Layout changes clamp the displayed viewport without replacing the saved document anchor. Gesture updates stay in memory; completed interactions and lifecycle changes enqueue ordered background saves.

New documents start in Normal view with vertically scrolling pages. Present fits and snaps horizontal pages. Markdown remains one continuous formatted document, with its position saved independently of the viewport. An explicit Text choice is saved when extraction is supported; search uses text temporarily without replacing the chosen view. Both retain the text's UTF-16 character anchor. Returning to Normal or Present saves that choice. Notes-pane visibility belongs to the document hash; identical bytes opened through another source share it, while changed bytes start with the pane hidden. The pane sits below pages in portrait and beside them in landscape. Database upgrades preserve reading anchors and default newly introduced notes/roughness fields to hidden/zero.

System is the initial document appearance. Changes while reading belong to that hash; Home settings establish defaults for new documents and cover colors. Existing hashes keep their saved choices. The independent app theme—System, Light, Dark or AMOLED—is stored globally and does not change document colors.

History is capped at 1,000,000 records, ordered by last use. Eviction removes the least recently used records. Late state writes do not recreate an evicted record. Source metadata is updated independently of reading position.

## Recoverability

A local file URI is considered recoverable. A content URI is recoverable only when AllDoc actually holds an applicable persisted read grant. An incoming permission flag by itself does not make access durable. The system file picker requests persistable access where the provider supports it.

With retention enabled, supported documents opened through temporary content grants can receive a durable copy. On Android 10 and later, the default public location is Documents/AllDoc through MediaStore. A user-selected document tree is also supported. Android 9 uses the selected tree or reports a private-storage fallback. Opening individual documents and retaining temporary shares require no broad storage access. Library scanning uses optional shared-storage access and preserves any previously granted folder sources.

The importer matches copies by full hash, updates their display name when the source name changes, and keeps a separate bounded index. File URIs do not create retained copies. Persisted content grants normally reuse the original. If ownership cannot be verified and the URI cannot safely be saved for reopening, enabled retention may create a fallback copy within the configured limits. Working snapshots used during an open are temporary and have a separate lifetime.

## Library

Library keeps a separate, bounded catalog of candidates, verified document hashes and locations. Known document names appear incrementally before signature checks and full hashing; unfamiliar names are probed for supported document signatures during discovery. Candidates receive a hash only after reading their complete content. Bounded, inconclusive package probes retain compatible name/provider hints; unrecognized binary data is excluded. Container signatures identify candidates; their contents are validated when opened. Verified equal content merges its locations without adding entries to Recents. An incoming temporary document reuses an indexed original only after rechecking its complete hash. AllDoc-managed copies remain subject to their retention limits.

Library groups files by type and sorts names alphabetically within each group. Fuzzy search filters those names using a pinned Rust matcher on workers; it does not reorder matches by score or search document contents. The Library type controls persist across launches; excluded types are skipped by subsequent scans.

Only readable shared storage and granted folders are scanned. Scans run while Library is visible in the foreground and stop when it is hidden. A completed scan can prune missing locations; interrupted or inaccessible scans keep existing entries. Private-cache files proven missing are removed from the catalog without deleting any source files. Access failures alone do not establish that a file was deleted.

## Limits and cleanup

Retained copies default to 256 MiB and a safety cap of 1,000,000 documents. The inline Settings slider has fixed stops from Off through 4096 MB, then Unlimited; its MB values use 1,048,576 bytes. Unlimited removes the byte quota while retaining the count safety cap. Legacy finite quotas remain valid until changed. Reducing limits can remove older retained copies. Off disables new retained copies and does not trim existing copies. The explicit clear action removes only copies managed by AllDoc and preserves original files and reading preferences.

Copy publication and eviction are journaled. Each explicit cleanup batch selects at most 256 copies after a separately bounded recovery pass, then reconciles exact references before continuing. Invalidating an evicted reference matches its exact old URI, so a delayed cleanup cannot erase a newer copy for the same hash. Failed or cancelled admission closes its session and removes temporary work. If a provider refuses an operation, the reader reports the failure rather than claiming that a copy was saved or removed. When saved storage settings are unavailable, opening a document does not create copies or enforce guessed quotas.

Generic document-tree providers may reuse a file path for different content. AllDoc verifies these saved-copy claims against the admitted snapshot before using their old format metadata or renaming them. Changed published copies lose their stale managed reference while their provider files are preserved. Clear and quota cleanup also verify document-tree copies; unreadable files and unverifiable incomplete copies remain accounted for and report a warning. Private storage and MediaStore item identities retain their normal cleanup behavior.

The state database uses bounded recency queries and incremental reclamation. Page caches have fixed count/byte budgets and reduce image resolution when many pages share the viewport. Up to two settled visible regions receive native detail: 48 MiB of base images, 16 MiB of retained detail and up to two 512 KiB classification contexts, with one detail replacement in flight. Classification travels with the base image, so engine cache eviction cannot change a zoomed region’s colors. Pages and worksheets allow up to 32× zoom; text and Markdown reflow at 0.5×–8×. Embedded raster previews remain limited by their saved source resolution. Cached cover originals are recolored off the UI thread for the current document defaults; each theme does not create another disk cache. Static texture and settled navigation do not start timers or request continuous frames. Operating-system activity, garbage collection and external providers can still account for occasional CPU time.

## Reopening

An incoming document open takes priority over automatic resume. An explicit return to Home remains on Home at the next launch. Recent entries prefer the retained copy, then their saved recoverable source. If access has disappeared, opening the same content through the picker restores its hash-keyed preferences.

Position saving does not imply that an abruptly killed process can finish an outstanding disk write. Normal rotation, completed gestures, navigation and foreground/background transitions are the persistence boundaries. Passwords are not persisted with reading state. A readable document remains usable if its state database or copy index is unavailable; the reader warns when the session cannot be saved.
