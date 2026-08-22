#!/usr/bin/env bash
set -euxo pipefail

BASE_ORIG='.github/scripts/run_mcp247_emergency_build.sh'
V3_ORIG='.github/scripts/run_mcp247_emergency_build_v3.sh'
BASE_249="$RUNNER_TEMP/run_mcp249_base.sh"
V3_249="$RUNNER_TEMP/run_mcp249_v3.sh"

python3 - "$BASE_ORIG" "$BASE_249" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()

# Start from the fully verified MCP247 product line, restore the exact MCP248 eight-model pool, then
# apply only the MCP249 exact-model COMPAT adaptation.
anchor = 'python3 .github/scripts/patch_mcp247_qwen35_verified_2b_32k.py\n'
insert = anchor + r'''export MCP248_FREEZE_JSON="$RUNNER_TEMP/mcp249_model_freeze.json"
python3 -m py_compile .github/scripts/freeze_mcp248_models.py
python3 .github/scripts/freeze_mcp248_models.py
test -s "$MCP248_FREEZE_JSON"
python3 - <<'PYFREEZE'
import json, os
p=os.environ['MCP248_FREEZE_JSON']
d=json.load(open(p))
assert d['schema']=='local-agent-plaza.mcp248-model-freeze.v1'
assert len(d['models'])==8
expected={
 'ministral3b':('e69d446849a7723eb6eceac970deca94be97dc0c',2340982768),
 'phi4mini':('8cd368be75fdb94d5a6f6f5b40f1ab22a6c2543e',3910090752),
 'llama32_3b':('c1ddfa1879bb812752db254e3f2e6eb65fe38b6a',2210301872),
 'falcon_h1_3b':('d64b51d448c3313468ba0dbad463d0c12f01f47c',3385302368),
 'jan_nano':('492a1af5794a6d1ea573cd360655ba935b4f3b8f',2474357680),
 'fastcontext4b':('2eebcbbacbb644bd656137a243e0248e465a6e80',2662888368),
 'laguna_xs2':('db2a76388c4f8105790334cb4ff2a81ea7a8b15c',3037564832),
 'gemma4_26b_a4b':('755026618afd72ebb6d970f784d42effa67398bc',15786524672),
}
by={m['key']:m for m in d['models']}
assert set(by)==set(expected),(set(by),set(expected))
for key,(rev,size) in expected.items():
    m=by[key]
    assert m['revision']==rev,(key,m['revision'],rev)
    assert int(m['size'])==size,(key,m['size'],size)
assert by['ministral3b']['file']=='Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm'
assert by['phi4mini']['file']=='Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm'
print('MCP249_EXACT_MCP248_MODEL_POOL_IDENTITY_PASS')
PYFREEZE
python3 -m py_compile .github/scripts/patch_mcp249_ministral_phi_compat.py
python3 .github/scripts/patch_mcp249_ministral_phi_compat.py
'''
assert src.count(anchor) == 1, src.count(anchor)
src = src.replace(anchor, insert, 1)

# MCP249 adds exactly two new Kotlin source paths beyond MCP247's existing five-file CI workspace
# delta. LlmChatModelHelper was already in the MCP247 expected set.
expected_old = r'''expected=(
  'Android/src/app/src/main/assets/model_allowlists/1_0_14.json'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt'
  'model_allowlists/1_0_14.json'
)
'''
expected_new = r'''expected=(
  'Android/src/app/src/main/assets/model_allowlists/1_0_14.json'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTooling.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt'
  'Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt'
  'model_allowlists/1_0_14.json'
)
'''
assert src.count(expected_old)==1, src.count(expected_old)
src=src.replace(expected_old,expected_new,1)

# Independent APK allowlist audit: exact same eight model identities as MCP248, plus unchanged 4096
# artifacts for the two MCP249 targets.
audit_anchor = "    assert int(cfg['maxTokens'])==4096\n"
audit_insert = audit_anchor + r'''    freeze=json.load(open(os.environ['MCP248_FREEZE_JSON']))
    assert len(freeze['models'])==8
    for spec in freeze['models']:
        hits=[x for x in d['models'] if x.get('modelId')==spec['repo'] and x.get('modelFile')==spec['file'] and x.get('commitHash')==spec['revision']]
        assert len(hits)==1,(spec['key'],len(hits))
        x=hits[0]
        assert int(x['sizeInBytes'])==int(spec['size']),(spec['key'],x['sizeInBytes'],spec['size'])
    targets={
      'Ministral-3-3B-Instruct-2512 LiteRT':'Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm',
      'Phi-4-mini-instruct Q8 4096 LiteRT':'Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm',
    }
    for name,file in targets.items():
        hits=[x for x in d['models'] if x.get('name')==name]
        assert len(hits)==1,(name,len(hits))
        assert hits[0]['modelFile']==file
        assert int(hits[0]['defaultConfig']['maxContextLength'])==4096
    g=[x for x in d['models'] if x.get('name')=='Gemma-4-26B-A4B-it Box web artifact experimental']
    assert len(g)==1
    assert g[0]['defaultConfig']['accelerators']=='gpu'
    assert int(g[0]['defaultConfig']['maxContextLength'])==32000
'''
assert src.count(audit_anchor) == 1, src.count(audit_anchor)
src = src.replace(audit_anchor, audit_insert, 1)

