#!/usr/bin/env bash
set -euo pipefail

# Qwen3.5-4B high-swap MCP238-lineage retry.
# This preserves the v2 native-BF16 checkpoint loader and all model semantics,
# while expanding auxiliary swap from 24 GiB to 48 GiB. The v3 resource trace
# proved the 24 GiB auxiliary swap plus the runner's 3 GiB default swap became
# fully exhausted during Torch Export of prefill_128.

BASE="model-conversion/qwen35/run_4b_q8_32768_ci.sh"
TMP="$RUNNER_TEMP/run_4b_q8_32768_ci_v4.generated.sh"
PATCH_DIR="$RUNNER_TEMP/qwen35-native-dtype-highswap-site"
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
        print("QWEN35_NATIVE_CHECKPOINT_DTYPE_HIGHSWAP_PATCH_ACTIVE requested=float32 effective=auto")
    return _orig(cls, *args, **kwargs)

_cls.from_pretrained = _native_dtype_from_pretrained
PY

cp "$BASE" "$TMP"
sed -i 's/qwen35-4b-q8-32768-mcp238lineage-v1/qwen35-4b-q8-32768-mcp238lineage-v4/g' "$TMP"
sed -i 's/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v1/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v4/g' "$TMP"
# Expand only the auxiliary swap allocation; keep v2-style swappiness=80.
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
s=s.replace("'quantization_recipe':'dynamic_wi8_afp32','prefill_lengths':[128]", "'quantization_recipe':'dynamic_wi8_afp32','source_checkpoint_load_dtype':'native_auto_bfloat16','auxiliary_swap_gib':48,'prefill_lengths':[128]")
s=s.replace("Qwen3.5 4B Q8 32K MCP238-lineage verified v1", "Qwen3.5 4B Q8 32K MCP238-lineage verified v4")
s=s.replace("Official pinned Qwen3.5-4B source.", "Official pinned Qwen3.5-4B source loaded in native BF16; 48 GiB auxiliary swap is a runner-resource change only.")
p.write_text(s,encoding='utf-8')
PY
chmod +x "$TMP"

(
  while true; do
    echo "=== QWEN35_4B_V4_RESOURCE_MONITOR $(date -u +%FT%TZ) ==="
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
