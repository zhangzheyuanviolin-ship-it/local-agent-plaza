#!/usr/bin/env bash
set -euxo pipefail

BASE_ORIG='.github/scripts/run_mcp247_emergency_build.sh'
V3_ORIG='.github/scripts/run_mcp247_emergency_build_v3.sh'
BASE_248="$RUNNER_TEMP/run_mcp248_base.sh"
V3_248="$RUNNER_TEMP/run_mcp248_v3.sh"

python3 - "$BASE_ORIG" "$BASE_248" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1]).read_text()

anchor = 'python3 .github/scripts/patch_mcp247_qwen35_verified_2b_32k.py\n'
insert = anchor + r'''export MCP248_FREEZE_JSON="$RUNNER_TEMP/mcp248_model_freeze.json"
python3 -m py_compile .github/scripts/freeze_mcp248_models.py
python3 .github/scripts/freeze_mcp248_models.py
test -s "$MCP248_FREEZE_JSON"
python3 - <<'PYFREEZE'
import json, os
p=os.environ['MCP248_FREEZE_JSON']
d=json.load(open(p))
assert d['schema']=='local-agent-plaza.mcp248-model-freeze.v1'
assert len(d['models'])==8
assert len({(m['repo'],m['file'],m['revision']) for m in d['models']})==8
keys={m['key'] for m in d['models']}
assert keys=={'ministral3b','phi4mini','llama32_3b','falcon_h1_3b','jan_nano','fastcontext4b','laguna_xs2','gemma4_26b_a4b'}
print('MCP248_EXACT_EIGHT_MODEL_FREEZE_PASS')
PYFREEZE
'''
assert src.count(anchor) == 1, src.count(anchor)
src = src.replace(anchor, insert, 1)

audit_anchor = "    assert int(cfg['maxTokens'])==4096\n"
audit_insert = audit_anchor + r'''    freeze=json.load(open(os.environ['MCP248_FREEZE_JSON']))
    assert len(freeze['models'])==8
    for spec in freeze['models']:
        hits=[x for x in d['models'] if x.get('modelId')==spec['repo'] and x.get('modelFile')==spec['file'] and x.get('commitHash')==spec['revision']]
        assert len(hits)==1,(spec['key'],len(hits))
        x=hits[0]
        assert int(x['sizeInBytes'])==int(spec['size']),(spec['key'],x['sizeInBytes'],spec['size'])
    g=[x for x in d['models'] if x.get('name')=='Gemma-4-26B-A4B-it Box web artifact experimental']
    assert len(g)==1
    assert g[0]['defaultConfig']['accelerators']=='gpu'
    assert int(g[0]['defaultConfig']['maxContextLength'])==32000
'''
assert src.count(audit_anchor) == 1, src.count(audit_anchor)
src = src.replace(audit_anchor, audit_insert, 1)

repls = {
    "# 7. Package identity and version code prove it can cover-update MCP246 (code 346).": "# 7. Package identity and version code prove it can cover-update MCP247 (code 347).",
    "versionCode='347'": "versionCode='348'",
    "versionName='1.0.14-mcp.247'": "versionName='1.0.14-mcp.248'",
    "--title 'Local Agent Plaza MCP247 emergency media runtime restore'": "--title 'Local Agent Plaza MCP248 eight-model pool + Gemma 4 26B experiment'",
    "--notes 'Layered emergency restore over MCP246. Preserves stable Agent/search/tool/diagnostic behavior; restores the MCP237/Box 0.4.9 device-proven LiteRT native set; excludes MCP242 custom runtime and MCP246 Qwen Engine reset; registers the independently verified Qwen3.5-2B Q8 32768 bundle on CPU.'": "--notes 'MCP248 is a narrow model-pool expansion over the fully verified MCP247 product baseline. It preserves MCP247 Agent/search/tool/diagnostic behavior and the MCP237/Box 0.4.9 device-proven media native set, keeps the verified Qwen3.5-2B Q8 32768 CPU path, and adds exactly eight frozen LiteRT-LM model entries including the Box v3.3.2-proven Gemma 4 26B-A4B web artifact.'",
    "'schema':'local-agent-plaza.mcp247-emergency-media-runtime-restore.v2'": "'schema':'local-agent-plaza.mcp248-model-pool.v1'",
    "'upgrade_target':'MCP246 versionCode 346'": "'upgrade_target':'MCP247 versionCode 347'",
    '"$RUNNER_TEMP/mcp247_result.json"': '"$RUNNER_TEMP/mcp248_result.json"',
    'docs/mcp247_emergency_media_restore_result.json': 'docs/mcp248_model_pool_result.json',
    "'Update MCP247 emergency restore acceptance'": "'Update MCP248 model-pool acceptance'",
    'echo MCP247_FULLY_VERIFIED_PASS': 'echo MCP248_FULLY_VERIFIED_PASS',
}
for old,new in repls.items():
    c=src.count(old)
    assert c >= 1, (old,c)
    src=src.replace(old,new)

manifest_anchor = " 'media_native':{'libLiteRt.so':'da27c0d6e59460248b1032610e924bc0f518a1229d2e3b081e0a229be51ab1c8',\n"
manifest_insert = " 'model_pool':json.load(open(os.environ['MCP248_FREEZE_JSON']))['models'],\n" + manifest_anchor
assert src.count(manifest_anchor)==1, src.count(manifest_anchor)
src=src.replace(manifest_anchor,manifest_insert,1)

src=src.replace('mcp247_run.json','mcp248_run.json')
src=src.replace('local-agent-plaza.mcp247-emergency-run.v2','local-agent-plaza.mcp248-model-pool-run.v1')
src=src.replace('docs/mcp247_emergency_run.json','docs/mcp248_run.json')
src=src.replace("'Update MCP247 emergency run marker'","'Update MCP248 run marker'")

Path(sys.argv[2]).write_text(src)
PY

python3 - "$V3_ORIG" "$V3_248" "$BASE_248" <<'PY'
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

chmod +x "$BASE_248" "$V3_248"
bash -n "$BASE_248"
bash -n "$V3_248"
grep -F 'MCP248_EXACT_EIGHT_MODEL_FREEZE_PASS' "$BASE_248"
grep -F "versionCode='348'" "$BASE_248"
grep -F "versionName='1.0.14-mcp.248'" "$BASE_248"
grep -F 'docs/mcp248_model_pool_result.json' "$BASE_248"
exec bash "$V3_248"
