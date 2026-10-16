# AllDoc document API

Version 1. Android 9 (API 28) or newer. Bind explicitly to
`app.smallthingz.alldoc/app.smallthingz.alldoc.ipc.DocumentService`.
Include the small `:api` Android library; `AllDocClient` wraps the three AIDL interfaces.

1. Open your source with your own `ContentResolver` URI permission. Pass its read-only
   `ParcelFileDescriptor`, display name and optional MIME/password to `open`.
   The Kotlin client consumes the descriptor even if opening fails.
2. Wait for the callback state `ready` or `error`. `status()` returns the latest state.
   Kotlin callbacks receive `(document, state)`; use that document argument. A fast
   callback may run before `open` returns, but its document is already usable.
3. Request `text`, hierarchical `json`, or page `png`. Read the returned descriptor on
   a worker. Keep the session open to reuse its source, semantic model and bounded
   page cache. Uncached Office pages can invoke a fresh visual parser.
4. Close a result to cancel it. Close the document and connection when done.
   Client-process death closes its sessions automatically.

`ready` metadata includes `reflowable`, `continuous` and the logical `pageCount`.
`continuous=true` identifies a single continuous page, currently Markdown;
other semantic readers report their virtual page count.
PDF/Office pages keep their intrinsic layout. A Markdown PNG or print request
renders the complete page or reports the 8-million-pixel / 8,192-pixel-height
limit; no successful result silently clips its remaining content.

Inputs follow the [document format registry](../docs/FORMATS.md). Text, JSON and
PNG are output formats; they do not imply support for standalone text, data or
image files as inputs.

Page indices start at zero. PNG target width is 64–2048 pixels, with aspect ratio
preserved; engines may return a smaller bounded image.
Themes: `original` (default), `adaptive` (dark), `adaptive_light`, `system`,
`sepia`, `high_contrast`, `invert`. `system` resolves against Android appearance
for each request; explicit theme names remain fixed.
`preserveImages` defaults to true. Text and JSON are unaffected by visual themes.
PDF text depends on the available platform/native backend; inspect `textAvailable`
and handle unsupported or encrypted inputs. Fidelity and warnings describe omissions.

## Ownership and bounds

The service never opens a caller-supplied path or URI. The descriptor is the input
capability, so AllDoc cannot use its own permissions to open a client's requested file.
Streaming descriptors transfer exclusive read ownership during opening. Their nonblocking
flag is temporarily enabled and restored afterward; regular-file reads preserve the source offset.
Seekable IPC PDFs may retain an independent read handle for low-overhead access; keep
those source bytes unchanged until the session closes. Other inputs, including nonseekable
PDFs, use bounded temporary snapshots. The UI separately hashes and parses its own snapshot.
Session calls are restricted to the UID that opened them, even if the Binder is forwarded.
No document content travels inside a Binder Bundle.

Two sessions per UID, eight total; two output requests per session; two workers and eight
queued tasks. Calls fail promptly when capacity is full. Output uses reliable pipes
with bounded, nonblocking writes and a 32 MiB export ceiling. Input limits depend on
format (at most 128 MiB). Requests have a 60-second cancellation deadline.
Native PDF calls cannot be interrupted mid-call; cancellation takes effect before/after
that call. Errors propagate through the pipe instead of returning a successful truncated
image. JSON may explicitly identify a bounded partial document.

Binding keeps the document process warm without a background service. No network server,
broad storage permission or privileged agent permission is required. Cold start, parsing,
rendering and PNG encoding still cost time; this is low-overhead IPC, not zero latency.
The app's own UI uses the same engine directly.

## Agents

Agent applications can use [OACP capabilities](OACP.md) for text, JSON and PNG handoffs,
or retain a session through the AIDL client. The machine-readable
request schema is `document-request.schema.json`; exported structure follows
`document.schema.json`. This is an ordinary Android API, not a
registered Android AppFunctions endpoint; the latter currently requires Android 16+ and
experimental system access ([platform documentation](https://developer.android.com/ai/appfunctions)).

For local development with an authorized ADB device, install AllDoc and the optional
`:client-test` APK, then run:

```sh
python3 scripts/alldoc-export.py example.docx --format json > document.json
python3 scripts/alldoc-export.py example.pdf --format png --page 0 > page.png
```

This test bridge stages only the chosen local file in its own app directory, invokes
the production API from a different UID, streams the result and removes its temporary
files. It is not bundled into the reader APK and does not expose a network endpoint.
Use `:client-test:connectedDebugAndroidTest` for the cross-UID API suite.

The separate `DocumentApiTimingTest` is skipped unless instrumentation receives
`-e profile true`. Run it only after correctness passes, with the host and device quiet.
It reports a single bind and first-document open separately from median/p95 warm status
round trips and warm text streaming. Use `-e coldStart true` only when AllDoc was
force-stopped immediately before the run. Those measurements describe that device and
fixture, not a general latency guarantee.

