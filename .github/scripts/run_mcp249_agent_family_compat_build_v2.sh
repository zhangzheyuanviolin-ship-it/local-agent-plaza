#!/usr/bin/env bash
set -euxo pipefail

ORIG='.github/scripts/run_mcp249_agent_family_compat_build.sh'
FIXED="$RUNNER_TEMP/run_mcp249_agent_family_compat_build_pinned.sh"

python3 - "$ORIG" "$FIXED" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
repls={
  'export MCP248_FREEZE_JSON="$RUNNER_TEMP/mcp249_model_freeze.json"':'export MCP249_FREEZE_JSON="$RUNNER_TEMP/mcp249_model_freeze.json"',
  'python3 -m py_compile .github/scripts/freeze_mcp248_models.py':'python3 -m py_compile .github/scripts/pin_mcp249_model_pool.py',
  'python3 .github/scripts/freeze_mcp248_models.py':'python3 .github/scripts/pin_mcp249_model_pool.py',
  'test -s "$MCP248_FREEZE_JSON"':'test -s "$MCP249_FREEZE_JSON"',
  "p=os.environ['MCP248_FREEZE_JSON']":"p=os.environ['MCP249_FREEZE_JSON']",
  "assert d['schema']=='local-agent-plaza.mcp248-model-freeze.v1'":"assert d['schema']=='local-agent-plaza.mcp249-model-freeze.v1'",
  "json.load(open(os.environ['MCP248_FREEZE_JSON']))":"json.load(open(os.environ['MCP249_FREEZE_JSON']))",
}
for old,new in repls.items():
    c=src.count(old)
    if c < 1:
        raise SystemExit(f'MCP249 pinned wrapper anchor missing: {old!r}')
    src=src.replace(old,new)
# Freeze env references may occur in comments/strings beyond exact expressions; no dynamic MCP248
# model-pool lookup is allowed in the resulting build script.
assert 'freeze_mcp248_models.py' not in src
assert 'MCP248_FREEZE_JSON' not in src
assert 'pin_mcp249_model_pool.py' in src
assert "local-agent-plaza.mcp249-model-freeze.v1" in src
Path(sys.argv[2]).write_text(src)
PY

chmod +x "$FIXED"
bash -n "$FIXED"
grep -F 'pin_mcp249_model_pool.py' "$FIXED"
grep -F 'local-agent-plaza.mcp249-model-freeze.v1' "$FIXED"
! grep -F 'freeze_mcp248_models.py' "$FIXED"
! grep -F 'MCP248_FREEZE_JSON' "$FIXED"
exec bash "$FIXED"
