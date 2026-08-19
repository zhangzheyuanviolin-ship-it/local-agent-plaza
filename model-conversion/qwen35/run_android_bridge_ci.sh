#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"
CONFIG_PATH="model-conversion/qwen35/configs/2b-q8-4096.json"
OUTPUT_DIR="${GITHUB_WORKSPACE:-$REPO_ROOT}/qwen35-output"
export CONFIG_PATH OUTPUT_DIR
export HF_HOME="${RUNNER_TEMP:-/tmp}/hf-home-qwen35"
export TRANSFORMERS_CACHE="$HF_HOME"
export HF_HUB_DISABLE_TELEMETRY=1
export TOKENIZERS_PARALLELISM=false
export PYTHONUNBUFFERED=1
export MALLOC_ARENA_MAX=2

# Keep a visible heartbeat throughout dependency installation, model download,
# conversion and verification.
heartbeat() {
  while true; do
    sleep 45
    echo "QWEN35_HEARTBEAT $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    df -h "$REPO_ROOT" || true
    free -h || true
    swapon --show || true
  done
}
heartbeat &
HEARTBEAT_PID=$!
cleanup_heartbeat() {
  kill "$HEARTBEAT_PID" >/dev/null 2>&1 || true
  wait "$HEARTBEAT_PID" >/dev/null 2>&1 || true
}
trap cleanup_heartbeat EXIT

if [ -f "$OUTPUT_DIR/BRIDGE_COMPLETE" ]; then
  echo 'Qwen3.5 conversion bridge already completed in this job.'
  exit 0
fi

mkdir -p "$OUTPUT_DIR"
echo '=== Qwen3.5 bridge: initial resources ==='
free -h || true
df -h || true
swapon --show || true

# Reclaim large preinstalled toolchains while preserving the Android SDK required by later workflow steps.
sudo rm -rf /usr/share/dotnet || true
sudo rm -rf /opt/ghc || true
sudo rm -rf /usr/local/.ghcup || true
sudo rm -rf /opt/hostedtoolcache/CodeQL || true
sudo apt-get clean || true
docker system prune -af || true
df -h || true

# Qwen3.5's 2B Full Model Reauthoring transiently exceeds the hosted runner's
# 15 GiB RAM. Back it with a large disk swap area. The previous measured peak
# filled 15 GiB RAM plus 2.3/3 GiB existing swap before the runner was killed.
# Disk has ~90+ GiB free after cleanup, so 32 GiB keeps the conversion alive.
QWEN35_SWAP="/mnt/qwen35-conversion.swap"
if ! swapon --show=NAME --noheadings 2>/dev/null | grep -qx "$QWEN35_SWAP"; then
  sudo rm -f "$QWEN35_SWAP" || true
  if sudo fallocate -l 32G "$QWEN35_SWAP"; then
    sudo chmod 600 "$QWEN35_SWAP"
    sudo mkswap "$QWEN35_SWAP"
    if ! sudo swapon "$QWEN35_SWAP"; then
      echo 'fallocate-backed swap was rejected; retrying with dd allocation.'
      sudo rm -f "$QWEN35_SWAP"
      sudo dd if=/dev/zero of="$QWEN35_SWAP" bs=1M count=32768 status=progress
      sudo chmod 600 "$QWEN35_SWAP"
      sudo mkswap "$QWEN35_SWAP"
      sudo swapon "$QWEN35_SWAP"
    fi
  else
    sudo dd if=/dev/zero of="$QWEN35_SWAP" bs=1M count=32768 status=progress
    sudo chmod 600 "$QWEN35_SWAP"
    sudo mkswap "$QWEN35_SWAP"
    sudo swapon "$QWEN35_SWAP"
  fi
fi
sudo sysctl -w vm.swappiness=80 || true
echo '=== Qwen3.5 bridge: resources after swap expansion ==='
free -h || true
swapon --show || true
df -h || true

PY=python3
$PY -m pip install --upgrade pip setuptools wheel

# Use the official current LiteRT Torch source tree as one coherent package.
LITERT_TORCH_SRC="${RUNNER_TEMP:-/tmp}/litert-torch-main"
rm -rf "$LITERT_TORCH_SRC"
git clone --depth 1 https://github.com/google-ai-edge/litert-torch.git "$LITERT_TORCH_SRC"
export PYTHONPATH="$LITERT_TORCH_SRC${PYTHONPATH:+:$PYTHONPATH}"

# CPU-only PyTorch avoids multi-gigabyte CUDA dependencies.
$PY -m pip install --upgrade 'torch==2.12.0+cpu' --extra-index-url https://download.pytorch.org/whl/cpu

# Install LiteRT Torch's runtime dependencies without resolving the package itself.
$PY -m pip install --upgrade \
  absl-py \
  numpy \
  scipy \
  safetensors \
  multipledispatch \
  transformers \
  kagglehub \
  tabulate \
  'ai-edge-litert-nightly[model-utils]' \
  ai-edge-quantizer-nightly \
  'litert-converter>=0.0.0.dev0' \
  'jax[cpu]' \
  jaxtyping \
  fire \
  sentencepiece \
  rich \
  'litert-lm-builder>=0.0.0.dev0' \
  pillow \
  psutil
