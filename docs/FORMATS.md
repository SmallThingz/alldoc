# Format capabilities

| Format | Display | Edit / export | Limits |
|---|---|---|---|
| PDF | Native page rasterization, pagination, zoom | Byte-identical copy | No annotations, search, text edits or password support |
| DOCX | Offline formatted HTML preview plus content runs, headers/footers/notes | Replace existing run text in package | Preview includes tables and explicit page breaks; pagination and complex shapes are approximate, system fonts substitute originals; editing still targets existing runs |
| XLSX | Native sparse grid by worksheet part and address | Replace non-formula cell with inline string | No recalculation; formulas read-only, numbers become text; no blank-cell insertion or chart rendering |
| PPTX | Existing slide text runs, numeric part order | Replace existing text runs | Not slide-layout rendering; actual presentation relationship order may differ |
| ODT / ODP | Existing paragraph/headings text | Replace text nodes, retain elements | Not page/slide-layout rendering; advanced text constructs incomplete |
| ODS | Paragraph text inside cells | Safe string-cell text only | Numeric/formula cells read-only; no recalculation or grid layout |
| TXT / MD / CSV / TSV / LOG / JSON / XML / YAML | Plain-text source | Edit original UTF-8 / UTF-16 encoding | CSV is not a grid, Markdown/HTML is not rendered; no formulas executed |
| DOC / XLS / PPT / RTF | Unsupported | No mutation | Needs a full office application or conversion |

Untouched ZIP members retain their uncompressed bytes. Edited XML parts are serialized and can have lexical changes. This is **not** a guarantee of lossless layout or office roundtrip compatibility. Original source files are not modified. Exported edits must be reviewed in the originating/full office application.

Bounds: 32 MiB input; 64 MiB total expanded archive; 8 MiB per entry; 3,000 entries; 12,000 content blocks; 2 MiB editable plain text. Documents beyond limits fail explicitly, without partial editing. Macros, signed packages and encrypted files are rejected.

Formatted DOCX preview is independent from the edit/export engine. It uses a normalized, validated ZIP copy; user unchanged exports remain byte-identical. Preview disables alternate HTML chunks, embedded fonts, active hyperlinks, file/content access, external resources, navigation and application JavaScript bridges. The preview is not an assertion of Word layout fidelity.
