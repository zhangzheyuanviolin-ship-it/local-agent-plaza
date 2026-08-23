#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: materialize_mcp250_runner.py <materialized-mcp249-runner>')
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str) -> None:
    global s
    c = s.count(old)
    if c != 1:
        raise SystemExit(f'MCP250 {label} anchor count={c}')
    s = s.replace(old, new, 1)

anchor = 'python3 .github/scripts/patch_mcp249_ministral_phi_compat_v2.py\n'
insert = anchor + '''export MCP250_PROTECTED_SNAPSHOT="$RUNNER_TEMP/mcp250_protected_core.json"
python3 - <<'PY250PROTECTBEFORE'
import json,os
p='model_allowlists/1_0_14.json'
d=json.load(open(p,encoding='utf-8'))
names=['LocoOperator-4B LiteRTLM','Gemma-4-12B-it (experimental)']
out={}
for name in names:
    hits=[m for m in d['models'] if m.get('name')==name]
    assert len(hits)==1,(name,len(hits))
    out[name]=hits[0]
assert out['LocoOperator-4B LiteRTLM']['modelId']=='4ntoine/LocoOperator-4B-LiteRTLM'
assert out['LocoOperator-4B LiteRTLM']['modelFile']=='model.litertlm'
assert out['LocoOperator-4B LiteRTLM']['commitHash']=='6862d30e40d1c80d7b40207d91d66dfc2bec9b6a'
assert int(out['LocoOperator-4B LiteRTLM']['sizeInBytes'])==4059223584
assert out['Gemma-4-12B-it (experimental)']['modelId']=='litert-community/gemma-4-12B-it-litert-lm'
assert out['Gemma-4-12B-it (experimental)']['modelFile']=='gemma-4-12B-it.litertlm'
assert out['Gemma-4-12B-it (experimental)']['commitHash']=='44cf85a326f79b814fa86a60af414c042755b43a'
assert int(out['Gemma-4-12B-it (experimental)']['sizeInBytes'])==6547589312
json.dump(out,open(os.environ['MCP250_PROTECTED_SNAPSHOT'],'w',encoding='utf-8'),ensure_ascii=False,sort_keys=True,separators=(',',':'))
print('MCP250_PROTECTED_CORE_SNAPSHOT_PASS')
PY250PROTECTBEFORE
python3 -m py_compile .github/scripts/patch_mcp250_target_agent_families.py
python3 .github/scripts/patch_mcp250_target_agent_families.py
python3 - <<'PY250PROTECTAFTER'
import json,os
before=json.load(open(os.environ['MCP250_PROTECTED_SNAPSHOT'],encoding='utf-8'))
d=json.load(open('model_allowlists/1_0_14.json',encoding='utf-8'))
asset=json.load(open('Android/src/app/src/main/assets/model_allowlists/1_0_14.json',encoding='utf-8'))
for name,expected in before.items():
    hits=[m for m in d['models'] if m.get('name')==name]
    ahits=[m for m in asset['models'] if m.get('name')==name]
    assert len(hits)==1 and len(ahits)==1,name
    assert hits[0]==expected,(name,'root allowlist changed')
    assert ahits[0]==expected,(name,'asset allowlist changed')
print('MCP250_PROTECTED_LOCO_GEMMA12_EXACT_ALLOWLIST_PASS')
PY250PROTECTAFTER
test -f Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Mcp250TargetAgentCompat.kt
grep -F 'PROTECTED_LOCO = "LocoOperator-4B LiteRTLM"' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Mcp250TargetAgentCompat.kt
grep -F 'PROTECTED_GEMMA12 = "Gemma-4-12B-it (experimental)"' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Mcp250TargetAgentCompat.kt
grep -F 'parseCompatToolCall(lastAgentText.content)' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt
grep -F 'Every protected/non-target model retains the original Engine construction path.' Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt
echo MCP250_PROTECTED_CORE_ROUTING_STATIC_PASS
'''
replace_once(anchor, insert, 'product injection')

