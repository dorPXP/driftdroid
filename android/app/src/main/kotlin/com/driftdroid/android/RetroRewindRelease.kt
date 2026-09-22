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
 * This is the known-good FALLBACK release, used when the live release catalog can't be reached
 * (offline, or the service is down). The app normally discovers releases from that catalog
 * instead - see RetroRewindCatalog - and only what it can actually run is offered, decided by
 * [SUPPORTED_CODE_PUL_SHA256]. Bumping this means re-translating/re-validating our own
 * statically-recompiled Code.pul support (see runtime/generated/build_shards - re-translated for
 * 6.12.8 on 2026-09-10, after RR's 6.12.8 hotfix genuinely changed Code.pul at the byte level and
 * broke the previous 6.12.7-translated build for anyone who updated).
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
     * Every Binaries/Code.pul build this app has native code for, newest last.
     *
     * A SET, not a single pin, and the thing compatibility is actually decided on - see
     * [RetroRewindCatalog]. Our Retro Rewind support is a static, ahead-of-time recompilation of
     * this exact file's game logic (see the translator's retro_rewind build shards): a Code.pul
     * that isn't in here has no translated code on the device and would desync at runtime instead
     * of failing cleanly, so it is rejected before it can replace a working install.
     *
     * Keying on this hash rather than on a version number is what lets version detection be
     * automatic: Retro Rewind releases that only change assets/tracks ship the same Code.pul (or
     * no Code.pul at all in their delta), so they are adopted with no app update at all. Adding a
     * genuinely new release means re-translating against its Code.pul and appending the hash here.
     *
     * 6.12.8 ("88cd25...") cross-checked against KartPad's v0.4.16-android.1 build profile, and
     * re-verified directly against the live 6.12.8 delta archive on update.rwfc.net.
     */
    val SUPPORTED_CODE_PUL_SHA256 =
        setOf(
            "88cd25ff08121f7c4ddb40538703f40c270f414f2dbc55a6b4b6767e62db7253", // 6.12.8
        )

    /** The build [VERSION] itself ships - the fallback when the network is unavailable and the
     * live catalog can't be consulted. */
    const val CODE_PUL_SHA256 = "88cd25ff08121f7c4ddb40538703f40c270f414f2dbc55a6b4b6767e62db7253"
}
