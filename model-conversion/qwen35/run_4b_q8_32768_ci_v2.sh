#!/usr/bin/env bash
set -euo pipefail

# Qwen3.5-4B low-memory MCP238-lineage retry.
# The official Qwen3.5-4B checkpoint is native BF16. The pinned LiteRT-Torch
# loader forcibly expands it to FP32, which exceeds the 16 GB standard hosted
# runner during export. This wrapper preserves the exact official checkpoint
# dtype during model load while keeping the conversion recipe itself at
# dynamic_wi8_afp32 and all 32K/template/runtime validation gates unchanged.

BASE="model-conversion/qwen35/run_4b_q8_32768_ci.sh"
TMP="$RUNNER_TEMP/run_4b_q8_32768_ci_v2.generated.sh"
PATCH_DIR="$RUNNER_TEMP/qwen35-native-dtype-site"
mkdir -p "$PATCH_DIR"

cat > "$PATCH_DIR/sitecustomize.py" <<'PY'
import torch
import transformers

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
        print("QWEN35_NATIVE_CHECKPOINT_DTYPE_PATCH_ACTIVE requested=float32 effective=auto")
    return _orig(cls, *args, **kwargs)

_cls.from_pretrained = _native_dtype_from_pretrained
PY

cp "$BASE" "$TMP"
# Immutable new identity for the native-checkpoint-dtype retry.
sed -i 's/qwen35-4b-q8-32768-mcp238lineage-v1/qwen35-4b-q8-32768-mcp238lineage-v2/g' "$TMP"
sed -i 's/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v1/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v2/g' "$TMP"
# Apply the native BF16 loader only to the actual conversion subprocesses.
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
# Record provenance in release notes and validation manifest without changing
# model semantics or weakening any acceptance gate.
s=s.replace("'quantization_recipe':'dynamic_wi8_afp32','prefill_lengths':[128]", "'quantization_recipe':'dynamic_wi8_afp32','source_checkpoint_load_dtype':'native_auto_bfloat16','prefill_lengths':[128]")
s=s.replace("Qwen3.5 4B Q8 32K MCP238-lineage verified v1", "Qwen3.5 4B Q8 32K MCP238-lineage verified v2")
s=s.replace("Official pinned Qwen3.5-4B source.", "Official pinned Qwen3.5-4B source, loaded in its native BF16 checkpoint dtype to avoid unnecessary FP32 expansion on the hosted runner.")
p.write_text(s,encoding='utf-8')
PY
chmod +x "$TMP"

# Keep a lightweight resource trace in Actions logs so any infrastructure
# shutdown can be distinguished from a converter exception.
(
  while true; do
    echo "=== QWEN35_4B_RESOURCE_MONITOR $(date -u +%FT%TZ) ==="
    free -h || true
    swapon --show || true
    ps -eo pid,ppid,rss,vsz,pcpu,pmem,comm --sort=-rss | head -n 12 || true
    sleep 30
  done
) &
MONITOR_PID=$!
trap 'kill "$MONITOR_PID" 2>/dev/null || true' EXIT

bash "$TMP"
rc=$?
kill "$MONITOR_PID" 2>/dev/null || true
trap - EXIT
exit "$rc"
