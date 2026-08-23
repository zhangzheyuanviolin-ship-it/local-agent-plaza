#!/usr/bin/env bash
set -euxo pipefail

python3 -m py_compile .github/scripts/materialize_mcp250_runner.py
python3 -m py_compile .github/scripts/materialize_mcp250_runner_v2.py
python3 -m py_compile .github/scripts/patch_mcp250_target_agent_families.py
python3 -m py_compile .github/scripts/patch_mcp250_target_agent_families_v2.py
grep -F 'MCP250_PACKAGED_TARGET_AND_PROTECTED_MARKERS_PASS' .github/scripts/materialize_mcp250_runner.py
grep -F 'MCP250_RUNNER_V2_POST_MCP224_MATERIALIZATION_PASS' .github/scripts/materialize_mcp250_runner_v2.py

ORIG='.github/scripts/run_mcp249_agent_family_compat_build_v3.sh'
WRAPPED="$RUNNER_TEMP/run_mcp250_from_mcp249_v3.sh"

python3 - "$ORIG" "$WRAPPED" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text(encoding='utf-8')
old='exec bash "$FIXED"\n'
new='''python3 .github/scripts/materialize_mcp250_runner_v2.py "$FIXED"\nbash -n "$FIXED"\ngrep -F 'patch_mcp224_model_diagnostics.py' "$FIXED"\ngrep -F 'patch_mcp250_target_agent_families_v2.py' "$FIXED"\ngrep -F "versionCode='350'" "$FIXED"\ngrep -F "versionName='1.0.14-mcp.250'" "$FIXED"\ngrep -F 'docs/mcp250_target_agent_result.json' "$FIXED"\ngrep -F 'MCP250_TARGET_LOOP_GUARD' "$FIXED"\nexec bash "$FIXED"\n'''
if src.count(old)!=1:
    raise SystemExit(f'MCP250 v3 wrapper final exec count={src.count(old)}')
src=src.replace(old,new,1)
Path(sys.argv[2]).write_text(src,encoding='utf-8')
PY

chmod +x "$WRAPPED"
bash -n "$WRAPPED"
grep -F 'materialize_mcp250_runner_v2.py' "$WRAPPED"
exec bash "$WRAPPED"
