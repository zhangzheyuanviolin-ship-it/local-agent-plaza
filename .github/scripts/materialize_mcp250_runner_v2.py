#!/usr/bin/env python3
"""Materialize MCP250 over the same MCP224 build-time golden runtime used by MCP247/248/249."""
from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: materialize_mcp250_runner_v2.py <materialized-mcp249-runner>")

target = Path(sys.argv[1])
base = Path(__file__).with_name("materialize_mcp250_runner.py")
subprocess.run([sys.executable, str(base), str(target)], check=True)
s = target.read_text(encoding="utf-8")

old_patch = '''python3 -m py_compile .github/scripts/patch_mcp250_target_agent_families.py
python3 .github/scripts/patch_mcp250_target_agent_families.py
'''
new_patch = '''# Materialize MCP224 before the MCP250 exact-model layer. This is the same generic runtime that
# Gradle already applies in the released MCP247/248/249 line; doing it here makes subsequent
# MCP250 edits post-MCP224 and lets Gradle re-run both MCP224 scripts idempotently.
python3 Android/src/scripts/patch_mcp224_model_diagnostics.py
python3 Android/src/scripts/patch_mcp224_inference_diag_context_fix.py
python3 -m py_compile .github/scripts/patch_mcp250_target_agent_families_v2.py
python3 .github/scripts/patch_mcp250_target_agent_families_v2.py
'''
if s.count(old_patch) != 1:
    raise SystemExit(f"MCP250 v2 target-patch runner anchor count={s.count(old_patch)}")
s = s.replace(old_patch, new_patch, 1)

# MCP224 is normally applied later by Gradle. Since it is deliberately materialized before the
# narrow-delta audit in MCP250, include its three additional tracked UI/model-manager files.
anchor = "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt'\n"
extra = anchor + (
    "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/modelitem/ModelItem.kt'\n"
    "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt'\n"
    "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt'\n"
)
if s.count(anchor) != 1:
    raise SystemExit(f"MCP250 v2 expected-delta anchor count={s.count(anchor)}")
s = s.replace(anchor, extra, 1)

# Fail closed on the ordering contract and preserve explicit evidence in the materialized runner.
check_anchor = "echo MCP250_PROTECTED_CORE_ROUTING_STATIC_PASS\n"
check_extra = check_anchor + '''grep -F 'Compatibility tool call accepted without a fixed step limit' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt
grep -F 'stage = "litert.model_initialize.failure"' Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt
echo MCP250_POST_MCP224_ORDERING_PASS
'''
if s.count(check_anchor) != 1:
    raise SystemExit(f"MCP250 v2 ordering check anchor count={s.count(check_anchor)}")
s = s.replace(check_anchor, check_extra, 1)

assert "patch_mcp224_model_diagnostics.py" in s
assert "patch_mcp224_inference_diag_context_fix.py" in s
assert "patch_mcp250_target_agent_families_v2.py" in s
assert "ModelManagerViewModel.kt" in s
assert "ModelItem.kt" in s
assert "LlmChatScreen.kt" in s

target.write_text(s, encoding="utf-8")
print("MCP250_RUNNER_V2_POST_MCP224_MATERIALIZATION_PASS")