# `git diff --name-only` does not include the newly-created untracked policy source; audit that file
# explicitly above, and add only the tracked AgentChatScreen change to the inherited delta list.
old_line = "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt'\n"
new_line = old_line + "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt'\n"
replace_once(old_line, new_line, 'expected tracked delta')

audit_tail = "    assert int(g[0]['defaultConfig']['maxContextLength'])==32000\n"
audit_extra = audit_tail + '''    # This insertion occurs before the inherited MCP247 DEX marker block, so materialize the
    # APK DEX bytes here as well. Keeping the audit self-contained avoids relying on a later local.
    dex=b''.join(z.read(n) for n in sorted(names) if re.fullmatch(r'classes\\d*\\.dex',n))
    for marker in (b'MCP250_MINISTRAL_STATE_V1', b'MCP250_PHI_STATE_V1',
                   b'MCP250_FALCON_STATE_V1', b'MCP250_JAN_STATE_V1',
                   b'MCP250_FASTCONTEXT_STATE_V1', b'MCP250_TARGET_LOOP_GUARD',
                   b'LocoOperator-4B LiteRTLM', b'Gemma-4-12B-it (experimental)'):
        assert marker in dex,marker
    print('MCP250_PACKAGED_TARGET_AND_PROTECTED_MARKERS_PASS')
'''
replace_once(audit_tail, audit_extra, 'packaged DEX audit')

repls = {
    "versionCode='349'": "versionCode='350'",
    "versionName='1.0.14-mcp.249'": "versionName='1.0.14-mcp.250'",
    "# 7. Package identity and version code prove it can cover-update MCP248 (code 348).": "# 7. Package identity and version code prove it can cover-update MCP249 (code 349).",
    "--title 'Local Agent Plaza MCP249 targeted Ministral and Phi Agent compatibility'": "--title 'Local Agent Plaza MCP250 isolated target-family Agent compatibility'",
    "--notes 'MCP249 preserves the fully verified MCP247 media/Agent/Qwen product baseline and the exact MCP248 eight-model pool. It changes only COMPAT behavior for the existing 4096-KV Ministral-3-3B-Instruct-2512 and Phi-4-mini-instruct artifacts: bounded larger post-tool history/result budgets, exact-family finalization rules, and low-variance COMPAT sampling. All other model protocol paths remain on the MCP248 behavior.'": "--notes 'MCP250 is the final isolated target-family experiment over MCP249. LocoOperator-4B and Gemma-4-12B retain their previously verified Agent route and model objects. Media/native generation remains on the MCP247 golden runtime. Target-only changes cover Ministral, Phi-4-mini, Falcon-H1-3B, Jan-nano, FastContext, Laguna, and Gemma-4-26B.'",
    "'schema':'local-agent-plaza.mcp249-agent-family-compat.v1'": "'schema':'local-agent-plaza.mcp250-target-agent-family.v1'",
    "'upgrade_target':'MCP248 versionCode 348'": "'upgrade_target':'MCP249 versionCode 349'",
    '"$RUNNER_TEMP/mcp249_result.json"': '"$RUNNER_TEMP/mcp250_result.json"',
    'docs/mcp249_agent_family_compat_result.json': 'docs/mcp250_target_agent_result.json',
    "'Update MCP249 Agent family compatibility acceptance'": "'Update MCP250 target-family acceptance'",
    'echo MCP249_FULLY_VERIFIED_PASS': 'echo MCP250_FULLY_VERIFIED_PASS',
    'mcp249_run.json': 'mcp250_run.json',
    'local-agent-plaza.mcp249-agent-family-compat-run.v1': 'local-agent-plaza.mcp250-target-agent-run.v1',
    "'Update MCP249 run marker'": "'Update MCP250 run marker'",
}
for old,new in repls.items():
    c=s.count(old)
    if c < 1:
        raise SystemExit(f'MCP250 identity replacement missing {old!r}')
    s=s.replace(old,new)

p.write_text(s,encoding='utf-8')
print('MCP250_RUNNER_MATERIALIZATION_PASS')
