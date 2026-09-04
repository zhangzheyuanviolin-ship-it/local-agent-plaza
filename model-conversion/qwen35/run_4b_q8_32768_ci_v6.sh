#!/usr/bin/env bash
set -euo pipefail

# Qwen3.5-4B MCP238-lineage retry v6.
# v5 proved the native-BF16 -> MLIR bridge and completed export/MLIR/FlatBuffer,
# producing a 21,983,081,152-byte unquantized TFLite and calculating all
# 3153/3153 quantization parameters. It then exhausted ~50 GiB total swap while
# the quantizer transformations overlapped with the still-live source model and
# export modules. v6 fixes that lifetime overlap without changing model
# semantics: export the external embedder first, then text; after text TFLite is
# materialized, release source-model/export graph references before PTQ; remove
# superseded unquantized TFLite files only after successful quantization; retain
# a guarded 4 GiB emergency swap escape hatch only if swap is critically low
# and enough disk remains.

BASE="model-conversion/qwen35/run_4b_q8_32768_ci.sh"
TMP="$RUNNER_TEMP/run_4b_q8_32768_ci_v6.generated.sh"
PATCH_DIR="$RUNNER_TEMP/qwen35-v6-patches"
mkdir -p "$PATCH_DIR"

cat > "$PATCH_DIR/sitecustomize.py" <<'PY'
import numpy as np
import torch
import transformers

# Preserve official native BF16 checkpoint storage on the hosted runner.
_cls = transformers.AutoModelForCausalLM
_orig = _cls.from_pretrained.__func__

@classmethod
def _native_dtype_from_pretrained(cls, *args, **kwargs):
    changed = False
    if kwargs.get("torch_dtype", None) is torch.float32:
        kwargs["torch_dtype"] = "auto"
        changed = True
    if kwargs.get("dtype", None) is torch.float32:
        kwargs["dtype"] = "auto"
        changed = True
    if changed:
        print("QWEN35_NATIVE_CHECKPOINT_DTYPE_V6_PATCH_ACTIVE requested=float32 effective=auto")
    return _orig(cls, *args, **kwargs)

_cls.from_pretrained = _native_dtype_from_pretrained

# Bit-preserving BF16 bridge proven by v5 to pass the previous Create-MLIR
# failure and reach a complete serialized TFLite.
from litert_torch.backend import inline_consts as _inline_consts

def _tensor_to_mlir_compatible_array_bf16(tensor):
    if hasattr(tensor, "detach"):
        t = tensor.contiguous().detach().cpu()
        if t.dtype == torch.bfloat16:
            import ml_dtypes
            raw = t.view(torch.uint16).numpy()
            arr = raw.view(ml_dtypes.bfloat16)
            if not getattr(_tensor_to_mlir_compatible_array_bf16, "_reported", False):
                print("QWEN35_BF16_MLIR_NUMPY_BRIDGE_V6_ACTIVE bit_preserving=true dtype=bfloat16")
                _tensor_to_mlir_compatible_array_bf16._reported = True
        else:
            arr = t.numpy()
    else:
        arr = np.array(tensor)
    return np.ascontiguousarray(arr)

_inline_consts._tensor_to_mlir_compatible_array = _tensor_to_mlir_compatible_array_bf16
PY

cat > "$PATCH_DIR/apply_v6_lifetime_patch.py" <<'PY'
from pathlib import Path
import os

lt = Path(os.environ['LT'])
export_py = lt / 'litert_torch/generative/export_hf/export.py'
lib_py = lt / 'litert_torch/generative/export_hf/core/export_lib.py'

# 1) External embedder first. This makes it safe to dispose of the source model
# after the large text TFLite has been exported and before text PTQ begins.
s = export_py.read_text(encoding='utf-8')
old1 = """  else:\n    export_tasks.append(export_lib.export_text_prefill_decode_model)\n"""
new1 = """  else:\n    if export_config.externalize_embedder:\n      print('QWEN35_V6_TASK_ORDER external_embedder_before_text=true')\n      export_tasks.append(export_lib.export_embedder_model)\n    export_tasks.append(export_lib.export_text_prefill_decode_model)\n"""
old2 = """    if export_config.externalize_embedder:\n      export_tasks.append(export_lib.export_embedder_model)\n    if export_config.split_cache:\n"""
new2 = """    if export_config.split_cache:\n"""
if s.count(old1) != 1 or s.count(old2) != 1:
    raise SystemExit(f'v6 task-order patch anchors mismatch old1={s.count(old1)} old2={s.count(old2)}')
s = s.replace(old1, new1, 1).replace(old2, new2, 1)
export_py.write_text(s, encoding='utf-8')

