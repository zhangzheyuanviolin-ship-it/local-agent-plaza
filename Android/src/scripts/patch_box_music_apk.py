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


def load_exact_runtime() -> tuple[Path, dict[str, bytes]]:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    roots = [
        gradle_home / "caches" / "modules-2" / "files-2.1" / "com.google.ai.edge.litert" / "litert" / VERSION,
        gradle_home / "caches",
    ]
    candidates: list[Path] = []
    seen: set[Path] = set()
    for root in roots:
        if not root.exists():
            continue
        for candidate in root.rglob("*.aar"):
            if candidate not in seen:
                seen.add(candidate)
                candidates.append(candidate)

    diagnostics: list[str] = []
    for aar in candidates:
        try:
            with zipfile.ZipFile(aar) as zf:
                names = zf.namelist()
                runtime: dict[str, bytes] = {}
                for lib, expected in EXPECTED.items():
                    members = [n for n in names if n.endswith(f"/{ABI}/{lib}")]
                    if not members:
                        diagnostics.append(f"{aar}: missing {lib}")
                        break
                    data = zf.read(members[0])
                    actual = sha256(data)
                    if actual != expected:
                        diagnostics.append(f"{aar}: {lib} sha256={actual}")
                        break
                    runtime[lib] = data
                if len(runtime) == len(EXPECTED):
                    return aar, runtime
        except (OSError, zipfile.BadZipFile) as exc:
            diagnostics.append(f"{aar}: {exc}")

    raise RuntimeError(
        "Exact Box 0.4.9 / LiteRT 2.1.6 runtime AAR was not found in Gradle cache.\n"
        + "\n".join(diagnostics[-20:])
    )


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: patch_box_music_apk.py <input-apk> <output-unsigned-apk>")
    src = Path(sys.argv[1]).resolve()
    dst = Path(sys.argv[2]).resolve()
    if not src.is_file():
        raise RuntimeError(f"Input APK not found: {src}")

    aar, runtime = load_exact_runtime()
    print(f"Using byte-verified Box 0.4.9 LiteRT runtime from: {aar}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    replaced: set[str] = set()
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", allowZip64=True) as zout:
        for info in zin.infolist():
            upper = info.filename.upper()
            if upper == "META-INF/MANIFEST.MF" or (upper.startswith("META-INF/") and upper.endswith((".RSA", ".DSA", ".EC", ".SF"))):
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
