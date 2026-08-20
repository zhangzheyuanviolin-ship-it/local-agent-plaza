#!/usr/bin/env bash
set -euo pipefail

CONFIG_PATH="model-conversion/qwen35/configs/2b-q8-32768.json"
MODEL_FILE="Qwen3.5-2B-LiteRT-LM-Q8-32768.litertlm"
HF_MODEL_ID="Qwen/Qwen3.5-2B"
HF_REVISION="15852e8c16360a2fea060d615a32b45270f8a8fc"
LITERT_TORCH_SHA="b66af07f1dcb9af2a990612bf6c9c0f654be3598"
LITERT_LM_SHA="2117fc4314670e00047bc8469783f02a68c33f0c"
RELEASE_TAG="qwen35-2b-q8-32768-mcp238lineage-v3"
STATUS="$RUNNER_TEMP/qwen35_2b_32k_status.json"
OUT="$RUNNER_TEMP/qwen35-output"
SOURCE="$RUNNER_TEMP/qwen35-2b-source"
UNPACK="$RUNNER_TEMP/qwen35-unpack"
export CONFIG_PATH MODEL_FILE HF_MODEL_ID HF_REVISION LITERT_TORCH_SHA LITERT_LM_SHA RELEASE_TAG STATUS OUT SOURCE UNPACK

stage="start"
write_status() {
  local result="$1"
  local message="${2:-}"
  RESULT="$result" STAGE="$stage" MESSAGE="$message" python - <<'PY'
import datetime,json,os
p=os.environ['STATUS']
old={}
try: old=json.load(open(p))
except Exception: pass
old.update({
  'schema':'qwen35-2b-q8-32768-ci-status.v1',
  'status':os.environ['RESULT'],
  'stage':os.environ['STAGE'],
  'message':os.environ.get('MESSAGE',''),
  'repository_commit':os.environ.get('GITHUB_SHA',''),
  'workflow_run_id':os.environ.get('GITHUB_RUN_ID',''),
  'workflow_run_url':f"https://github.com/{os.environ.get('GITHUB_REPOSITORY','')}/actions/runs/{os.environ.get('GITHUB_RUN_ID','')}",
  'updated_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
})
open(p,'w').write(json.dumps(old,ensure_ascii=False,indent=2)+'\n')
PY
}
trap 'rc=$?; if [ "$rc" -ne 0 ]; then write_status FAILED "exit_code=$rc"; fi' EXIT
write_status RUNNING

stage="config_gate"
python - <<'PY'
import json,os
c=json.load(open(os.environ['CONFIG_PATH']))
assert c['source_model_id']==os.environ['HF_MODEL_ID']
assert c['source_revision']==os.environ['HF_REVISION']
assert c['quantization_recipe']=='dynamic_wi8_afp32'
assert c['cache_length']==32768
assert c['prefill_lengths']==[128]
assert c['externalize_embedder'] is True
assert c['single_token_embedder'] is True
assert c['use_jinja_template'] is True
assert c['output_filename']==os.environ['MODEL_FILE']
assert c['target_app_litert_lm']=='0.15.0'
PY
write_status RUNNING

stage="runner_resources"
sudo rm -rf /usr/share/dotnet /opt/ghc /usr/local/.ghcup /opt/hostedtoolcache/CodeQL || true
docker system prune -af || true
sudo apt-get clean || true
SWAP=/mnt/qwen35-32k-v3.swap
sudo rm -f "$SWAP" || true
sudo fallocate -l 32G "$SWAP" || sudo dd if=/dev/zero of="$SWAP" bs=1M count=32768 status=progress
sudo chmod 600 "$SWAP"
sudo mkswap "$SWAP"
sudo swapon "$SWAP"
sudo sysctl -w vm.swappiness=80 || true
free -h
df -h
swapon --show
write_status RUNNING

