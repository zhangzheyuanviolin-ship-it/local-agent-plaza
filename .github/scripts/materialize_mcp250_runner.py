#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: materialize_mcp250_runner.py <materialized-mcp249-runner>')
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

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
'''
if s.count(anchor) != 1:
    raise SystemExit(f'MCP250 injection anchor count={s.count(anchor)}')
s = s.replace(anchor, insert, 1)

old_line = "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt'\n"
new_line = old_line + "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt'\n" + "  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Mcp250TargetAgentCompat.kt'\n"
if s.count(old_line) != 1:
    raise SystemExit(f'MCP250 expected-delta anchor count={s.count(old_line)}')
s = s.replace(old_line, new_line, 1)

repls = {
    "versionCode='349'": "versionCode='350'",
    "versionName='1.0.14-mcp.249'": "versionName='1.0.14-mcp.250'",
    "# 7. Package identity and version code prove it can cover-update MCP248 (code 348).": "# 7. Package identity and version code prove it can cover-update MCP249 (code 349).",
    "--title 'Local Agent Plaza MCP249 targeted Ministral and Phi Agent compatibility'": "--title 'Local Agent Plaza MCP250 isolated target-family Agent compatibility'",
    "--notes 'MCP249 preserves the fully verified MCP247 media/Agent/Qwen product baseline and the exact MCP248 eight-model pool. It changes only COMPAT behavior for the existing 4096-KV Ministral-3-3B-Instruct-2512 and Phi-4-mini-instruct artifacts: bounded larger post-tool history/result budgets, exact-family finalization rules, and low-variance COMPAT sampling. All other model protocol paths remain on the MCP248 behavior.'": "--notes 'MCP250 is the final isolated target-family experiment over MCP249. LocoOperator-4B and Gemma-4-12B retain their previously verified Agent path and model objects exactly. Media/native generation remains on the MCP247 golden runtime. New behavior is restricted to Ministral, Phi-4-mini, Falcon-H1-3B, Jan-nano, FastContext, Laguna, and Gemma-4-26B: target parser/state/loop guards, Jan reasoning allowance, Laguna CPU init fallback, and a Gemma26-only Engine KV safety clamp.'",
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
for old, new in repls.items():
    c = s.count(old)
    if c < 1:
        raise SystemExit(f'MCP250 materialize missing {old!r}')
    s = s.replace(old, new)

manifest_anchor = " 'media_native':{'libLiteRt.so':'da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8',\n"
manifest_insert = " 'mcp250_target_agent':{'scope':'exact_model_only','protected_core':{'LocoOperator-4B LiteRTLM':'UNCHANGED','Gemma-4-12B-it (experimental)':'UNCHANGED'},'protected_allowlist_exact_match':True,'media_behavior_unchanged':True,'ministral_payload_alias':True,'target_loop_guard':True,'jan_native_reasoning':True,'laguna_gpu_to_cpu_fallback':True,'gemma26_engine_context_cap':2048},\n" + manifest_anchor
if s.count(manifest_anchor) != 1:
    raise SystemExit(f'MCP250 manifest anchor count={s.count(manifest_anchor)}')
s = s.replace(manifest_anchor, manifest_insert, 1)

pre_build_anchor = 'echo MCP249_EXACT_MCP248_MODEL_POOL_IDENTITY_PASS\n'
pre_build_insert = pre_build_anchor + '''grep -F 'PROTECTED_LOCO = "LocoOperator-4B LiteRTLM"' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Mcp250TargetAgentCompat.kt
grep -F 'PROTECTED_GEMMA12 = "Gemma-4-12B-it (experimental)"' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/Mcp250TargetAgentCompat.kt
grep -F 'parseCompatToolCall(lastAgentText.content)' Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt
grep -F 'Every protected/non-target model retains the original Engine construction path.' Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt
echo MCP250_PROTECTED_CORE_ROUTING_STATIC_PASS
'''
if s.count(pre_build_anchor) != 1:
    raise SystemExit(f'MCP250 protected static anchor count={s.count(pre_build_anchor)}')
s = s.replace(pre_build_anchor, pre_build_insert, 1)

# Independent packaged-Dex proof for all MCP250 target branches and protected exact-name guards.
audit_anchor = 'echo MCP247_CRITICAL_SYMBOL_AND_LITERTLM_ISOLATION_PASS\n'
audit_insert = audit_anchor + '''export APK
python3 - <<'PY250DEX'
import os,re,zipfile
apk=os.environ['APK']
with zipfile.ZipFile(apk) as z:
    names=z.namelist()
    dex=b''.join(z.read(n) for n in sorted(names) if re.fullmatch(r'classes\\d*\\.dex',n))
    markers=[
      b'MCP250_MINISTRAL_STATE_V1',b'MCP250_PHI_STATE_V1',b'MCP250_FALCON_STATE_V1',
      b'MCP250_JAN_STATE_V1',b'MCP250_FASTCONTEXT_STATE_V1',b'MCP250_TARGET_LOOP_GUARD',
      b'LocoOperator-4B LiteRTLM',b'Gemma-4-12B-it (experimental)'
    ]
    for marker in markers:
        assert marker in dex,marker
print('MCP250_PACKAGED_TARGET_AND_PROTECTED_MARKERS_PASS')
PY250DEX
'''
if s.count(audit_anchor) != 1:
    raise SystemExit(f'MCP250 packaged DEX audit anchor count={s.count(audit_anchor)}')
s = s.replace(audit_anchor, audit_insert, 1)

p.write_text(s, encoding='utf-8')
print('MCP250_RUNNER_MATERIALIZATION_PASS')
