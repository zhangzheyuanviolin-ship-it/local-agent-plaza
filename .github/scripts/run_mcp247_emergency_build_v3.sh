#!/usr/bin/env bash
set -euxo pipefail

# Materialize the existing fail-closed v2 harness unchanged, except for two audit-design corrections:
# 1. MCP247_QWEN35_VERIFIED_2B_CPU_ONLY is a Kotlin source comment and is intentionally not a runtime
#    artifact. Source presence is already gated earlier with grep. The APK/DEX audit must validate only
#    runtime strings that can legitimately survive compilation (release tag, SHA, golden engines/modules).
# 2. Android build-tools 37 apksigner prefixes certificate lines with "V3.0 Signer:" instead of the
#    older "Signer #1" wording. Parse the stable certificate SHA-256 field and then fail closed on the
#    exact release certificate digest used by the MCP upgrade line.
BASE_ORIG='.github/scripts/run_mcp247_emergency_build.sh'
BASE_FIXED="$RUNNER_TEMP/run_mcp247_emergency_build_runtime_audit_fixed.sh"
V2_ORIG='.github/scripts/run_mcp247_emergency_build_v2.sh'
V2_FIXED="$RUNNER_TEMP/run_mcp247_emergency_build_v3_materialized.sh"
EXPECTED_CERT_SHA256='38a9a4f15ed53f47abee1a0343b2fe3d825687acb148ac8c522fa1d29f3e292d'

python3 - "$BASE_ORIG" "$BASE_FIXED" "$EXPECTED_CERT_SHA256" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
expected_cert=sys.argv[3]
old="for marker in (b'MCP247_QWEN35_VERIFIED_2B_CPU_ONLY', os.environ['MODEL_RELEASE_TAG'].encode(),\n"
new="for marker in (os.environ['MODEL_RELEASE_TAG'].encode(),\n"
assert src.count(old)==1, f'brittle source-comment DEX marker anchor count={src.count(old)}'
src=src.replace(old,new,1)
old_cert="""CERT_SHA=\"$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' \"$RUNNER_TEMP/final_certs.txt\" | head -n1 | tr -d '\\r')\"\ntest -n \"$CERT_SHA\"\n"""
new_cert=f"""CERT_SHA=\"$(sed -n -E 's/^.*certificate SHA-256 digest: ([0-9A-Fa-f]{{64}})\\r?$/\\1/p' \"$RUNNER_TEMP/final_certs.txt\" | head -n1 | tr 'A-F' 'a-f')\"\n[[ \"$CERT_SHA\" =~ ^[0-9a-f]{{64}}$ ]]\ntest \"$CERT_SHA\" = \"{expected_cert}\"\n"""
assert src.count(old_cert)==1, f'certificate parser anchor count={src.count(old_cert)}'
src=src.replace(old_cert,new_cert,1)
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
grep -F "certificate SHA-256 digest: ([0-9A-Fa-f]{64})" "$BASE_FIXED"
grep -F "test \"\$CERT_SHA\" = \"$EXPECTED_CERT_SHA256\"" "$BASE_FIXED"
echo MCP247_V3_RUNTIME_DEX_AND_SIGNER_AUDIT_FIX_PASS
exec bash "$V2_FIXED"
