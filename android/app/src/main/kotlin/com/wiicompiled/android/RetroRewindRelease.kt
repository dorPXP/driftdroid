package com.wiicompiled.android

/**
 * One pinned, known-good Retro Rewind release, fetched straight from the same official
 * update.rwfc.net/cdn.update.rwfc.net infrastructure this app's own WFC payload URL already talks
 * to (see projects/mkwii/recomp.yml's retro_wfc_payload) - not a third-party mirror. Values
 * (archive URL, exact byte size, SHA-256) are the real, current 6.12.7 release as published by
 * that service; every download is verified against ARCHIVE_BYTES/ARCHIVE_SHA256 before it's
 * accepted, so a corrupted or unexpectedly-changed download fails closed instead of silently
 * installing something else.
 *
 * Deliberately pinned rather than always "whatever's newest": bumping this to a new Retro Rewind
 * release means updating the hash/size here too (and re-validating our own translated Code.pul
 * support against it, per the Kamek v2 chunk-format lesson from getting 6.12.7 itself working),
 * not something to change casually.
 */
internal object RetroRewindRelease {
    const val VERSION = "6.12.7"
    const val ARCHIVE_URL = "https://cdn.update.rwfc.net/RetroRewind/zip/6.12.7-full.zip"
    const val ARCHIVE_BYTES = 1859041688L
    const val ARCHIVE_SHA256 = "ade59f3ae217944bd7c3535b3bae79d5aa7b521ba00c581a16c7c2e3ce54c349"
}
