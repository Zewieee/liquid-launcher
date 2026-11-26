# Changelog

All notable changes to the **Liquid Launcher** overlay are documented in this file.

## [3.3-fix.147] - 2026-11-27

### UI Bugfix (`MiniPlayer`)
- **Unclipped Music Play/Pause & Control Rows (`MiniPlayer`):** Disabled child/padding clipping (`MiniPlayer.unclipped(...)`) across `controlsHud`, `progressRow`, `actionRow`, `creditRow`, and the timing-offset dialog row so the focused `44dp` circular Play/Pause button (`1.08x` scale + `-1.5dp` lift) and bottom pills are never sliced at the top or bottom edges.
- **Exact Fractional-Density Circle Radius & Timeline Thumb Clearance:** Derived transport button corner radii directly from pixel diameter (`diameter * 0.5f`) and increased focused `Timeline` horizontal padding from `5.5dp` to `7.0dp` so the `6.8dp` thumb halo stays unclipped at `0%` and `100%`.
- **Verification:** Added `Fix147PlayClipCheck` (29/29 default ART suites passing).

## [3.3-fix.146] - 2026-11-23

### Codebase-Wide Performance & Reliability (Rust/JNI + Java)
- **Lens RAM Release & Single-Pass Native Kernel (`FastPixelOps`, `pixels.rs`, `pixels_jni.c`, `lib.rs`):** `FastPixelOps.trimMemory` now frees `Shamsi` lens source/result buffers (~4 MB when backgrounded); `pixels.rs` folds finite/bounds validation into a single pass (eliminating `5 × W × H` per-frame pre-scans); `pixels_jni.c` reuses C output buffers and frees them via `RustBlur.trimNative -> lp_trim()`.
- **Shared Static `LiveLyrics` Worker Pools:** Replaced per-instance `LiveLyrics` thread pools with shared auto-expiring static `WORKER` and `IO_POOL` executors, eliminating 9-thread spawn/teardown churn per popup session.
- **`O(1)` Artwork Revision Cache (`MochyMediaBrowser`, `SpotifyMetadata`):** Added a 4-slot `RevisionSlot` ring cache keyed by `Bitmap.getGenerationId()`, reducing steady-state 1s metadata polls to `0` `Bitmap.getPixel` JNI calls and `0` `SHA-256` allocations.
- **Zero-JNI 60fps Karaoke Sweep (`RubyLyricsView`):** Cached cumulative codepoint prefix advances (`cpMetrics`) in `Cell.prefixWidth` so word-synced karaoke rendering makes `0` per-frame `Paint.measureText` JNI calls.
- **Streaming `sharpen` & Single-Pass HSV Backdrop (`FastPixelOps`, `MusicBackdropView`):** Rewrote `FastPixelOps.sharpen` with rotating 3-row buffers (`-66.7%` `Bitmap.getPixels` JNI calls, bit-exact), folded redundant JNI pre-blurs into `boxBlurAndToneMapWorker`, and reused single-pass HSV worker buffers.
- **Adaptive `Unison` 404 Cooldown (`LiveLyrics`):** Added a 15-minute cooldown after 2 consecutive HTTP `404` responses from `unison.betterlyrics.org` with dynamic `expectedFast`/`expectedWord` race counts.
- **Resilient Japanese Ruby Conversion (`JapaneseRomaji`):** Preserved romaji annotations for known Japanese tokens on lyric lines containing rare/unknown Kanji while keeping `alias()` strict for title matching.
- **Verification:** Added `Fix146ImproveCheck` (28/28 default ART suites, 6,838 assertions passing).

## [3.3-fix.145] - 2026-11-22

### Performance & Architecture
- **Zero-Rebind Media Discovery (`SpotifyMetadata.DirectHub`):** Shared ref-counted `MochyMediaBrowser` and `GenericMediaBrowser` sessions with a `750 ms` grace handoff between Dock and Music Popup.
- **Auto-Expiring Worker Pools & Unified Memory Trimming:** Switched 4 background single-thread executors to `ThreadPoolExecutor` with `allowCoreThreadTimeOut(true)`. Expanded `MemoryTrimmer` coverage to `WallpaperTheme`, `LiveLyrics`, `SpotifyMetadata`, `WallpaperThumbLoader.sFastKeyCache`, and lazy `MusicBackdropView` scratch buffers.
- **Zero-Allocation 60fps Lyrics Renderer:** Cached `FontChoice.faceEpoch()`, `Cell.blank`, and special `LineBlock` instances in `RubyLyricsView` to eliminate per-frame churn during smooth karaoke playback.
- **Instant Single-Track Prefetch:** Added a 2-second debounced transient RAM prefetch in `DockMusicPill` for the currently playing track only (no disk cache, no bulk scraping).
- **Verification:** Added `Fix145OptimizeCheck` (27/27 default ART suites passing).

## [3.3-fix.144] - 2026-11-22

### Lyric Matching Accuracy
- Added Japanese corner-quote title extraction (`「...」`, `『...』`), `Artist - Title` normalization, and upload/MV suffix stripping in `LyricMatch`.
- Introduced multi-factor `Lyrics.quality` scoring and improved best-candidate selection in `AggregateLyrics`.
- Added TV-size cut rejection (`isTruncatedForDuration`) and two-tier conditional `±10 s` duration tolerance for music videos.
- **Verification:** Added `Fix144AccuracyCheck` and `LiveParallelLyricsCheck`.

## [3.3-fix.143] - 2026-11-22

### Visuals
- Added 60fps VSync-driven, zero-permission Liquid Glass `MusicVisualizerView` (`MODE_DOCK` 4 backlit capsules and `MODE_STAGE` dual-layer silk aura ribbon with 28 symmetric floating glass capsules).
- **Verification:** Added `Fix143VisualizerCheck`.

## [3.3-fix.142] - 2026-11-21

### Improvements
- Refined media metadata normalization, lyric source prioritization, and popup/dock state synchronization (`Fix142Check`).

## [3.3-fix.141] - 2026-11-21

### Security & Stability
- Hardened lyric URL validation, path-traversal guards, and XXE-resistant XML parsing for TTML/QRC providers (`Fix141Check`).

## [3.3-fix.140] - 2026-11-21

### Lyrics & Media
- Expanded parallel multi-source lyric lookup and BitChord/direct `MediaBrowserService` discovery (`Fix140LyricsCheck`, `BitChordLyricsCheck`).
