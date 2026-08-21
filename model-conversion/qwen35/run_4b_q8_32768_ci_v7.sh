#!/usr/bin/env bash
set -euo pipefail

# Qwen3.5-4B MCP238-lineage retry v7.
# v6 successfully completed the lightweight 4B text export and Q8 PTQ after
# releasing the heavyweight source/export graph, producing an 8.39 GiB
# quantized text TFLite. Its package step then failed because v6 had replaced
# source_model_artifacts.model with None, while the pinned LiteRT-LM packer still
# reads model.config and model.generation_config to build metadata. v7 preserves
# only that tiny metadata surface in a SimpleNamespace and releases all tensor
# weights/export modules before PTQ. Model bytes, source revision, 32K cache,
# prefill-128, quantization recipe, official Jinja template, and MCP238 recurrent
# metadata semantics remain unchanged.

SRC="model-conversion/qwen35/run_4b_q8_32768_ci_v6.sh"
TMP="$RUNNER_TEMP/run_4b_q8_32768_ci_v7.materialized.sh"

python - "$SRC" "$TMP" <<'PY'
from pathlib import Path
import sys

src = Path(sys.argv[1])
dst = Path(sys.argv[2])
s = src.read_text(encoding='utf-8')

# Give this run its own release/schema/diagnostic identity while retaining the
# already-proven v6 BF16 bridge, task order, swap envelope and cleanup logic.
s = s.replace('qwen35-4b-q8-32768-mcp238lineage-v6', 'qwen35-4b-q8-32768-mcp238lineage-v7')
s = s.replace('local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v6', 'local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v7')
s = s.replace('Qwen3.5 4B Q8 32K MCP238-lineage verified v6', 'Qwen3.5 4B Q8 32K MCP238-lineage verified v7')
s = s.replace('QWEN35_V6_', 'QWEN35_V7_')
s = s.replace('qwen35-v6-patches', 'qwen35-v7-patches')
s = s.replace('run_4b_q8_32768_ci_v6.generated.sh', 'run_4b_q8_32768_ci_v7.generated.sh')
s = s.replace('apply_v6_lifetime_patch.py', 'apply_v7_lifetime_patch.py')

# The v6 package failure was exact and late: package_model/build_llm_metadata
# dereferenced source_model_artifacts.model.config after PTQ. Keep only config +
# generation_config, both small metadata objects; release the real model and all
# export modules exactly where v6 proved PTQ can complete.
old_import = "from pathlib import Path\nimport os\n\nlt = Path(os.environ['LT'])"
new_import = "from pathlib import Path\nimport os\nimport types\n\nlt = Path(os.environ['LT'])"
if s.count(old_import) != 1:
    raise SystemExit(f'v7 import anchor mismatch count={s.count(old_import)}')
s = s.replace(old_import, new_import, 1)

old_release = "    source_model_artifacts.model = None\n    model = None\n"
new_release = """    _metadata_model = types.SimpleNamespace(\n        config=source_model_artifacts.model.config,\n        generation_config=getattr(source_model_artifacts.model, 'generation_config', None),\n    )\n    source_model_artifacts.model = _metadata_model\n    print('QWEN35_V7_METADATA_PROXY_READY config=true generation_config=' + str(_metadata_model.generation_config is not None).lower())\n    model = None\n"""
if s.count(old_release) != 1:
    raise SystemExit(f'v7 metadata-proxy anchor mismatch count={s.count(old_release)}')
s = s.replace(old_release, new_release, 1)

# Tighten the structural acceptance gate so the exact v6 regression cannot
# silently return.
needle = "assert ls.count('QWEN35_V7_PRE_QUANT_RELEASE begin') == 1\n"
insert = needle + "assert ls.count('QWEN35_V7_METADATA_PROXY_READY') == 1\nassert 'source_model_artifacts.model = None' not in ls\n"
if s.count(needle) != 1:
    raise SystemExit(f'v7 acceptance anchor mismatch count={s.count(needle)}')
s = s.replace(needle, insert, 1)

# Record the resource-lifetime correction in the final manifest.
old_manifest = "'pre_quant_source_release':True,'cleanup_unquantized_after_ptq':True"
new_manifest = "'pre_quant_source_release':True,'metadata_only_model_proxy_after_export':True,'cleanup_unquantized_after_ptq':True"
if s.count(old_manifest) != 1:
    raise SystemExit(f'v7 manifest anchor mismatch count={s.count(old_manifest)}')
s = s.replace(old_manifest, new_manifest, 1)

# Update comments/labels that are useful in logs without altering behavior.
s = s.replace('MCP238-lineage retry v6.', 'MCP238-lineage retry v7.')
s = s.replace('v6 fixes that lifetime overlap', 'v7 retains the proven lifetime fix')
s = s.replace('by v6.', 'by v7.')
s = s.replace('scheduled first by v6.', 'scheduled first by v7.')
s = s.replace('Qwen3.5 4B hosted-runner lifetime barrier.', 'Qwen3.5 4B hosted-runner lifetime barrier with metadata-only model proxy.')

dst.write_text(s, encoding='utf-8')
print('QWEN35_V7_MATERIALIZATION_PASS metadata_only_model_proxy=true')
PY

chmod +x "$TMP"
exec bash "$TMP"