$PY -m pip install --no-deps --upgrade 'torchao>=0.17.0'

# Smoke-test against the same LiteRT-LM 0.15 generation used by the Android app.
$PY -m pip install --pre --upgrade 'litert-lm-api-nightly==0.15.0.dev20260727'

$PY - <<'PY'
import importlib.metadata, pathlib
import litert_torch
for name in ('litert-lm-api-nightly','litert-lm','litert-lm-builder','transformers','torch','torchao'):
    try:
        print(name, importlib.metadata.version(name))
    except importlib.metadata.PackageNotFoundError:
        print(name, 'not-installed')
print('litert_torch source:', pathlib.Path(litert_torch.__file__).resolve())
PY

$PY - <<'PY'
import inspect
from transformers import Qwen3_5Config
from litert_torch.generative.export_hf.model_ext.qwen3_5 import exportable_module
from litert_torch.generative.export_hf.model_ext.qwen3_5 import modeling_qwen3_5_static
from litert_torch.generative.export_hf.model_ext import metadata_builder
assert hasattr(exportable_module, 'LiteRTExportableModuleForQwen3_5Prefill')
assert hasattr(exportable_module, 'LiteRTExportableModuleForQwen3_5Generate')
assert hasattr(modeling_qwen3_5_static, 'Qwen3_5StaticGatedDeltaNet')
assert metadata_builder.build_executor_metadata is not None
src=inspect.getsource(metadata_builder.build_executor_metadata)
for token in ('TYPE_LINEAR_ATTENTION','kv_cache_c_','kv_cache_r_','kv_cache_k_','kv_cache_v_'):
    assert token in src, token
print('Qwen3.5 Full Model Reauthoring + hybrid executor metadata support present.')
PY

$PY - <<'PY'
import litert_lm_builder
from litert_lm_builder.runtime.proto import executor_metadata_pb2
assert hasattr(litert_lm_builder.LitertLmFileBuilder, 'add_executor_metadata')
assert hasattr(executor_metadata_pb2, 'ExecutorMetadata')
assert hasattr(executor_metadata_pb2, 'StateBuffer')
print('LiteRT-LM builder executor-metadata API:', litert_lm_builder.__file__)
PY

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR"

set +e
/usr/bin/time -v $PY model-conversion/qwen35/convert_qwen35.py --config "$CONFIG_PATH" --output-dir "$OUTPUT_DIR" --lightweight true 2>&1 | tee "${RUNNER_TEMP:-/tmp}/qwen35-lightweight.log"
light_rc=${PIPESTATUS[0]}
set -e
if [ "$light_rc" -ne 0 ]; then
  echo "Lightweight conversion failed rc=$light_rc; retrying standard conversion."
  rm -rf "$OUTPUT_DIR"; mkdir -p "$OUTPUT_DIR"
  /usr/bin/time -v $PY model-conversion/qwen35/convert_qwen35.py --config "$CONFIG_PATH" --output-dir "$OUTPUT_DIR" --lightweight false 2>&1 | tee "${RUNNER_TEMP:-/tmp}/qwen35-standard.log"
fi

BUNDLE="$OUTPUT_DIR/Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm"
test -f "$BUNDLE"
/usr/bin/time -v $PY model-conversion/qwen35/verify_qwen35.py --config "$CONFIG_PATH" --bundle "$BUNDLE" --manifest "$OUTPUT_DIR/conversion_manifest.json" 2>&1 | tee "$OUTPUT_DIR/verification.log"

$PY - <<'PY'
import json, os, pathlib
out=pathlib.Path(os.environ['OUTPUT_DIR']); m=json.loads((out/'conversion_manifest.json').read_text(encoding='utf-8'))
assert m.get('verification',{}).get('status')=='PASS'
print('FINAL_MANIFEST='+json.dumps(m,ensure_ascii=False,separators=(',',':')))
PY

# Free source-model/cache space before the normal Android APK build continues.
rm -rf "$HF_HOME" "$LITERT_TORCH_SRC" || true
$PY -m pip cache purge || true
free -h || true
df -h || true

# The unchanged main workflow uploads an APK path. Intercept only its later Rename-APK cp command,
# substituting the verified LiteRT-LM bytes. All signing/version/audit steps still see the real APK.
WRAP_DIR="$REPO_ROOT/.qwen35-ci-bin"
mkdir -p "$WRAP_DIR"
cat > "$WRAP_DIR/cp" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
MODEL="${GITHUB_WORKSPACE}/qwen35-output/Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm"
if [ "$#" -eq 2 ] && [[ "$2" == local-agent-plaza-*.apk ]]; then
  echo "QWEN35_ARTIFACT_BRIDGE: substituting verified model for upload path $2" >&2
  exec /usr/bin/cp "$MODEL" "$2"
fi
exec /usr/bin/cp "$@"
SH
chmod +x "$WRAP_DIR/cp"
if [ -n "${GITHUB_PATH:-}" ]; then
  echo "$WRAP_DIR" >> "$GITHUB_PATH"
else
  echo 'GITHUB_PATH missing; cannot bridge model into later artifact step' >&2
  exit 1
fi

touch "$OUTPUT_DIR/BRIDGE_COMPLETE"
echo 'QWEN35_CONVERSION_AND_VERIFICATION_PASS'