# DEX must contain the actual runtime protocol markers used by the exact-model branches. Keep the
# MCP247 first-line marker intact so the v3 audit correction can still remove the source-only Qwen
# comment from the DEX requirements.
dex_old = "b'Bonsai', b'FLUX', b'Z-Image', b'SEARCH_REQUIRED=true'):\n"
dex_new = "b'Bonsai', b'FLUX', b'Z-Image', b'SEARCH_REQUIRED=true',\n                   b'MCP249_MINISTRAL_COMPAT_V1', b'MCP249_PHI4MINI_COMPAT_V1'):\n"
assert src.count(dex_old)==1, src.count(dex_old)
src=src.replace(dex_old,dex_new,1)

repls = {
    "# 7. Package identity and version code prove it can cover-update MCP246 (code 346).": "# 7. Package identity and version code prove it can cover-update MCP248 (code 348).",
    "versionCode='347'": "versionCode='349'",
    "versionName='1.0.14-mcp.247'": "versionName='1.0.14-mcp.249'",
    "--title 'Local Agent Plaza MCP247 emergency media runtime restore'": "--title 'Local Agent Plaza MCP249 targeted Ministral and Phi Agent compatibility'",
    "--notes 'Layered emergency restore over MCP246. Preserves stable Agent/search/tool/diagnostic behavior; restores the MCP237/Box 0.4.9 device-proven LiteRT native set; excludes MCP242 custom runtime and MCP246 Qwen Engine reset; registers the independently verified Qwen3.5-2B Q8 32768 bundle on CPU.'": "--notes 'MCP249 preserves the fully verified MCP247 media/Agent/Qwen product baseline and the exact MCP248 eight-model pool. It changes only COMPAT behavior for the existing 4096-KV Ministral-3-3B-Instruct-2512 and Phi-4-mini-instruct artifacts: bounded larger post-tool history/result budgets, exact-family finalization rules, and low-variance COMPAT sampling. All other model protocol paths remain on the MCP248 behavior.'",
    "'schema':'local-agent-plaza.mcp247-emergency-media-runtime-restore.v2'": "'schema':'local-agent-plaza.mcp249-agent-family-compat.v1'",
    "'upgrade_target':'MCP246 versionCode 346'": "'upgrade_target':'MCP248 versionCode 348'",
    '"$RUNNER_TEMP/mcp247_result.json"': '"$RUNNER_TEMP/mcp249_result.json"',
    'docs/mcp247_emergency_media_restore_result.json': 'docs/mcp249_agent_family_compat_result.json',
    "'Update MCP247 emergency restore acceptance'": "'Update MCP249 Agent family compatibility acceptance'",
    'echo MCP247_FULLY_VERIFIED_PASS': 'echo MCP249_FULLY_VERIFIED_PASS',
}
for old,new in repls.items():
    c=src.count(old)
    assert c >= 1, (old,c)
    src=src.replace(old,new)

manifest_anchor = " 'media_native':{'libLiteRt.so':'da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8',\n"
manifest_insert = (
    " 'model_pool':json.load(open(os.environ['MCP248_FREEZE_JSON']))['models'],\n"
    " 'agent_family_compat':{'ministral3b':True,'phi4mini':True,'artifact_context_unchanged':4096,'tool_result_budget_chars':[2200,2400],'history_budget_chars':[2200,2400],'generic_model_protocol_path_unchanged':True,'reconversion':False},\n"
    + manifest_anchor
)
assert src.count(manifest_anchor)==1, src.count(manifest_anchor)
src=src.replace(manifest_anchor,manifest_insert,1)

src=src.replace('mcp247_run.json','mcp249_run.json')
src=src.replace('local-agent-plaza.mcp247-emergency-run.v2','local-agent-plaza.mcp249-agent-family-compat-run.v1')
src=src.replace('docs/mcp247_emergency_run.json','docs/mcp249_run.json')
src=src.replace("'Update MCP247 emergency run marker'","'Update MCP249 run marker'")

Path(sys.argv[2]).write_text(src)
PY

python3 - "$V3_ORIG" "$V3_249" "$BASE_249" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
base=sys.argv[3]
old="BASE_ORIG='.github/scripts/run_mcp247_emergency_build.sh'"
new=f"BASE_ORIG='{base}'"
assert src.count(old)==1, src.count(old)
src=src.replace(old,new,1)
Path(sys.argv[2]).write_text(src)
PY

chmod +x "$BASE_249" "$V3_249"
bash -n "$BASE_249"
bash -n "$V3_249"
grep -F 'MCP249_EXACT_MCP248_MODEL_POOL_IDENTITY_PASS' "$BASE_249"
grep -F 'patch_mcp249_ministral_phi_compat.py' "$BASE_249"
grep -F "versionCode='349'" "$BASE_249"
grep -F "versionName='1.0.14-mcp.249'" "$BASE_249"
grep -F 'docs/mcp249_agent_family_compat_result.json' "$BASE_249"
grep -F 'MCP249_MINISTRAL_COMPAT_V1' "$BASE_249"
grep -F 'MCP249_PHI4MINI_COMPAT_V1' "$BASE_249"
exec bash "$V3_249"
