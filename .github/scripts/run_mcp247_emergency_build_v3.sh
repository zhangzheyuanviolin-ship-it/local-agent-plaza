#!/usr/bin/env bash
set -euxo pipefail

# Materialize the existing fail-closed v2 harness unchanged, except for one audit-design correction:
# MCP247_QWEN35_VERIFIED_2B_CPU_ONLY is a Kotlin source comment and is intentionally not a runtime
# artifact. Source presence is already gated earlier with grep. The APK/DEX audit must validate only
# runtime strings that can legitimately survive compilation (release tag, SHA, golden engines/modules).
BASE_ORIG='.github/scripts/run_mcp247_emergency_build.sh'
BASE_FIXED="$RUNNER_TEMP/run_mcp247_emergency_build_runtime_audit_fixed.sh"
V2_ORIG='.github/scripts/run_mcp247_emergency_build_v2.sh'
V2_FIXED="$RUNNER_TEMP/run_mcp247_emergency_build_v3_materialized.sh"

python3 - "$BASE_ORIG" "$BASE_FIXED" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
old="for marker in (b'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY', os.environ['MODEL_RELEASE_TAG'].encode(),\n"
new="for marker in (os.environ['MODEL_RELEASE_TAG'].encode(),\n"
assert src.count(old)==1, f'brittle source-comment DEX marker anchor count={src.count(old)}'
src=src.replace(old,new,1)
Path(sys.argv[2]).write_text(src)
PY

python3 - "$V2_ORIG" "$V2_FIXED" "$BASE_FIXED" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
fixed=sys.argv[3]
old="BASE_SCRIPT='.github/scripts/run_mcp247_emergency_build.sh'"
new=f"BASE_SCRIPT='{fixed}'"
assert src.count(old)==1, f'v2 base-script anchor count={src.count(old)}'
src=src.replace(old,new,1)
Path(sys.argv[2]).write_text(src)
PY

chmod +x "$BASE_FIXED" "$V2_FIXED"
bash -n "$BASE_FIXED"
bash -n "$V2_FIXED"
grep -F "grep -F 'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY'" "$BASE_FIXED"
! grep -F "for marker in (b'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY'" "$BASE_FIXED"
grep -F "for marker in (os.environ['MODEL_RELEASE_TAG'].encode()" "$BASE_FIXED"
echo MCP247_V3_RUNTIME_ONLY_DEX_AUDIT_FIX_PASS
exec bash "$V2_FIXED"
