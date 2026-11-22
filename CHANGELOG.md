# Changelog

All notable changes to the **Liquid Launcher** overlay are documented in this file.

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
