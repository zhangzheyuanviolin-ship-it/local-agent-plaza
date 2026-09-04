#!/usr/bin/env bash
set -euo pipefail

# Qwen3.5-4B MCP238-lineage retry v5.
# v4 proved 48 GiB auxiliary swap is sufficient for the hosted runner and
# reached MLIR constant creation. The remaining blocker was PyTorch refusing
# Tensor.numpy() on native BF16 constants. This wrapper preserves native BF16
# checkpoint loading and bridges BF16 constants to NumPy via ml_dtypes while
# retaining their exact BF16 bit pattern and MLIR bf16 element type.

BASE="model-conversion/qwen35/run_4b_q8_32768_ci.sh"
TMP="$RUNNER_TEMP/run_4b_q8_32768_ci_v5.generated.sh"
PATCH_DIR="$RUNNER_TEMP/qwen35-native-bf16-mlir-site"
mkdir -p "$PATCH_DIR"

cat > "$PATCH_DIR/sitecustomize.py" <<'PY'
import numpy as np
import torch
import transformers

# Keep the official checkpoint in its native BF16 form. Full float32 source
# loading exceeded the hosted runner's practical RAM+swap envelope.
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
        print("QWEN35_NATIVE_CHECKPOINT_DTYPE_V5_PATCH_ACTIVE requested=float32 effective=auto")
    return _orig(cls, *args, **kwargs)

_cls.from_pretrained = _native_dtype_from_pretrained

# LiteRT-Torch b66af07 currently calls Tensor.numpy() directly when converting
# inline constants. PyTorch rejects .numpy() for BF16. MLIR DenseElementsAttr
# accepts an explicit BF16 tensor type together with a buffer supplied by the
# ml_dtypes NumPy extension. Reinterpret the uint16 storage bits as
# ml_dtypes.bfloat16 so there is no numeric cast and no precision change.
from litert_torch.backend import inline_consts as _inline_consts

def _tensor_to_mlir_compatible_array_bf16(tensor):
    if hasattr(tensor, "detach"):
        t = tensor.contiguous().detach().cpu()
        if t.dtype == torch.bfloat16:
            import ml_dtypes
            raw = t.view(torch.uint16).numpy()
            arr = raw.view(ml_dtypes.bfloat16)
            if not getattr(_tensor_to_mlir_compatible_array_bf16, "_reported", False):
                print("QWEN35_BF16_MLIR_NUMPY_BRIDGE_PATCH_ACTIVE bit_preserving=true dtype=bfloat16")
                _tensor_to_mlir_compatible_array_bf16._reported = True
        else:
            arr = t.numpy()
    else:
        arr = np.array(tensor)
    return np.ascontiguousarray(arr)

_inline_consts._tensor_to_mlir_compatible_array = _tensor_to_mlir_compatible_array_bf16
PY

cp "$BASE" "$TMP"
sed -i 's/qwen35-4b-q8-32768-mcp238lineage-v1/qwen35-4b-q8-32768-mcp238lineage-v5/g' "$TMP"
sed -i 's/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v1/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v5/g' "$TMP"
# Retain the v4-proven runner resource envelope.
sed -i 's/fallocate -l 24G/fallocate -l 48G/g' "$TMP"
sed -i 's/bs=1M count=24576/bs=1M count=49152/g' "$TMP"
python - "$TMP" "$PATCH_DIR" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); patch=sys.argv[2]
s=p.read_text(encoding='utf-8')
needle='python model-conversion/qwen35/convert_qwen35.py'
repl=f'env PYTHONPATH="{patch}:$PYTHONPATH" python model-conversion/qwen35/convert_qwen35.py'
count=s.count(needle)
if count != 2:
    raise SystemExit(f'expected two converter invocations, found {count}')
s=s.replace(needle,repl)
s=s.replace("'quantization_recipe':'dynamic_wi8_afp32','prefill_lengths':[128]", "'quantization_recipe':'dynamic_wi8_afp32','source_checkpoint_load_dtype':'native_auto_bfloat16','bf16_mlir_numpy_bridge':'ml_dtypes_bit_preserving','auxiliary_swap_gib':48,'prefill_lengths':[128]")
s=s.replace("Qwen3.5 4B Q8 32K MCP238-lineage verified v1", "Qwen3.5 4B Q8 32K MCP238-lineage verified v5")
s=s.replace("Official pinned Qwen3.5-4B source.", "Official pinned Qwen3.5-4B source loaded in native BF16; BF16 constants are bridged to MLIR through ml_dtypes with bit-preserving storage reinterpretation; 48 GiB auxiliary swap is runner-only.")
p.write_text(s,encoding='utf-8')
PY
chmod +x "$TMP"

(
  while true; do
    echo "=== QWEN35_4B_V5_RESOURCE_MONITOR $(date -u +%FT%TZ) ==="
    free -h || true
    swapon --show || true
    df -h / /mnt || true
    ps -eo pid,ppid,rss,vsz,pcpu,pmem,comm --sort=-rss | head -n 12 || true
    sleep 60
  done
) &
MONITOR_PID=$!
trap 'kill "$MONITOR_PID" 2>/dev/null || true' EXIT

bash "$TMP"
rc=$?
kill "$MONITOR_PID" 2>/dev/null || true
trap - EXIT
exit "$rc"
