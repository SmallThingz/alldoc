# Reader UI reference

AllDoc uses Reverb's stationary background texture and the same cached, repeated
image-shader approach. The shader integration remains AllDoc code; the texture is
third-party GPL-3.0 material, not part of AllDoc's Apache-2.0 original-work grant.

- Upstream: https://github.com/SmallThingz/reverb
- Inspected revision: 0278485cd0b20630900cdd6596ecfcfd3486ea59
- Renderer: app/src/main/java/app/smallthingz/reverb/AppSurfaceStyle.kt
- Asset: app/src/main/res/drawable-nodpi/settings_noise_tile.png
- Asset revision: 8952ca1acc895ef4be7a0229709eb9975a0e36e1 (fix: remove visible noise tiling)
- Unchanged asset SHA-256: 55ce1eaa8d7c1b78eb0b8298b101aec91ca1ef1d6b467f8899380046355dc517
- Full upstream license: app/src/main/assets/reverb-noise-license.txt

The 1024x1024 tile replaces AllDoc's old 96x96 tile. It contains low-alpha black
and white grain (alpha 5-17), so the repeat is much less conspicuous; it remains a
repeated texture, not an aperiodic procedural field. One immutable bitmap and
shader are shared across backgrounds, decoded off the main thread. There is no
animation timer, generated frame or per-composition bitmap allocation.

Only screen and reader backgrounds use this texture. Buttons, bars, cards and
raised panels remain flat. AMOLED backgrounds stay pure black and skip initial
texture loading. Document paper grain is a separate reading-theme effect and is
unchanged.

The asset is 667,307 encoded bytes; an ARGB decode is at most 4 MiB. Compared with
the old tile this adds 662,405 encoded bytes and at most 4,157,440 decoded bytes.
Once loaded, the bitmap remains shared for the process lifetime. These are asset
bounds, not measured APK size, GPU residency, frame timing or idle CPU results.
Optional texture loading falls back to a flat background on allocation failure.
