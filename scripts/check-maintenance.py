#!/usr/bin/env python3
"""
Automated Security Hygiene, Dependency Fetcher, and 7-Source Lyric Provider Health Check
for Liquid Launcher (cross-platform: Windows, Linux, macOS, and GitHub Actions CI).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent

MAVEN_URLS = {
    "kuromoji-core-0.9.0.jar": "https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-core/0.9.0/kuromoji-core-0.9.0.jar",
    "kuromoji-ipadic-0.9.0.jar": "https://repo1.maven.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar",
    "snakeyaml-2.7.jar": "https://repo1.maven.org/maven2/org/yaml/snakeyaml/2.7/snakeyaml-2.7.jar",
}

FORBIDDEN_EXTS = {
    ".apk", ".apks", ".aab", ".idsig", ".dex", ".jar", ".so", ".o", ".a",
    ".keystore", ".jks", ".p12", ".pfx", ".pem", ".key", ".crt", ".cer",
    ".cookies", ".db", ".sqlite", ".log", ".exe", ".dll", ".pyc", ".class",
}

STRICT_PATTERNS = [
    ("personal_user_path", re.compile(r"(?i)C:[\\/]+Users[\\/]+(?!<your-user>)[A-Za-z0-9._-]+")),
    ("private_lan_31", re.compile(r"192\.168\.31\.")),
    ("debug_keystore_pass", re.compile(r"(?i)(androiddebugkey|pass:android|debug\.keystore)")),
    ("private_key_block", re.compile(r"BEGIN [A-Z ]*PRIVATE KEY")),
    ("github_token", re.compile(r"(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})")),
    ("google_api_key", re.compile(r"AIza[0-9A-Za-z_-]{20,}")),
    ("openai_key", re.compile(r"sk-[A-Za-z0-9]{20,}")),
    ("aws_key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("spotify_cookie", re.compile(r"(?i)sp_dc\s*=")),
]

PROVIDERS = [
    ("LRCLIB", "https://lrclib.net/api/search?track_name=Idol&artist_name=YOASOBI"),
    ("Better Lyrics (Boidu)", "https://lyrics-api.boidu.dev/getLyrics?s=Idol&a=YOASOBI&d=213"),
    ("Better Lyrics (API)", "https://api.betterlyrics.org/getLyrics?s=Idol&a=YOASOBI&d=213"),
    ("Unison (Better Lyrics)", "https://unison.betterlyrics.org/lyrics?song=Idol&artist=YOASOBI&duration=213"),
    ("LyricsPlus", "https://lyricsplus.prjktla.my.id/v2/lyrics/get?title=Idol&artist=YOASOBI&duration=213"),
    ("LrcRed (TTML Search)", "https://lrc.red/search.json?q=YOASOBI%20Idol"),
    ("KuGou Direct", "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=YOASOBI%20-%20Idol&duration=213000"),
]


def iter_repo_files():
    skip_dirs = {".git", "build", "dist", "work", "classes", "dex", "target", "__pycache__"}
    for path in ROOT.rglob("*"):
        if not path.is_file():
            continue
        rel_parts = path.relative_to(ROOT).parts
        if any(part in skip_dirs for part in rel_parts):
            continue
        if rel_parts[0] == "libs" and path.suffix.lower() == ".jar":
            continue
        yield path


def run_offline_audit() -> None:
    print("=== [1/2] Security & Repository Hygiene Audit ===")
    checksum_file = ROOT / "libs" / "checksums.json"
    if not checksum_file.is_file():
        raise SystemExit("ERROR: Missing libs/checksums.json")
    checksums = json.loads(checksum_file.read_text(encoding="utf-8"))
    for name, digest in checksums.items():
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise SystemExit(f"ERROR: Invalid SHA-256 digest for {name} in libs/checksums.json")

    file_count = 0
    leaks = []
    bad_files = []
    for path in iter_repo_files():
        file_count += 1
        rel = path.relative_to(ROOT).as_posix()
        if path.name == ".env" or path.suffix.lower() in FORBIDDEN_EXTS:
            bad_files.append(rel)
            continue
        if path.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for label, pattern in STRICT_PATTERNS:
            for match in pattern.finditer(text):
                line_no = text.count("\n", 0, match.start()) + 1
                leaks.append(f"{rel}:{line_no} [{label}]")

    if bad_files:
        raise SystemExit(f"ERROR: Forbidden binary/env files found: {bad_files}")
    if leaks:
        raise SystemExit("ERROR: Potential secret/path leaks found:\n  " + "\n  ".join(leaks))
    print(f"AUDIT_OK: {file_count} tracked files scanned, 0 leaks, 0 forbidden binaries.")


def fetch_pinned_libs() -> None:
    print("=== Fetching & Verifying Pinned Maven Libraries ===")
    libs_dir = ROOT / "libs"
    checksums = json.loads((libs_dir / "checksums.json").read_text(encoding="utf-8"))
    ctx = ssl.create_default_context()
    for name, url in MAVEN_URLS.items():
        expected = checksums[name].lower()
        dest = libs_dir / name
        if not dest.is_file():
            print(f"Downloading {name} ...")
            req = urllib.request.Request(url, headers={"User-Agent": "LiquidLauncher-Build/3.3"})
            with urllib.request.urlopen(req, timeout=30, context=ctx) as resp:
                dest.write_bytes(resp.read())
        actual = hashlib.sha256(dest.read_bytes()).hexdigest().lower()
        if actual != expected:
            dest.unlink(missing_ok=True)
            raise SystemExit(f"ERROR: SHA-256 mismatch for {name}: expected {expected}, got {actual}")
        print(f"VERIFIED: {name} ({actual})")


def check_providers(min_healthy: int = 3) -> None:
    print("=== Checking 7 Parallel Lyric Providers ===")
    ctx = ssl.create_default_context()
    rows = []
    healthy = 0
    for name, url in PROVIDERS:
        t0 = time.monotonic()
        status_str = "DOWN"
        code_str = "-"
        note = ""
        try:
            req = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "Mozilla/5.0 (Linux; Android 12; Android TV) LiquidLauncher/3.3",
                    "Accept": "application/json, text/plain, */*",
                },
            )
            with urllib.request.urlopen(req, timeout=8, context=ctx) as resp:
                body = resp.read(65536)
                ms = int((time.monotonic() - t0) * 1000)
                code_str = str(resp.status)
                if 200 <= resp.status < 300 and len(body) > 10:
                    status_str = "UP"
                    healthy += 1
                    note = f"{len(body)} bytes"
                else:
                    status_str = "WARN"
                    note = f"Empty/short body ({len(body)} B)"
        except urllib.error.HTTPError as e:
            ms = int((time.monotonic() - t0) * 1000)
            code_str = str(e.code)
            status_str = "WARN"
            note = f"HTTP {e.code}"
        except Exception as e:
            ms = int((time.monotonic() - t0) * 1000)
            status_str = "WARN"
            note = type(e).__name__
        rows.append((name, status_str, code_str, f"{ms} ms", note))
        print(f"  [{status_str:<4}] {name:<24} HTTP={code_str:<4} Latency={ms:>5} ms  ({note})")

    md_lines = [
        "### Lyric Providers Health Summary",
        "",
        f"- **Healthy Providers:** `{healthy}/{len(PROVIDERS)}` (parallel fallback engine requires >= `{min_healthy}`)",
        "",
        "| Provider | Status | HTTP | Latency | Details |",
        "| :--- | :---: | :---: | :---: | :--- |",
    ]
    for name, st, code, lat, note in rows:
        badge = "🟢 UP" if st == "UP" else "🟡 WARN"
        md_lines.append(f"| **{name}** | {badge} | `{code}` | `{lat}` | {note} |")
    summary_md = "\n".join(md_lines) + "\n"

    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        Path(summary_path).write_text(summary_md, encoding="utf-8")

    if healthy < min_healthy:
        raise SystemExit(f"ERROR: Only {healthy}/{len(PROVIDERS)} lyric providers responded cleanly (min {min_healthy}).")
    print(f"PROVIDERS_OK: {healthy}/{len(PROVIDERS)} active.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Liquid Launcher maintenance & CI checks")
    parser.add_argument("--offline", action="store_true", help="Run offline security & hygiene audit")
    parser.add_argument("--fetch-libs", action="store_true", help="Download and SHA-256 verify Maven libraries")
    parser.add_argument("--providers", action="store_true", help="Probe live HTTPS lyric providers")
    args = parser.parse_args()

    if not (args.offline or args.fetch_libs or args.providers):
        args.offline = True
        args.providers = True

    if args.offline:
        run_offline_audit()
    if args.fetch_libs:
        fetch_pinned_libs()
    if args.providers:
        check_providers()


if __name__ == "__main__":
    main()
