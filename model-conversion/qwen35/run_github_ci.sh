#!/usr/bin/env bash
set -euo pipefail

CONFIG_PATH="${CONFIG_PATH:-model-conversion/qwen35/configs/2b-q8-4096.json}"
OUTPUT_DIR="${OUTPUT_DIR:-$PWD/qwen35-output}"
export CONFIG_PATH OUTPUT_DIR
export HF_HOME="${HF_HOME:-${RUNNER_TEMP:-/tmp}/hf-home}"
export TRANSFORMERS_CACHE="${TRANSFORMERS_CACHE:-$HF_HOME}"
export HF_HUB_DISABLE_TELEMETRY=1
export TOKENIZERS_PARALLELISM=false
export PYTHONUNBUFFERED=1
export MALLOC_ARENA_MAX=2

mkdir -p "$OUTPUT_DIR"

echo '=== Disk before cleanup ==='
df -h || true
if command -v sudo >/dev/null 2>&1; then
  sudo rm -rf /usr/share/dotnet || true
  sudo rm -rf /usr/local/lib/android || true
  sudo rm -rf /opt/ghc || true
  sudo rm -rf /usr/local/.ghcup || true
  sudo rm -rf /opt/hostedtoolcache/CodeQL || true
  sudo apt-get clean || true
fi
if command -v docker >/dev/null 2>&1; then
  docker system prune -af || true
fi
df -h || true

python -m pip install --upgrade pip setuptools wheel
python -m pip install --pre --upgrade litert-torch-nightly
python -m pip install --pre --upgrade 'litert-lm-api-nightly==0.15.0.dev20260727'
python -m pip install --upgrade psutil

python - <<'PY'
import importlib.metadata
for name in (
    'litert-torch-nightly', 'litert-lm-api-nightly',
    'litert-lm-builder', 'litert-lm-builder-nightly',
    'transformers', 'torch'
):
    try:
        print(name, importlib.metadata.version(name))
    except importlib.metadata.PackageNotFoundError:
        print(name, 'not-installed')
PY

check_support() {
python - <<'PY'
import inspect
from transformers import Qwen3_5Config
from litert_torch.generative.export_hf.model_ext.qwen3_5 import exportable_module
from litert_torch.generative.export_hf.model_ext.qwen3_5 import modeling_qwen3_5_static
from litert_torch.generative.export_hf.model_ext import metadata_builder
assert hasattr(exportable_module, 'LiteRTExportableModuleForQwen3_5Prefill')
assert hasattr(exportable_module, 'LiteRTExportableModuleForQwen3_5Generate')
assert hasattr(modeling_qwen3_5_static, 'Qwen3_5StaticGatedDeltaNet')
assert metadata_builder.build_executor_metadata is not None
src = inspect.getsource(metadata_builder.build_executor_metadata)
assert 'TYPE_LINEAR_ATTENTION' in src
assert 'kv_cache_c_' in src and 'kv_cache_r_' in src
assert 'kv_cache_k_' in src and 'kv_cache_v_' in src
print('Qwen3.5 Full Model Reauthoring and hybrid executor metadata support present.')
PY
}

if ! check_support; then
  echo 'Installed nightly lacks current Qwen3.5 support; overlaying Google main.'
  if ! python - <<'PY'
from transformers import Qwen3_5Config
print(Qwen3_5Config)
PY
  then
    python -m pip install --upgrade 'git+https://github.com/huggingface/transformers.git'
  fi
  rm -rf "${RUNNER_TEMP:-/tmp}/litert-torch-main"
  git clone --depth 1 https://github.com/google-ai-edge/litert-torch.git "${RUNNER_TEMP:-/tmp}/litert-torch-main"
  PKG_DIR="$(python - <<'PY'
import pathlib, litert_torch
print(pathlib.Path(litert_torch.__file__).resolve().parent)
PY
)"
  SRC="${RUNNER_TEMP:-/tmp}/litert-torch-main/litert_torch/generative/export_hf"
  rsync -a "$SRC/model_ext/qwen3_5/" "$PKG_DIR/generative/export_hf/model_ext/qwen3_5/"
  cp "$SRC/model_ext/exportables.py" "$PKG_DIR/generative/export_hf/model_ext/exportables.py"
  cp "$SRC/model_ext/metadata_builder.py" "$PKG_DIR/generative/export_hf/model_ext/metadata_builder.py"
  cp "$SRC/core/litert_lm_builder.py" "$PKG_DIR/generative/export_hf/core/litert_lm_builder.py"
  check_support
fi

python - <<'PY'
import litert_lm_builder
from litert_lm_builder.runtime.proto import executor_metadata_pb2
assert hasattr(litert_lm_builder.LitertLmFileBuilder, 'add_executor_metadata')
assert hasattr(executor_metadata_pb2, 'ExecutorMetadata')
assert hasattr(executor_metadata_pb2, 'StateBuffer')
print('LiteRT-LM builder executor metadata API present:', litert_lm_builder.__file__)
PY

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

set +e
/usr/bin/time -v python model-conversion/qwen35/convert_qwen35.py \
  --config "$CONFIG_PATH" \
  --output-dir "$OUTPUT_DIR" \
  --lightweight true 2>&1 | tee "${RUNNER_TEMP:-/tmp}/qwen35-lightweight.log"
light_rc=${PIPESTATUS[0]}
set -e

if [ "$light_rc" -ne 0 ]; then
  echo "Lightweight conversion failed with rc=$light_rc; retrying standard path."
  rm -rf "$OUTPUT_DIR"
  mkdir -p "$OUTPUT_DIR"
  /usr/bin/time -v python model-conversion/qwen35/convert_qwen35.py \
    --config "$CONFIG_PATH" \
    --output-dir "$OUTPUT_DIR" \
    --lightweight false 2>&1 | tee "${RUNNER_TEMP:-/tmp}/qwen35-standard.log"
fi

BUNDLE="$(find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.litertlm' -print -quit)"
test -n "$BUNDLE"
test -f "$BUNDLE"

echo "Bundle: $BUNDLE"
ls -lh "$OUTPUT_DIR"
df -h || true

/usr/bin/time -v python model-conversion/qwen35/verify_qwen35.py \
  --config "$CONFIG_PATH" \
  --bundle "$BUNDLE" \
  --manifest "$OUTPUT_DIR/conversion_manifest.json" 2>&1 | tee "$OUTPUT_DIR/verification.log"

python - <<'PY'
import json, os, pathlib
out = pathlib.Path(os.environ['OUTPUT_DIR'])
manifest = json.loads((out / 'conversion_manifest.json').read_text(encoding='utf-8'))
assert manifest.get('verification', {}).get('status') == 'PASS'
print(json.dumps(manifest, ensure_ascii=False, indent=2))
PY

echo 'QWEN35_CONVERSION_PASS'
