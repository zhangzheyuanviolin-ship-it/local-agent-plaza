#!/usr/bin/env bash
set -euxo pipefail

ORIG='.github/scripts/run_mcp249_agent_family_compat_build_v3.sh'
WRAPPED="$RUNNER_TEMP/run_mcp250_from_mcp249_v3.sh"

python3 - "$ORIG" "$WRAPPED" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text(encoding='utf-8')
old='exec bash "$FIXED"\n'
new='''python3 .github/scripts/materialize_mcp250_runner.py "$FIXED"\nbash -n "$FIXED"\ngrep -F 'patch_mcp250_target_agent_families.py' "$FIXED"\ngrep -F "versionCode='350'" "$FIXED"\ngrep -F "versionName='1.0.14-mcp.250'" "$FIXED"\ngrep -F 'docs/mcp250_target_agent_result.json' "$FIXED"\ngrep -F 'MCP250_TARGET_LOOP_GUARD' "$FIXED"\nexec bash "$FIXED"\n'''
if src.count(old)!=1:
    raise SystemExit(f'MCP250 v3 wrapper final exec count={src.count(old)}')
src=src.replace(old,new,1)
Path(sys.argv[2]).write_text(src,encoding='utf-8')
PY

chmod +x "$WRAPPED"
bash -n "$WRAPPED"
grep -F 'materialize_mcp250_runner.py' "$WRAPPED"
exec bash "$WRAPPED"