# 2) Release all heavyweight Qwen3.5 source/export references after the text
# FlatBuffer is written, but only when the external embedder has already been
# exported and this exact text-only/no-split route is active.
s = lib_py.read_text(encoding='utf-8')
anchor = """  del lrt_model\n  del converter\n  gc.collect()\n\n  # Quantization\n"""
replacement = """  del lrt_model\n  del converter\n  gc.collect()\n\n  # Qwen3.5 4B hosted-runner lifetime barrier. The embedder task is deliberately\n  # scheduled first by v6. Once model.tflite exists, source weights and export\n  # modules are no longer required by Qwen3.5's remaining tasks (it has no\n  # additional exportables on this pinned LiteRT-Torch revision). Releasing\n  # them before PTQ prevents source-model + 22GB-TFLite quantizer overlap.\n  if (\n      source_model_artifacts.model_config.model_type in ('qwen3_5', 'qwen3_5_text')\n      and export_config.externalize_embedder\n      and not export_config.split_cache\n      and not export_config.export_vision_encoder\n  ):\n    if (\n        not exported_model_artifacts.embedder_model_path\n        or not os.path.exists(exported_model_artifacts.embedder_model_path)\n    ):\n      raise RuntimeError('QWEN35_V6_LIFETIME_GUARD: external embedder must exist before text PTQ')\n    print('QWEN35_V6_PRE_QUANT_RELEASE begin source_model=true export_modules=true')\n    source_model_artifacts.model = None\n    model = None\n    prefill_module = None\n    decode_module = None\n    sample_prefill_inputs = None\n    sample_decode_inputs = None\n    gc.collect()\n    try:\n      import ctypes\n      ctypes.CDLL('libc.so.6').malloc_trim(0)\n    except Exception as e:\n      print(f'QWEN35_V6_MALLOC_TRIM_WARNING {e}')\n    print('QWEN35_V6_PRE_QUANT_RELEASE done')\n\n  # Quantization\n"""
if s.count(anchor) != 1:
    raise SystemExit(f'v6 pre-quant lifetime anchor mismatch count={s.count(anchor)}')
s = s.replace(anchor, replacement, 1)

# 3) After a quantized model has been fully exported, delete only the superseded
# unquantized input TFLite. This is temporary-workdir cleanup and cannot mutate
# the returned quantized bytes. It recovers ~22GB for final LiteRT-LM packaging.
oldq = """  qt.quantize().export_model(quantized_model_path, overwrite=True)\n  return quantized_model_path\n"""
newq = """  qt.quantize().export_model(quantized_model_path, overwrite=True)\n  del qt\n  gc.collect()\n  if quantized_model_path != model_path and os.path.exists(model_path):\n    old_size = os.path.getsize(model_path)\n    os.remove(model_path)\n    print(f'QWEN35_V6_UNQUANTIZED_CLEANUP removed={model_path} bytes={old_size}')\n  try:\n    import ctypes\n    ctypes.CDLL('libc.so.6').malloc_trim(0)\n  except Exception:\n    pass\n  return quantized_model_path\n"""
if s.count(oldq) != 1:
    raise SystemExit(f'v6 quantized cleanup anchor mismatch count={s.count(oldq)}')
s = s.replace(oldq, newq, 1)
lib_py.write_text(s, encoding='utf-8')

# Structural acceptance gates: fail before expensive conversion if any of the
# intended lifetime changes are absent or duplicated.
es = export_py.read_text(encoding='utf-8')
ls = lib_py.read_text(encoding='utf-8')
assert es.count('QWEN35_V6_TASK_ORDER') == 1
assert es.find('export_lib.export_embedder_model') < es.find('export_lib.export_text_prefill_decode_model')
assert ls.count('QWEN35_V6_PRE_QUANT_RELEASE begin') == 1
assert ls.count('QWEN35_V6_UNQUANTIZED_CLEANUP') == 1
print('QWEN35_V6_LIFETIME_PATCH_PASS task_order=embedder_then_text pre_quant_source_release=true unquantized_cleanup=true')
PY

cp "$BASE" "$TMP"
sed -i 's/qwen35-4b-q8-32768-mcp238lineage-v1/qwen35-4b-q8-32768-mcp238lineage-v6/g' "$TMP"
sed -i 's/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v1/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v6/g' "$TMP"
sed -i 's/fallocate -l 24G/fallocate -l 48G/g' "$TMP"
sed -i 's/bs=1M count=24576/bs=1M count=49152/g' "$TMP"

