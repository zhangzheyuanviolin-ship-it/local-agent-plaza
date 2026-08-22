#!/usr/bin/env bash
set -euxo pipefail

ORIG='.github/scripts/run_mcp249_agent_family_compat_build.sh'
FIXED="$RUNNER_TEMP/run_mcp249_agent_family_compat_build_v3_materialized.sh"

python3 - "$ORIG" "$FIXED" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()

# Freeze the exact MCP248 product inventory; never follow mutable HF repository HEADs in MCP249.
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
    if c < 1: raise SystemExit(f'MCP249 v3 pin anchor missing: {old!r}')
    src=src.replace(old,new)

# Materialize the already-proven MCP218 wire/search-required layer before MCP249 edits the same
# helper. Gradle will subsequently re-run MCP218 idempotently, exactly as in MCP247/MCP248.
old='''python3 -m py_compile .github/scripts/patch_mcp249_ministral_phi_compat.py
python3 .github/scripts/patch_mcp249_ministral_phi_compat.py
'''
new='''python3 Android/src/scripts/patch_tool_call_wire_compat.py
python3 -m py_compile .github/scripts/patch_mcp249_ministral_phi_compat_v2.py
python3 .github/scripts/patch_mcp249_ministral_phi_compat_v2.py
'''
if src.count(old)!=1: raise SystemExit(f'MCP249 v3 target-patch anchor count={src.count(old)}')
src=src.replace(old,new,1)

# MCP218's CompatToolCallWireAdapter is now deliberately materialized before the narrow-delta audit.
# It is protected existing behavior, not a new MCP249 feature.
old_line="  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt'\n"
new_line=old_line+"  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/CompatToolCallWireAdapter.kt'\n"
if src.count(old_line)!=1: raise SystemExit(f'MCP249 v3 delta anchor count={src.count(old_line)}')
src=src.replace(old_line,new_line,1)

assert 'freeze_mcp248_models.py' not in src
assert 'MCP248_FREEZE_JSON' not in src
assert 'pin_mcp249_model_pool.py' in src
assert 'patch_tool_call_wire_compat.py' in src
assert 'patch_mcp249_ministral_phi_compat_v2.py' in src
assert 'CompatToolCallWireAdapter.kt' in src
Path(sys.argv[2]).write_text(src)
PY

chmod +x "$FIXED"
bash -n "$FIXED"
grep -F 'pin_mcp249_model_pool.py' "$FIXED"
grep -F 'patch_tool_call_wire_compat.py' "$FIXED"
grep -F 'patch_mcp249_ministral_phi_compat_v2.py' "$FIXED"
grep -F 'CompatToolCallWireAdapter.kt' "$FIXED"
! grep -F 'freeze_mcp248_models.py' "$FIXED"
exec bash "$FIXED"
