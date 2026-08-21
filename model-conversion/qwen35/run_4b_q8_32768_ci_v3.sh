#!/usr/bin/env bash
set -euo pipefail

# Qwen3.5-4B lower-memory MCP238-lineage retry.
# Keeps all acceptance semantics identical to the verified 2B route:
# official pinned source, dynamic_wi8_afp32, real 32768 cache, prefill 128,
# exact official Jinja, LiteRT-LM 0.15 runtime validation and immutable release.
# The only resource-level changes are native BF16 source loading,
# low_cpu_mem_usage, conservative allocator settings and lower swappiness.

BASE="model-conversion/qwen35/run_4b_q8_32768_ci.sh"
TMP="$RUNNER_TEMP/run_4b_q8_32768_ci_v3.generated.sh"
PATCH_DIR="$RUNNER_TEMP/qwen35-native-dtype-lowmem-site"
mkdir -p "$PATCH_DIR"

cat > "$PATCH_DIR/sitecustomize.py" <<'PY'
import torch
import transformers

_cls = transformers.AutoModelForCausalLM
_orig = _cls.from_pretrained.__func__

@classmethod
def _native_dtype_lowmem_from_pretrained(cls, *args, **kwargs):
    changed = False
    if kwargs.get("torch_dtype", None) is torch.float32:
        kwargs["torch_dtype"] = "auto"
        changed = True
    if kwargs.get("dtype", None) is torch.float32:
        kwargs["dtype"] = "auto"
        changed = True
    kwargs.setdefault("low_cpu_mem_usage", True)
    if changed:
        print("QWEN35_NATIVE_BF16_LOWMEM_PATCH_ACTIVE requested=float32 effective=auto low_cpu_mem_usage=true")
    return _orig(cls, *args, **kwargs)

_cls.from_pretrained = _native_dtype_lowmem_from_pretrained
PY

cp "$BASE" "$TMP"
sed -i 's/qwen35-4b-q8-32768-mcp238lineage-v1/qwen35-4b-q8-32768-mcp238lineage-v3/g' "$TMP"
sed -i 's/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v1/local-agent-plaza.qwen35-4b-q8-32768.mcp238-lineage.v3/g' "$TMP"
sed -i 's/vm.swappiness=80/vm.swappiness=20/g' "$TMP"
python - "$TMP" "$PATCH_DIR" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); patch=sys.argv[2]
s=p.read_text(encoding='utf-8')
needle='python model-conversion/qwen35/convert_qwen35.py'
repl=f'env PYTHONPATH="{patch}:$PYTHONPATH" PYTHONMALLOC=malloc MALLOC_ARENA_MAX=1 MALLOC_TRIM_THRESHOLD_=65536 python model-conversion/qwen35/convert_qwen35.py'
count=s.count(needle)
if count != 2:
    raise SystemExit(f'expected two converter invocations, found {count}')
s=s.replace(needle,repl)
s=s.replace("'quantization_recipe':'dynamic_wi8_afp32','prefill_lengths':[128]", "'quantization_recipe':'dynamic_wi8_afp32','source_checkpoint_load_dtype':'native_auto_bfloat16','low_cpu_mem_usage':True,'prefill_lengths':[128]")
s=s.replace("Qwen3.5 4B Q8 32K MCP238-lineage verified v1", "Qwen3.5 4B Q8 32K MCP238-lineage verified v3")
s=s.replace("Official pinned Qwen3.5-4B source.", "Official pinned Qwen3.5-4B source loaded in native BF16 with low_cpu_mem_usage; conversion semantics remain MCP238-lineage Q8/32K.")
p.write_text(s,encoding='utf-8')
PY
chmod +x "$TMP"

ulimit -n 65535 || true
export OMP_NUM_THREADS=4
export MKL_NUM_THREADS=4
export OPENBLAS_NUM_THREADS=4
export NUMEXPR_NUM_THREADS=4

(
  while true; do
    echo "=== QWEN35_4B_V3_RESOURCE_MONITOR $(date -u +%FT%TZ) ==="
    free -h || true
    swapon --show || true
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
