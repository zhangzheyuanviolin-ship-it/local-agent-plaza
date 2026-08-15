#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ABI = "arm64-v8a"
EXPECTED = {
    "libLiteRt.so": "da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8",
    "libLiteRtClGlAccelerator.so": "4b19f18f4ba9b1bde6060def4388b74d07f939db798c8c77c4f4e5125aeabcb1",
    "liblitert_jni.so": "a98ea95cbb5ac4581bf483fa90df66454e15b965f4fa94a1b169a60319f4dc9a",
}
CRITICAL = {"LiteRtCreateModelFromFd", "LiteRtGetBlockWiseQuantization"}
GOLDEN_REMOVED_VS_MCP189 = {
    "LiteRtGetCompiledModelEnvironment",
    "LiteRtSetEnvironmentOptionsValue",
}
DEX_REQUIRED = {
    b"com/google/ai/edge/gallery/customtasks/musicgeneration/box049/Box049Bridge":
        "generated Box 0.4.9 bridge",
    b"com/google/ai/edge/gallery/customtasks/musicgeneration/box049/OfficialSoundGenEngine":
        "effective golden Box 0.4.9 engine",
    b"com/google/ai/edge/gallery/customtasks/musicgeneration/GoldenBox049RuntimeEngine":
        "Plaza golden-runtime adapter",
    b"30036c4c3db7da656672a9490f9f821105068daf":
        "pinned golden commit fingerprint",
    b"box-0.4.9-golden-runtime-r1":
        "fresh music model cache version",
}


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT)


def symbols(path: Path, want_undefined: bool) -> set[str]:
    out = run("readelf", "-Ws", str(path))
    result: set[str] = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 8 or not parts[0].endswith(":"):
            continue
        ndx, name = parts[6], parts[7].split("@", 1)[0]
        if (ndx == "UND") != want_undefined:
            continue
        if name:
            result.add(name)
    return result


def needed_libraries(path: Path) -> set[str]:
    out = run("readelf", "-d", str(path))
    return set(re.findall(r"Shared library: \[([^]]+)\]", out))


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: audit_box_music_apk.py <apk>")
    apk = Path(sys.argv[1]).resolve()
    if not apk.is_file():
        raise RuntimeError(f"APK not found: {apk}")
    if shutil.which("readelf") is None:
        raise RuntimeError("readelf is required for the LiteRT ABI audit")

    with tempfile.TemporaryDirectory(prefix="box-music-audit-") as tmp_name:
        tmp = Path(tmp_name)
        with zipfile.ZipFile(apk) as zf:
            members = [
                n for n in zf.namelist()
                if n.startswith(f"lib/{ABI}/") and n.endswith(".so")
            ]
            if not members:
                raise RuntimeError(f"APK contains no {ABI} native libraries")
            for member in members:
                zf.extract(member, tmp)

            dex_members = sorted(
                n for n in zf.namelist()
                if re.fullmatch(r"classes\d*\.dex", Path(n).name)
            )
            if not dex_members:
                raise RuntimeError("APK contains no classes*.dex")
            dex_blob = b"".join(zf.read(name) for name in dex_members)

        missing_dex = [
            label for needle, label in DEX_REQUIRED.items() if needle not in dex_blob
        ]
        if missing_dex:
            raise RuntimeError(
                "Golden Box 0.4.9 runtime is not fully wired into the APK: "
                + ", ".join(missing_dex)
            )

        lib_dir = tmp / "lib" / ABI

        for lib, expected_hash in EXPECTED.items():
            path = lib_dir / lib
            if not path.is_file():
                raise RuntimeError(f"APK is missing required Box 0.4.9 runtime library: {lib}")
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            if actual != expected_hash:
                raise RuntimeError(
                    f"{lib} is not the device-validated Box 0.4.9 binary: "
                    f"sha256={actual}, expected={expected_hash}"
                )

        core = lib_dir / "libLiteRt.so"
        jni = lib_dir / "liblitert_jni.so"
        jni_required = {s for s in symbols(jni, True) if s.startswith("LiteRt")}
        core_provided = {s for s in symbols(core, False) if s.startswith("LiteRt")}
        missing = sorted(jni_required - core_provided)
        if missing:
            raise RuntimeError(
                "LiteRT JNI/Core ABI mismatch. Missing symbols: " + ", ".join(missing)
            )
        absent_critical = sorted(CRITICAL - core_provided)
        if absent_critical:
            raise RuntimeError(
                "Box music critical LiteRT APIs are absent: " + ", ".join(absent_critical)
            )

        consumers: dict[str, list[str]] = {s: [] for s in GOLDEN_REMOVED_VS_MCP189}
        for so in sorted(lib_dir.glob("*.so")):
            try:
                undefined = symbols(so, True)
            except subprocess.CalledProcessError:
                continue
            for sym in GOLDEN_REMOVED_VS_MCP189:
                if sym in undefined:
                    consumers[sym].append(so.name)
        risky = {sym: libs for sym, libs in consumers.items() if libs}
        if risky:
            raise RuntimeError(
                "Pinning the Box 0.4.9 core would remove LiteRT APIs still consumed "
                "by other APK libraries: " + repr(risky)
            )

        lm_jni = lib_dir / "liblitertlm_jni.so"
        if lm_jni.is_file() and "libLiteRt.so" in needed_libraries(lm_jni):
            raise RuntimeError(
                "LiteRT-LM JNI now directly links libLiteRt.so; "
                "the Box runtime pin needs a new compatibility review"
            )

        expected_jni_deps = {
            "libLiteRt.so", "libandroid.so", "libdl.so", "libm.so", "liblog.so", "libc.so"
        }
        unexpected_jni_deps = needed_libraries(jni) - expected_jni_deps
        if unexpected_jni_deps:
            raise RuntimeError(
                "liblitert_jni.so has unexpected native dependencies: "
                + repr(sorted(needed_libraries(jni)))
            )

        print(
            f"Box music runtime audit passed: exact 0.4.9 hashes; "
            f"JNI LiteRT ABI {len(jni_required)}/{len(jni_required)} satisfied; "
            "LiteRT-LM direct-core isolation preserved; "
            "golden engine/bridge/commit/cache fingerprints present in DEX."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
