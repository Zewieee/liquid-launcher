# Contributing to Liquid Launcher

Thank you for your interest in contributing to **Liquid Launcher**!

## Design & Engineering Principles

1. **Frameless Liquid Glass & D-pad First:** Every interactive element must work cleanly with a standard Android TV remote D-pad (no pointer-only targets or focus traps).
2. **60fps & Low-RAM Discipline:** Avoid per-frame allocations inside `onDraw` / animation loops, release caches through `MemoryTrimmer`, and keep background threads bounded and idle-expiring.
3. **Honest Lyrics & Privacy:**
   - Highlight karaoke word/segment timing **only** when real timestamps exist.
   - Never scrape bulk offline lyrics or read private storage, cookies, or login tokens of third-party apps.
   - All network calls must enforce HTTPS, explicit host allowlists, timeouts, and size caps.
4. **Native JNI Safety:** Keep `native/` free of unnecessary external dependencies, preserve `-Wall -Wextra -Werror` on C shims, maintain 16 KB ELF page alignment, and keep dynamic `NEEDED` libraries restricted to Android NDK system libs.

## Security Hygiene (Mandatory)

Before opening a Pull Request, verify that your diff contains **none** of the following:
- `.env` files, `.keystore` / `.jks` / `.pem` files, passwords, tokens, or certificate hashes.
- Hardcoded local filesystem paths or private LAN IP addresses.
- Compiled binaries (`.apk`, `.jar`, `.dex`, `.so`, `.idsig`) or decompiled third-party base APK directories (`work/`).

## Development Workflow

1. Fork the repository and create a focused branch (`git checkout -b feat/your-change`).
2. Fetch verified libraries:
   ```powershell
   .\scripts\fetch-libs.ps1
   ```
3. Compile your changes:
   ```powershell
   .\build.ps1 -CompileOnly
   ```
4. If you have an Android TV test device, run the ART test suite (and add unit/ART assertions for your change under `validation/`):
   ```powershell
   .\bridge\build.ps1
   .\validation\run-checks.ps1 -Tv <your-tv-ip:5555>
   ```
5. Open a Pull Request describing the motivation, architectural impact, and verification results.
