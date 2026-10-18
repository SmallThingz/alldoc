Owned native scan regression

native1080.rgb.gz contains big-endian width/height followed by RGB bytes.
It is the exact 1080x1440 Android ORIGINAL render of the owned synthetic
scan-photo-large.pdf page0, including authored text and synthetic landscape.
No third-party photo is used. This is a JVM test resource, not an app asset.

PDF SHA256: e9ea83e20f5e05d38c5208470f38d30525fb0c4ba5bef0524fcf312b9cecfa6d
Original PNG SHA256: fa39feb205c13f89f2cf3bec8a20aa482ebef2d8bcb9c68d2eb8511a0bd9e652
RGB gzip SHA256: b1e53ca3a6d90bce8829c8ec563d6a6425433cb7f9036eaad1e698b17e4d610e

The fixed mask oracle was produced by an independent host scanline union
of four-connected exterior near-white components, then closed neutral
components. Tests also check authored photo bounds, body text and grayscale
antialiasing across the formerly white photo-border strip. Full native2160
and actual BitmapTheme integration remain separate device qualification.