stage="pin_external_sources"
LT="$RUNNER_TEMP/litert-torch"
LM="$RUNNER_TEMP/LiteRT-LM"
rm -rf "$LT" "$LM"
git clone https://github.com/google-ai-edge/litert-torch.git "$LT"
git -C "$LT" checkout --detach "$LITERT_TORCH_SHA"
test "$(git -C "$LT" rev-parse HEAD)" = "$LITERT_TORCH_SHA"
git clone https://github.com/google-ai-edge/LiteRT-LM.git "$LM"
git -C "$LM" checkout --detach "$LITERT_LM_SHA"
test "$(git -C "$LM" rev-parse HEAD)" = "$LITERT_LM_SHA"
grep -Fq 'TYPE_LINEAR_ATTENTION = 5' "$LM/runtime/proto/executor_metadata.proto"
grep -Fq 'case proto::StateBuffer::TYPE_LINEAR_ATTENTION:' "$LM/runtime/executor/litert/state.cc"
export LT
python - <<'PY'
from pathlib import Path
import os
p=Path(os.environ['LT'])/'litert_torch/generative/export_hf/model_ext/metadata_builder.py'
s=p.read_text(encoding='utf-8')
needle="""            executor_metadata_pb2.StateBuffer.TYPE_LINEAR_ATTENTION,\n        )"""
repl="""            executor_metadata_pb2.StateBuffer.TYPE_LINEAR_ATTENTION,\n            sequence_axis=0,\n        )"""
count=s.count(needle)
if count==3:
    s=s.replace(needle,repl)
    p.write_text(s,encoding='utf-8')
elif count==0 and s.count('sequence_axis=0')>=3:
    pass
else:
    raise SystemExit(f'expected exactly 3 recurrent metadata patch sites, raw={count}, patched={s.count("sequence_axis=0")}')
s=p.read_text(encoding='utf-8')
assert s.count('sequence_axis=0')>=3
print('MCP238_SEQUENCE_AXIS_PATCH_PASS')
PY
sha256sum "$LT/litert_torch/generative/export_hf/model_ext/metadata_builder.py" > "$RUNNER_TEMP/patched_metadata_builder.sha256"
echo "PYTHONPATH=$LT" >> "$GITHUB_ENV"
export PYTHONPATH="$LT${PYTHONPATH:+:$PYTHONPATH}"
write_status RUNNING

stage="android_015_gate"
POM="$RUNNER_TEMP/litertlm-android-0.15.0.pom"
curl --retry 5 --retry-all-errors -fsSL 'https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.15.0/litertlm-android-0.15.0.pom' -o "$POM"
grep -Fq '<version>0.15.0</version>' "$POM"
sha256sum "$POM" > "$RUNNER_TEMP/android_0_15_pom.sha256"
write_status RUNNING

stage="dependencies"
python -m pip install --upgrade pip setuptools wheel
python -m pip install --upgrade 'torch==2.12.0+cpu' --extra-index-url https://download.pytorch.org/whl/cpu
python -m pip install --upgrade absl-py numpy scipy safetensors multipledispatch transformers kagglehub tabulate 'ai-edge-litert-nightly[model-utils]' ai-edge-quantizer-nightly 'litert-converter>=0.0.0.dev0' 'jax[cpu]' jaxtyping fire sentencepiece rich pillow psutil huggingface_hub
python -m pip install --no-deps --upgrade 'torchao>=0.17.0'
python -m pip install --upgrade 'litert-lm==0.15.0' ai-edge-litert
python - <<'PY'
import importlib.metadata as md
import litert_torch,litert_lm,litert_lm_builder
for n in ['torch','torchao','transformers','litert-lm','litert-lm-api','litert-lm-builder','ai-edge-litert','huggingface-hub']:
    try: print(n,md.version(n))
    except Exception: print(n,'unknown')
print('litert_torch',litert_torch.__file__)
print('litert_lm',litert_lm.__file__)
print('litert_lm_builder',litert_lm_builder.__file__)
PY
python -m pip freeze > "$RUNNER_TEMP/pip-freeze.txt"
write_status RUNNING

