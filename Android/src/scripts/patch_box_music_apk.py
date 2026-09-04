#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import os
import sys
import zipfile
from pathlib import Path

VERSION = "2.1.6"
ABI = "arm64-v8a"
EXPECTED = {
    "libLiteRt.so": "da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8",
    "libLiteRtClGlAccelerator.so": "4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1",
    "liblitert_jni.so": "a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a",
}
TARGETS = {f"lib/{ABI}/{name}": name for name in EXPECTED}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_exact_runtime() -> tuple[dict[str, str], dict[str, bytes]]:
    """Find each device-validated Box 0.4.9 native binary independently."""
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cache = gradle_home / "caches"
    if not cache.exists():
        raise RuntimeError(f"Gradle cache does not exist: {cache}")

    runtime: dict[str, bytes] = {}
    sources: dict[str, str] = {}
    seen_files: set[Path] = set()

    # AGP commonly expands AARs into transform directories before packaging.
    # Accept an extracted candidate only when its bytes match Box 0.4.9 exactly.
    for lib, expected_hash in EXPECTED.items():
        for path in cache.rglob(lib):
            if not path.is_file() or path in seen_files:
                continue
            seen_files.add(path)
            try:
                data = path.read_bytes()
            except OSError:
                continue
            if sha256(data) == expected_hash:
                runtime[lib] = data
                sources[lib] = str(path)
                break

    # LiteRT 2.x may split API/runtime native pieces across multiple AARs. Collect
    # each of the three files independently by immutable SHA-256 identity.
    missing = set(EXPECTED) - set(runtime)
    if missing:
        for aar in cache.rglob("*.aar"):
            if not missing:
                break
            try:
                with zipfile.ZipFile(aar) as zf:
                    names = zf.namelist()
                    for lib in list(missing):
                        expected_hash = EXPECTED[lib]
                        for member in names:
                            if not member.endswith(f"/{ABI}/{lib}"):
                                continue
                            data = zf.read(member)
                            if sha256(data) == expected_hash:
                                runtime[lib] = data
                                sources[lib] = f"{aar}!/{member}"
                                missing.remove(lib)
                                break
            except (OSError, zipfile.BadZipFile):
                continue

    missing = sorted(set(EXPECTED) - set(runtime))
    if missing:
        candidates: list[str] = []
        for lib in missing:
            hashes: set[str] = set()
            for path in cache.rglob(lib):
                if path.is_file():
                    try:
                        hashes.add(sha256(path.read_bytes()))
                    except OSError:
                        pass
            candidates.append(f"{lib}: discovered hashes={sorted(hashes)}")
        raise RuntimeError(
            "Could not locate all device-validated Box 0.4.9 LiteRT 2.1.6 binaries in Gradle cache. "
            f"Missing={missing}.\n" + "\n".join(candidates)
        )

    return sources, runtime


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_box_music_apk.py <input-apk> <output-unsigned-apk>")
    src = Path(sys.argv[1]).resolve()
    dst = Path(sys.argv[2]).resolve()
    if not src.is_file():
        raise RuntimeError(f"Input APK not found: {src}")

    sources, runtime = load_exact_runtime()
    for lib in EXPECTED:
        print(f"Pinned source {lib}: {sources[lib]}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    replaced: set[str] = set()
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", allowZip64=True) as zout:
        for info in zin.infolist():
            upper = info.filename.upper()
            if upper == "META-INF/MANIFEST.MF" or (
                upper.startswith("META-INF/") and upper.endswith((".RSA", ".DSA", ".EC", ".SF"))
            ):
                continue
            data = zin.read(info.filename)
            lib = TARGETS.get(info.filename)
            if lib is not None:
                before = sha256(data)
                data = runtime[lib]
                after = sha256(data)
                print(f"{lib}: packaged-before={before} pinned={after}")
                replaced.add(info.filename)
            zout.writestr(info, data)

        missing_entries = set(TARGETS) - replaced
        if missing_entries:
            raise RuntimeError(
                "APK is missing expected LiteRT entries: " + ", ".join(sorted(missing_entries))
            )

    print(f"Wrote unsigned runtime-pinned APK: {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
