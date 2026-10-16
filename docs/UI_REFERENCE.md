# Reader UI reference

AllDoc's Compose chrome is an original implementation informed by the local Reverb project: dynamic Material colors, static fine-grain surfaces, rounded bordered action controls, grouped settings and directional gesture-driven navigation. Reverb was inspected read-only; its GPLv3 source and assets were not copied into AllDoc.

`reader_noise.png` is an original 96 by 96 RGBA tile generated from a deterministic pseudo-random distribution of black/white pixels with low alpha. A repeated image shader draws the immutable tile. There are no animated noise uniforms, frame loops, periodic timers or idle animation jobs. Navigation animation runs only for an explicit transition or back gesture and respects disabled system animators.