stage="official_source"
rm -rf "$SOURCE"
python - <<'PY'
from huggingface_hub import snapshot_download
import os
p=snapshot_download(repo_id=os.environ['HF_MODEL_ID'],revision=os.environ['HF_REVISION'],local_dir=os.environ['SOURCE'])
print('HF_SNAPSHOT='+p)
PY
python - <<'PY'
from transformers import AutoTokenizer,AutoConfig
from pathlib import Path
import hashlib,json,os
src=Path(os.environ['SOURCE'])
cfg=AutoConfig.from_pretrained(src)
assert getattr(cfg,'model_type','')=='qwen3_5'
t=AutoTokenizer.from_pretrained(src).chat_template
assert isinstance(t,str) and len(t)>1000
required=['<tools>','<tool_call>','<function=','<parameter=','<tool_response>','message.tool_calls','enable_thinking']
missing=[x for x in required if x not in t]
if missing: raise SystemExit(f'official template markers missing {missing}')
(Path(os.environ['RUNNER_TEMP'])/'source_chat_template.jinja').write_text(t,encoding='utf-8')
sha=hashlib.sha256(t.encode()).hexdigest()
(Path(os.environ['RUNNER_TEMP'])/'source_chat_template.sha256').write_text(sha+'\n')
print('SOURCE_TEMPLATE_SHA256='+sha)
c=json.load(open(os.environ['CONFIG_PATH']))
c['model_id']=str(src)
(Path(os.environ['RUNNER_TEMP'])/'runtime_config.json').write_text(json.dumps(c,ensure_ascii=False,indent=2)+'\n')
PY
write_status RUNNING

stage="conversion"
rm -rf "$OUT"
mkdir -p "$OUT"
set +e
/usr/bin/time -v python model-conversion/qwen35/convert_qwen35.py --config "$RUNNER_TEMP/runtime_config.json" --output-dir "$OUT" --lightweight true 2>&1 | tee "$RUNNER_TEMP/conversion-lightweight.log"
rc=${PIPESTATUS[0]}
set -e
if [ "$rc" -ne 0 ]; then
  echo "Lightweight conversion failed rc=$rc; using historical standard fallback."
  rm -rf "$OUT"
  mkdir -p "$OUT"
  /usr/bin/time -v python model-conversion/qwen35/convert_qwen35.py --config "$RUNNER_TEMP/runtime_config.json" --output-dir "$OUT" --lightweight false 2>&1 | tee "$RUNNER_TEMP/conversion-standard.log"
fi
BUNDLE="$OUT/$MODEL_FILE"
export BUNDLE
test -f "$BUNDLE"
test "$(head -c 8 "$BUNDLE")" = LITERTLM
stat -c '%s %n' "$BUNDLE"
sha256sum "$BUNDLE"
write_status RUNNING

stage="cpu_runtime_validation"
/usr/bin/time -v python model-conversion/qwen35/verify_qwen35.py --config "$RUNNER_TEMP/runtime_config.json" --bundle "$BUNDLE" --manifest "$OUT/conversion_manifest.json" 2>&1 | tee "$RUNNER_TEMP/runtime-validation.log"
python - <<'PY'
import json,os
m=json.load(open(os.environ['OUT']+'/conversion_manifest.json'))
v=m['verification']
assert v['status']=='PASS'
assert v['hybrid_executor_state']['status']=='PASS'
assert v['hybrid_executor_state']['linear_recurrent_state_buffer_count']==36
assert v['hybrid_executor_state']['full_attention_kv_state_buffer_count']==12
assert v['hybrid_executor_state']['sequence_axis_zero_all_linear_states'] is True
assert v['hybrid_executor_state']['full_attention_maximum_sequence_length']==32768
assert v['runtime_smoke']['max_num_tokens']==32768
assert v['runtime_smoke']['output_chars']>=1
print('OFFICIAL_CPU_RUNTIME_AND_EXECUTOR_METADATA_PASS')
PY
write_status RUNNING

