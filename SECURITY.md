# Security & Privacy Policy

## Scope & Guarantees

- **Zero credentials in source**: No signing keystores, passwords, cookies, OAuth tokens, API keys, or local device addresses are stored in this repository.
- **No reading of other apps' private data**: Media discovery uses only Android's public `MediaBrowserService` API (or an optional user-started ADB shell bridge reading public `MediaSession` metadata). It never reads private storage, cookies, or login tokens of third-party apps.
- **Zero microphone permission**: The 60fps Liquid Glass music visualizer (`MusicVisualizerView`) uses deterministic VSync animation rather than `RECORD_AUDIO`.
- **Strict network & parser boundaries**:
  - Lyrics lookups use HTTPS-only requests restricted to an explicit host allowlist (`LiveLyrics.allowedHost`).
  - TTML/QRC XML parsing explicitly rejects `DOCTYPE`, external entities (XXE), and oversized payloads (`ProviderLyrics`).
  - Manual search URLs and downloaded payloads are strictly validated and size-bounded before parsing.
- **No bulk offline scraping**: Prefetch warms only the single currently playing track in transient RAM (`LiveLyrics.CACHE`) while Home is focused and clears under memory pressure (`MemoryTrimmer`).

## Before Publishing or Forking

- Keep your signing `.keystore` / `.jks` file and `.env` outside this repository (both are blocked by `.gitignore`).
- Do not commit decompiled third-party base APKs (`work/` or `*.apk`) unless you hold distribution rights for them.
