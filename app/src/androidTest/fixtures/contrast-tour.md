# Contrast cover fixture

`../assets/theme/contrast-tour.pdf` is a 3,790-byte PDF authored for AllDoc visual QA. It contains three pages of rectangles and Helvetica text, with no images, external content or imported third-party artwork. The fixture is covered by this repository's license and is packaged only in the test APK.

SHA-256: `293ec926bf3b7057e1df259b670d97af35fd51091f049bf75c0ddf40aa2217a1`.

Page one is 600 x 840 points. Its dark left header, blue right header, white body, separate middle swatches and dark footer distinguish flat backgrounds from text. The regression preserves authored panel ink and its antialiasing while mapping paper/body text. Native widths 137, 144, 229 and 240 test tile phases; render-then-fit cases use the production Library 240 x 320 and Recents 144 x 192 bounds, including a 1080-pixel reading raster.