stage="unpack_template_signature_validation"
rm -rf "$UNPACK"
litert-lm unpack "$BUNDLE" --output-dir "$UNPACK" --allow-overwrite
python - <<'PY'
from pathlib import Path
import ast,hashlib,os,re
root=Path(os.environ['UNPACK'])
pb=root/'LlmMetadataProto.pbtext'
if not pb.exists(): raise SystemExit('LlmMetadataProto.pbtext missing')
text=pb.read_text(encoding='utf-8')
m=re.search(r'jinja_prompt_template:\s*"((?:\\.|[^"\\])*)"',text)
if not m: raise SystemExit('packed Jinja missing')
packed=ast.literal_eval('"'+m.group(1)+'"')
source=(Path(os.environ['RUNNER_TEMP'])/'source_chat_template.jinja').read_text(encoding='utf-8')
if packed!=source: raise SystemExit('packed chat template differs from exact official source template')
sha=hashlib.sha256(packed.encode()).hexdigest()
expected=(Path(os.environ['RUNNER_TEMP'])/'source_chat_template.sha256').read_text().strip()
if sha!=expected: raise SystemExit(f'template SHA mismatch {sha} != {expected}')
print('OFFICIAL_TEMPLATE_ROUNDTRIP_PASS '+sha)
PY
python - <<'PY'
from pathlib import Path
import json,os
from ai_edge_litert.interpreter import Interpreter
root=Path(os.environ['UNPACK'])
files=sorted(root.rglob('*.tflite'))
if not files: raise SystemExit('no unpacked TFLite model found')
keys=[]; hits=[]; reports=[]
for f in files:
    it=Interpreter(model_path=str(f))
    sig=it.get_signature_list() or {}
    keys.extend(map(str,sig))
    reports.append({'file':f.name,'signatures':sig})
    for d in it.get_tensor_details():
        n=str(d.get('name',''))
        sh=[int(x) for x in d.get('shape',[])]
        if 32768 in sh and any(k in n.lower() for k in ['cache','mask']):
            hits.append({'file':f.name,'name':n,'shape':sh})
if not any('prefill_128' in k.lower() for k in keys): raise SystemExit(f'prefill_128 missing {keys}')
if not any('decode' in k.lower() for k in keys): raise SystemExit(f'decode missing {keys}')
if not hits: raise SystemExit('actual 32768 cache/mask tensor not found')
report={'status':'PASS','signature_keys':keys,'tflite_files':reports,'cache_or_mask_tensors_with_32768':hits[:100]}
(Path(os.environ['RUNNER_TEMP'])/'signature_32k_report.json').write_text(json.dumps(report,indent=2)+'\n')
print('TFLITE_SIGNATURE_AND_32768_CAPACITY_PASS')
PY
write_status RUNNING

stage="recurrent_metadata_and_manifest"
python - <<'PY'
from pathlib import Path
import hashlib,importlib.metadata as md,json,os

def sha256_file(p):
    h=hashlib.sha256()
    with open(p,'rb') as f:
        for b in iter(lambda:f.read(16*1024*1024),b''): h.update(b)
    return h.hexdigest()

b=Path(os.environ['BUNDLE'])
conv=json.load(open(os.environ['OUT']+'/conversion_manifest.json'))
sig=json.load(open(os.environ['RUNNER_TEMP']+'/signature_32k_report.json'))
state=conv['verification']['hybrid_executor_state']
if state['status']!='PASS': raise SystemExit('executor metadata verification did not pass')
if state['linear_recurrent_state_buffer_count']!=36: raise SystemExit('unexpected recurrent state count')
if state['full_attention_kv_state_buffer_count']!=12: raise SystemExit('unexpected full-attention KV state count')
if not state['sequence_axis_zero_all_linear_states']: raise SystemExit('linear state sequence axis gate failed')
if state['full_attention_maximum_sequence_length']!=32768: raise SystemExit('executor metadata does not prove 32768 cache')
vers={}
for n in ['torch','torchao','transformers','litert-lm','litert-lm-api','litert-lm-builder','ai-edge-litert','huggingface-hub']:
    try: vers[n]=md.version(n)
    except Exception: vers[n]='unknown'