python - "$TMP" "$PATCH_DIR" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); patch=sys.argv[2]
s=p.read_text(encoding='utf-8')
# Apply the pinned LiteRT source lifetime/task patch immediately after the
# historical three-site sequence-axis metadata patch has been verified.
anchor='sha256sum "$LT/litert_torch/generative/export_hf/model_ext/metadata_builder.py" > "$RUNNER_TEMP/patched_metadata_builder.sha256"'
insert=f'LT="$LT" python "{patch}/apply_v6_lifetime_patch.py"\n'+anchor
if s.count(anchor) != 1:
    raise SystemExit(f'expected one metadata sha anchor, found {s.count(anchor)}')
s=s.replace(anchor,insert,1)

needle='python model-conversion/qwen35/convert_qwen35.py'
repl=f'env PYTHONPATH="{patch}:$PYTHONPATH" python model-conversion/qwen35/convert_qwen35.py'
count=s.count(needle)
if count != 2:
    raise SystemExit(f'expected two converter invocations, found {count}')
s=s.replace(needle,repl)

s=s.replace("'quantization_recipe':'dynamic_wi8_afp32','prefill_lengths':[128]", "'quantization_recipe':'dynamic_wi8_afp32','source_checkpoint_load_dtype':'native_auto_bfloat16','bf16_mlir_numpy_bridge':'ml_dtypes_bit_preserving','external_embedder_before_text':True,'pre_quant_source_release':True,'cleanup_unquantized_after_ptq':True,'auxiliary_swap_gib':48,'emergency_swap_guard_gib':4,'prefill_lengths':[128]")
s=s.replace("Qwen3.5 4B Q8 32K MCP238-lineage verified v1", "Qwen3.5 4B Q8 32K MCP238-lineage verified v6")
s=s.replace("Official pinned Qwen3.5-4B source.", "Official pinned Qwen3.5-4B source loaded in native BF16; BF16->MLIR bridge is bit-preserving; external embedder is exported before text; source/export references are released before text PTQ; superseded unquantized TFLite is deleted only after successful PTQ; model semantics remain MCP238-lineage.")
p.write_text(s,encoding='utf-8')
PY
chmod +x "$TMP"

# Proactive route gate using the exact official config + pinned model extension.
# This prevents the lifetime optimization from running if this model ever gains
# additional/vision/split export requirements that would need source weights.
python - <<'PY'
import json
c=json.load(open('model-conversion/qwen35/configs/4b-q8-32768.json'))
assert c['source_model_id']=='Qwen/Qwen3.5-4B'
assert c['cache_length']==32768 and c['prefill_lengths']==[128]
assert c['quantization_recipe']=='dynamic_wi8_afp32'
assert c['externalize_embedder'] is True and c['single_token_embedder'] is True
assert c.get('split_cache', False) is False
assert c.get('export_vision_encoder', False) is False
print('QWEN35_V6_ROUTE_GATE_PASS text_only=true split_cache=false vision=false external_embedder=true cache=32768 prefill=128 quant=dynamic_wi8_afp32')
PY

# 10-second resource guard. The normal envelope remains 48 GiB auxiliary swap.
# A single 4 GiB emergency swap is added only under critical swap pressure and
# only if at least ~10 GiB filesystem headroom remains afterwards.
(
  EMERGENCY=/mnt/qwen35-4b-32k-emergency.swap
  while true; do
    echo "=== QWEN35_4B_V6_RESOURCE_MONITOR $(date -u +%FT%TZ) ==="
    free -h || true
    swapon --show || true
    df -h / /mnt || true
    ps -eo pid,ppid,rss,vsz,pcpu,pmem,comm --sort=-rss | head -n 12 || true
    if [ ! -e "$EMERGENCY" ]; then
      SWAP_FREE_MB=$(free -m | awk '/Swap:/ {print $4}')
      DISK_FREE_MB=$(df -Pm /mnt | awk 'NR==2 {print $4}')
      if [ "${SWAP_FREE_MB:-999999}" -lt 4096 ] && [ "${DISK_FREE_MB:-0}" -gt 14336 ]; then
        echo "QWEN35_V6_EMERGENCY_SWAP_TRIGGER swap_free_mb=$SWAP_FREE_MB disk_free_mb=$DISK_FREE_MB add_gib=4"
        sudo fallocate -l 4G "$EMERGENCY" || sudo dd if=/dev/zero of="$EMERGENCY" bs=1M count=4096 status=progress
        sudo chmod 600 "$EMERGENCY" && sudo mkswap "$EMERGENCY" && sudo swapon "$EMERGENCY"
      fi
    fi
    sleep 10
  done
) &
MONITOR_PID=$!
trap 'kill "$MONITOR_PID" 2>/dev/null || true' EXIT

bash "$TMP"
rc=$?
kill "$MONITOR_PID" 2>/dev/null || true
trap - EXIT
exit "$rc"
