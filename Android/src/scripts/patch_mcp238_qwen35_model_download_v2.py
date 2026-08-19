#!/usr/bin/env python3
"""Run the MCP238 patch after making its generated Kotlin URL expression Python-3.11-safe."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
legacy = ROOT / "scripts/patch_mcp238_qwen35_model_download.py"
source = legacy.read_text(encoding="utf-8")

# The first MCP238 patch used a backslash-containing expression inside an f-string. Python 3.11
# rejects that syntax before execution. Rewrite only that expression in memory and execute the
# otherwise identical, reviewed patch. The repository copy of v1 is retained as provenance.
suffix = ".join(PART_URLS)}"
pos = source.find(suffix)
if pos < 0:
    raise SystemExit("MCP238 v2: PART_URLS f-string expression anchor was not found")
start = source.rfind("{", 0, pos)
end = pos + len(suffix)
if start < 0:
    raise SystemExit("MCP238 v2: opening brace for PART_URLS expression was not found")
expression = source[start:end]
if "PART_URLS" not in expression:
    raise SystemExit(f"MCP238 v2: unexpected expression: {expression!r}")
source = source[:start] + "{PART_URLS_KOTLIN}" + source[end:]

insert_anchor = 'repo_insert = f"""'
if source.count(insert_anchor) != 1:
    raise SystemExit(
        f"MCP238 v2: expected one repo_insert anchor, found {source.count(insert_anchor)}"
    )
source = source.replace(
    insert_anchor,
    'PART_URLS_KOTLIN = "\\\\n".join(PART_URLS)\n' + insert_anchor,
    1,
)

compile(source, str(legacy), "exec")
namespace = {"__file__": str(legacy), "__name__": "__main__"}
exec(compile(source, str(legacy), "exec"), namespace, namespace)