res={
  'schema':'local-agent-plaza.qwen35-2b-q8-32768.mcp238-lineage.v3',
  'repository_commit':os.environ.get('GITHUB_SHA',''),
  'workflow_run_id':os.environ.get('GITHUB_RUN_ID',''),
  'source_model':os.environ['HF_MODEL_ID'],
  'source_revision':os.environ['HF_REVISION'],
  'litert_torch_revision':os.environ['LITERT_TORCH_SHA'],
  'litert_lm_source_revision':os.environ['LITERT_LM_SHA'],
  'target_android_litert_lm':'0.15.0',
  'quantization_recipe':'dynamic_wi8_afp32',
  'prefill_lengths':[128],
  'cache_length':32768,
  'model_file':os.environ['MODEL_FILE'],
  'full_size':b.stat().st_size,
  'full_sha256':sha256_file(b),
  'official_chat_template_sha256':(Path(os.environ['RUNNER_TEMP'])/'source_chat_template.sha256').read_text().strip(),
  'official_chat_template_exact_roundtrip':True,
  'executor_metadata_validation':state,
  'linear_recurrent_state_buffer_count':state['linear_recurrent_state_buffer_count'],
  'sequence_axis_zero_all_linear_states':True,
  'runtime_smoke':conv['verification']['runtime_smoke'],
  'runtime_validation_status':conv['verification']['status'],
  'signature_validation':sig,
  'versions':vers,
  'release_tag':os.environ['RELEASE_TAG'],
}
(Path(os.environ['RUNNER_TEMP'])/'validation_manifest.json').write_text(json.dumps(res,ensure_ascii=False,indent=2)+'\n')
print('VALIDATION_MANIFEST_PASS')
PY
write_status RUNNING

stage="immutable_publish"
if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" >/dev/null 2>&1; then
  echo "immutable tag already exists: $RELEASE_TAG" >&2
  exit 1
fi
ASSETS="$RUNNER_TEMP/release-assets"
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
export ASSETS
split -b 480000000 -d -a 2 "$BUNDLE" "$ASSETS/$MODEL_FILE.part"
cp "$RUNNER_TEMP/validation_manifest.json" "$ASSETS/validation_manifest.json"
cp "$RUNNER_TEMP/signature_32k_report.json" "$ASSETS/signature_32k_report.json"
cp "$RUNNER_TEMP/source_chat_template.sha256" "$ASSETS/source_chat_template.sha256"
cp "$RUNNER_TEMP/patched_metadata_builder.sha256" "$ASSETS/patched_metadata_builder.sha256"
cp "$RUNNER_TEMP/android_0_15_pom.sha256" "$ASSETS/android_0_15_pom.sha256"
python - <<'PY'
from pathlib import Path
import hashlib,json,os

def sha(p):
    h=hashlib.sha256()
    with open(p,'rb') as f:
        for b in iter(lambda:f.read(16*1024*1024),b''): h.update(b)
    return h.hexdigest()

