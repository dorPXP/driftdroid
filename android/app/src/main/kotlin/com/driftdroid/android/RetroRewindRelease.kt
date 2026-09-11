package com.driftdroid.android

/**
 * One pinned, known-good Retro Rewind release, fetched straight from the same official
 * update.rwfc.net/cdn.update.rwfc.net infrastructure this app's own WFC payload URL already talks
 * to (see projects/mkwii/recomp.yml's retro_wfc_payload) - not a third-party mirror. Values
 * (archive URL, exact byte size, SHA-256) are the real, current 6.12.8 release as published by
 * that service; every download is verified against ARCHIVE_BYTES/ARCHIVE_SHA256 before it's
 * accepted, so a corrupted or unexpectedly-changed download fails closed instead of silently
 * installing something else.
 *
 * Deliberately pinned rather than always "whatever's newest": bumping this to a new Retro Rewind
 * release means updating the hash/size here too and re-translating/re-validating our own
 * statically-recompiled Code.pul support against it (see runtime/generated/build_shards -
 * re-translated for 6.12.8 on 2026-09-10, after RR's 6.12.8 hotfix genuinely changed Code.pul at
 * the byte level and broke the previous 6.12.7-translated build for anyone who updated), not
 * something to change casually.
 *
 * ARCHIVE_SHA256 and CODE_PUL_SHA256 were independently cross-checked against KartPad's own
 * v0.4.16-android.1 release (chrissotraidis/kartpad), which bumped to 6.12.8 the same day and
 * publishes the identical hashes in its build profile.
 */
internal object RetroRewindRelease {
    const val VERSION = "6.12.8"
    const val ARCHIVE_URL = "https://cdn.update.rwfc.net/RetroRewind/zip/6.12.8-full.zip"
    const val ARCHIVE_BYTES = 1859035109L
    const val ARCHIVE_SHA256 = "9dc9f689b2d1bc03f7b9f49e9013f31d2ab31e9b800aa2da1f6badbd2ecf21dc"

    /**
     * SHA-256 of Binaries/Code.pul from this exact release, verified against the file this app
     * ships/downloads. Our Retro Rewind support is a static, ahead-of-time recompilation of this
     * one file's game logic (see the translator's retro_rewind build shards) - a Code.pul from any
     * other release (older or newer, including hotfixes that only bump a patch version) genuinely
     * differs at the byte level and will desync from that recompiled logic at runtime instead of
     * failing cleanly. Used to reject a manually-imported zip/folder whose Code.pul doesn't match,
     * rather than let it silently load and crash/hang (see the 6.12.8 hotfix breakage report that
     * prompted this whole version bump).
     */
    const val CODE_PUL_SHA256 = "88cd25ff08121f7c4ddb40538703f40c270f414f2dbc55a6b4b6767e62db7253"
}
