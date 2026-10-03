# Legacy Office fixtures

- `legacy-basic.doc`: Apache POI 5.5.1 `test-data/document/simple.doc`, Apache-2.0.
  Source: https://raw.githubusercontent.com/apache/poi/REL_5_5_1/test-data/document/simple.doc
- `legacy-basic.xls`: generated using Apache POI 5.5.1 HSSFWorkbook with worksheet Forecast,
  row 1 values Hello XLS, 42.25, and formula B1*2. The stored formula cache is intentionally
  not recalculated, verifying the preview labels cached results as potentially stale.
- `legacy-basic.ppt`: generated using Apache POI 5.5.1 HSLFSlideShow with one slide and
  a text box containing Hello PPT. POI's default template also contains master placeholders,
  demonstrating why text-only PPT preview explicitly warns about master/hidden/duplicate text.

The XLS/PPT fixture content was created for this repository. No Office rendering or editing
fidelity is implied by these small smoke fixtures.