a=Path(os.environ['ASSETS']); b=Path(os.environ['BUNDLE'])
parts=[{'name':p.name,'size':p.stat().st_size,'sha256':sha(p)} for p in sorted(a.glob(os.environ['MODEL_FILE']+'.part*'))]
if not parts: raise SystemExit('multipart split produced no parts')
manifest={'schema':'immutable-multipart.v1','model_file':os.environ['MODEL_FILE'],'full_size':b.stat().st_size,'full_sha256':sha(b),'parts':parts}
(a/'multipart_manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(f"MULTIPART_MANIFEST_PASS parts={len(parts)}")
PY
gh release create "$RELEASE_TAG" "$ASSETS"/* --repo "$GITHUB_REPOSITORY" --title 'Qwen3.5 2B Q8 32K MCP238-lineage verified v3' --notes 'Official pinned Qwen3.5-2B source. MCP238-derived dynamic INT8/Q8 conversion. Actual 32768 cache. Exact official tool-aware template round-trip. Recurrent linear-attention state metadata and official LiteRT-LM 0.15 CPU Engine generation gates passed.' --prerelease
write_status RUNNING

stage="post_publish_reconstruction"
DOWN="$RUNNER_TEMP/post-publish"
rm -rf "$DOWN"
mkdir -p "$DOWN"
export DOWN
gh release download "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" --dir "$DOWN"
python - <<'PY'
from pathlib import Path
import hashlib,json,os

def sha(p):
    h=hashlib.sha256()
    with open(p,'rb') as f:
        for b in iter(lambda:f.read(16*1024*1024),b''): h.update(b)
    return h.hexdigest()

d=Path(os.environ['DOWN'])
m=json.loads((d/'multipart_manifest.json').read_text())
for x in m['parts']:
    p=d/x['name']
    if p.stat().st_size!=x['size'] or sha(p)!=x['sha256']:
        raise SystemExit(f"published part verification failed: {x['name']}")
reb=d/('reconstructed-'+m['model_file'])
with open(reb,'wb') as w:
    for x in m['parts']:
        with open(d/x['name'],'rb') as r:
            for b in iter(lambda:r.read(16*1024*1024),b''): w.write(b)
full=sha(reb)
if reb.stat().st_size!=m['full_size'] or full!=m['full_sha256']:
    raise SystemExit('published full-model reconstruction failed')
reb.unlink()
r={'schema':'post-publication-verification.v1','release_tag':os.environ['RELEASE_TAG'],'model_file':m['model_file'],'full_size':m['full_size'],'full_sha256':full,'part_count':len(m['parts']),'all_part_hashes_verified':True,'reconstruction_verified':True,'status':'PASS'}
(d/'post_publication_verification.json').write_text(json.dumps(r,indent=2)+'\n')
print('POST_PUBLICATION_RECONSTRUCTION_PASS')
PY
gh release upload "$RELEASE_TAG" "$DOWN/post_publication_verification.json" --repo "$GITHUB_REPOSITORY"
write_status RUNNING

stage="final_gate"
python - <<'PY'
import json,os
v=json.load(open(os.environ['RUNNER_TEMP']+'/validation_manifest.json'))
p=json.load(open(os.environ['DOWN']+'/post_publication_verification.json'))
assert v['cache_length']==32768
assert v['quantization_recipe']=='dynamic_wi8_afp32'
assert v['official_chat_template_exact_roundtrip']
assert v['sequence_axis_zero_all_linear_states']
assert v['executor_metadata_validation']['status']=='PASS'
assert v['executor_metadata_validation']['linear_recurrent_state_buffer_count']==36
assert v['executor_metadata_validation']['full_attention_kv_state_buffer_count']==12
assert v['executor_metadata_validation']['full_attention_maximum_sequence_length']==32768
assert v['runtime_validation_status']=='PASS'
assert v['runtime_smoke']['max_num_tokens']==32768
assert v['runtime_smoke']['output_chars']>=1
assert v['signature_validation']['status']=='PASS'
assert p['status']=='PASS' and p['reconstruction_verified'] and p['all_part_hashes_verified']
assert v['full_sha256']==p['full_sha256'] and v['full_size']==p['full_size']
status=json.load(open(os.environ['STATUS']))
status.update({
  'status':'PASS',
  'stage':'complete',
  'message':'QWEN35_2B_Q8_32K_FULLY_VERIFIED_PASS',
  'model_file':v['model_file'],
  'model_size':v['full_size'],
  'model_sha256':v['full_sha256'],
  'template_sha256':v['official_chat_template_sha256'],
  'source_revision':v['source_revision'],
  'litert_torch_revision':v['litert_torch_revision'],
  'litert_lm_source_revision':v['litert_lm_source_revision'],
  'release_tag':v['release_tag'],
  'executor_metadata_verification':'PASS',
  'cpu_runtime_verification':'PASS',
  'signature_32k_verification':'PASS',
  'post_publication_verification':'PASS',
})
open(os.environ['STATUS'],'w').write(json.dumps(status,ensure_ascii=False,indent=2)+'\n')
PY
trap - EXIT
echo QWEN35_2B_Q8_32K_FULLY_VERIFIED_PASS
