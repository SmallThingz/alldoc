# OACP SDK

Unmodified Kotlin sources and Android manifest from the official OACP Android SDK,
pinned in `UPSTREAM.json`. The local build wrapper uses AllDoc's Android toolchain;
no opaque AAR or additional runtime dependency is included.

The upstream license is packaged as `src/main/assets/oacp-sdk-license.txt` and
appears in AllDoc's licenses screen. The wrapper intentionally omits upstream's
blanket keep rule so unused SDK classes can be removed by the app shrinker.

To update, review a new upstream revision, replace the seven Kotlin files and
manifest byte-for-byte, copy its license, and refresh the commit and hashes in
`UPSTREAM.json`. Do not change the projected files locally. AllDoc owns its
bounded document adapter and caller authorization outside this module.
