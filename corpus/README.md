# Real-format regression corpus: staged, not executed

The manifest records 50 distinct upstream regression fixtures totaling 820,665 bytes, exact source URLs and pinned revisions, SHA-256 values, repository license references, embedded producer metadata and intended outcomes. The corpus is separate from locally generated interaction and parser fixtures.

Only SHA-256 checks for 50 fixtures and seven license/notice files completed. The attempted harness command stopped during dependency lookup with a missing cached `commons-io:commons-io:2.21.0`, before compilation or child parser execution. The execution track is paused. Do not present manifest expectations as observed results. `results/` is empty.

Not run: parser open/preview-model probes, unchanged export/reopen, edit/export/reopen, network-denial self-test, mutations, minimization, Android PDF rendering or form filling. No macros or embedded objects were executed.

Repository notices are preserved under licenses/. Fixture-by-fixture redistribution/license review remains incomplete; do not redistribute this staged corpus as an approved release artifact. `upstream/poi-SimpleNormal.xlsx` is an extra downloaded file outside the 50-file manifest and is not counted.

Format counts: DOC4, DOCX5, XLS3, XLSX4, PPT3, PPTX3, RTF4, ODT2, ODS2, ODP2, FODT/FODS/FODP1 each, PDF4, CSS/CSV/HTML/JSON/MD/SVG/TSV/TXT/XML/YAML/YML1 each. HTML→HTM and TXT→LOG are alias probes, not additional distinct files. Eighteen files have recorded producer metadata, including Microsoft Word/Excel/PowerPoint, Macintosh PowerPoint, LibreOffice and OpenOffice.org; 32 are unknown/not embedded.

Binary fixtures and generated results are deliberately ignored by Git and remain local. The manifest, notices and unexecuted harness are preserved for later review; this is not authorization to resume the paused execution track.
