#!/usr/bin/env python3
"""Execute the MCP238 patch with the verified ten-part Qwen3.5 model metadata.

The source model was split by the conversion workflow into ten 480 MB-class artifacts. This
bootstrap updates the retained MCP238 patch in memory so the Android app downloads the stable
GitHub Release copies of those exact ten parts, reconstructs the original LiteRT-LM container,
and verifies the conversion workflow's full-file SHA-256 before exposing it to LiteRT-LM.
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
legacy = ROOT / "scripts/patch_mcp238_qwen35_model_download.py"
source = legacy.read_text(encoding="utf-8")

replacements = {
    'MODEL_SHA256 = "a3a7cd9d05242200a4f819228e7cd3987e046f5fd81b030d71eb88e4a96fcd03"':
        'MODEL_SHA256 = "d5e975f0eb5b081b2a3f5c55e65d00e5ce7e43aad10bc1d002d5df66d82e9f73"',
    'PART_URLS = [f"{RELEASE_BASE}/{MODEL_FILE}.part{i:02d}" for i in range(4)]':
        'PART_URLS = [f"{RELEASE_BASE}/{MODEL_FILE}.part{i:02d}" for i in range(10)]',
    'PART_SIZES = [1_200_000_000, 1_200_000_000, 1_200_000_000, 1_180_966_112]':
        'PART_SIZES = [480_000_000] * 9 + [460_966_112]',
    'downloads four public release assets': 'downloads ten public release assets',
    'downloads four public GitHub Release parts': 'downloads ten public GitHub Release parts',
}
for old, new in replacements.items():
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"MCP238 v3 expected one replacement anchor, found {count}: {old}")
    source = source.replace(old, new, 1)

# Python 3.11 rejects a backslash-containing expression inside the retained f-string. Rewrite that
# single expression before compilation; this is the same compatibility fix used by v2.
suffix = ".join(PART_URLS)}"
pos = source.find(suffix)
if pos < 0:
    raise SystemExit("MCP238 v3: PART_URLS f-string expression anchor was not found")
start = source.rfind("{", 0, pos)
end = pos + len(suffix)
if start < 0:
    raise SystemExit("MCP238 v3: opening brace for PART_URLS expression was not found")
expression = source[start:end]
if "PART_URLS" not in expression:
    raise SystemExit(f"MCP238 v3: unexpected PART_URLS expression: {expression!r}")
source = source[:start] + "{PART_URLS_KOTLIN}" + source[end:]

insert_anchor = 'repo_insert = f"""'
if source.count(insert_anchor) != 1:
    raise SystemExit(
        f"MCP238 v3: expected one repo_insert anchor, found {source.count(insert_anchor)}"
    )
source = source.replace(
    insert_anchor,
    'PART_URLS_KOTLIN = "\\\\n".join(PART_URLS)\n' + insert_anchor,
    1,
)

compiled = compile(source, str(legacy), "exec")
namespace = {"__file__": str(legacy), "__name__": "__main__"}
exec(compiled, namespace, namespace)
