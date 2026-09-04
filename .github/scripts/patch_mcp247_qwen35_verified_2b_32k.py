#!/usr/bin/env python3
"""MCP247 emergency product patch.

Register only the independently verified MCP238-lineage Qwen3.5-2B Q8/32768 bundle while
preserving the MCP210/MCP223 Agent runtime architecture. Reuse the proven MCP238 multipart
downloader implementation, update only immutable model metadata/context, and force this Qwen3.5
model to CPU at both the allowlist and host-selection layers so persisted MCP246 preferences cannot
route the hybrid graph back to the Android GPU delegate.

This script deliberately does NOT apply MCP240/MCP241/MCP242/MCP244/MCP245/MCP246 behavioral or
runtime experiments. In particular it does not rebuild the Engine at conversation boundaries, does
not install a custom LiteRT-LM AAR/JNI, and does not repack/mutate the verified model bytes.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = REPO_ROOT / "Android/src"
LEGACY = ANDROID_ROOT / "scripts/patch_mcp238_qwen35_model_download.py"

MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 32768 Plaza MCP247"
MODEL_ID = "local-agent-plaza/Qwen3.5-2B-Q8-32768-MCP238-lineage"
MODEL_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-32768.litertlm"
MODEL_VERSION = "qwen35-2b-q8-32768-mcp238lineage-v3"
MODEL_SIZE = 4_780_966_112
MODEL_SHA256 = "364f975167ba9bb083d9c01f0d600e9b1bb2955962d320c30cf4375b1fe42cb1"
PART_SIZES = [480_000_000] * 9 + [460_966_112]


def fail(message: str) -> None:
    print(f"MCP247 patch failure: {message}", file=sys.stderr)
    raise SystemExit(1)


source = LEGACY.read_text(encoding="utf-8")
replacements = {
    'MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza"': f'MODEL_NAME = "{MODEL_NAME}"',
    'MODEL_ID = "local-agent-plaza/Qwen3.5-2B-Q8-4096"': f'MODEL_ID = "{MODEL_ID}"',
    'MODEL_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm"': f'MODEL_FILE = "{MODEL_FILE}"',
    'MODEL_VERSION = "qwen35-2b-q8-4096-v1"': f'MODEL_VERSION = "{MODEL_VERSION}"',
    'MODEL_SHA256 = "a3a7cd9d05242200a4f819228e7cd3987e046f5fd81b030d71eb88e4a96fcd03"': f'MODEL_SHA256 = "{MODEL_SHA256}"',
    'PART_URLS = [f"{RELEASE_BASE}/{MODEL_FILE}.part{i:02d}" for i in range(4)]': 'PART_URLS = [f"{RELEASE_BASE}/{MODEL_FILE}.part{i:02d}" for i in range(10)]',
    'PART_SIZES = [1_200_000_000, 1_200_000_000, 1_200_000_000, 1_180_966_112]': 'PART_SIZES = [480_000_000] * 9 + [460_966_112]',
    '"maxTokens": 1536,': '"maxTokens": 4096,',
    '"accelerators": "gpu,cpu",': '"accelerators": "cpu",',
    '"maxContextLength": 4096,': '"maxContextLength": 32768,',
}
for old, new in replacements.items():
    count = source.count(old)
    if count != 1:
        fail(f"expected one legacy replacement anchor, found {count}: {old}")
    source = source.replace(old, new, 1)

# Descriptive text only; correctness does not depend on these replacements.
source = source.replace("Q8/4096 bundle", "Q8/32768 bundle")
source = source.replace("downloads four", "downloads ten")
source = source.replace("four public", "ten public")
source = source.replace("4096 context.", "32768 context.")

# The retained MCP238 generator contains a backslash-bearing expression inside an f-string which
# Python 3.11 rejects. Materialize that expression first, exactly as the verified MCP238 v3 wrapper
# did, without changing the emitted Kotlin multipart metadata.
suffix = ".join(PART_URLS)}"
pos = source.find(suffix)
if pos < 0:
    fail("PART_URLS f-string expression anchor not found")
start = source.rfind("{", 0, pos)
end = pos + len(suffix)
if start < 0:
    fail("PART_URLS f-string opening brace not found")
expression = source[start:end]
if "PART_URLS" not in expression:
    fail(f"unexpected PART_URLS expression: {expression!r}")
source = source[:start] + "{PART_URLS_KOTLIN}" + source[end:]
insert_anchor = 'repo_insert = f"""'
if source.count(insert_anchor) != 1:
    fail(f"expected one repo_insert anchor, found {source.count(insert_anchor)}")
source = source.replace(
    insert_anchor,
    'PART_URLS_KOTLIN = "\\\\n".join(PART_URLS)\n' + insert_anchor,
    1,
)

compiled = compile(source, str(LEGACY), "exec")
namespace = {"__file__": str(LEGACY), "__name__": "__main__"}
exec(compiled, namespace, namespace)

# Host-level CPU guard: an in-place upgrade from MCP246 preserves user configuration. A stale GPU
# preference must not override the CPU-only allowlist for this one hybrid model. All other models
# retain their configured accelerator and the MCP210 Engine/conversation lifecycle unchanged.
helper_path = ANDROID_ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
helper = helper_path.read_text(encoding="utf-8")
sentinel = "// MCP247_QWEN35_VERIFIED_2B_CPU_ONLY"
if sentinel not in helper:
    old = '''    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
'''
    new = f'''    // MCP247_QWEN35_VERIFIED_2B_CPU_ONLY: preserve the stable MCP210 Engine lifecycle while
    // preventing persisted MCP246 GPU preferences from routing this gated-delta model to GPU.
    val configuredAccelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val accelerator =
      if (model.name == "{MODEL_NAME}") Accelerator.CPU.label else configuredAccelerator
'''
    if helper.count(old) != 1:
        fail(f"expected one initialization accelerator anchor, found {helper.count(old)}")
    helper = helper.replace(old, new, 1)
    helper_path.write_text(helper, encoding="utf-8")

# Hard postconditions on the two embedded allowlists.
for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ANDROID_ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    data = json.loads(allowlist.read_text(encoding="utf-8"))
    models = data.get("models", [])
    matches = [m for m in models if m.get("name") == MODEL_NAME]
    if len(matches) != 1:
        fail(f"expected exactly one verified Qwen model in {allowlist}, got {len(matches)}")
    m = matches[0]
    cfg = m.get("defaultConfig", {})
    expected = (
        m.get("modelId"), m.get("modelFile"), m.get("commitHash"), m.get("sizeInBytes"),
        cfg.get("accelerators"), cfg.get("maxContextLength"), cfg.get("maxTokens"),
        cfg.get("topK"), cfg.get("topP"), cfg.get("temperature"),
    )
    wanted = (
        MODEL_ID, MODEL_FILE, MODEL_VERSION, MODEL_SIZE,
        "cpu", 32768, 4096, 20, 0.8, 0.6,
    )
    if expected != wanted:
        fail(f"verified Qwen metadata mismatch in {allowlist}: {expected!r} != {wanted!r}")
    obsolete = [
        x for x in models
        if x.get("modelId") == "paulsp94/Qwen3.5-2B-LiteRT-LM"
        and x.get("modelFile") in {"qwen35_2b.litertlm", "qwen35_2b_q4.litertlm"}
    ]
    if obsolete:
        fail(f"obsolete community Qwen3.5-2B entries remain in {allowlist}")

repo_text = (ANDROID_ROOT / "app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt").read_text(encoding="utf-8")
for required in (MODEL_NAME, MODEL_FILE, MODEL_VERSION, MODEL_SHA256, "KEY_MODEL_MULTIPART_URLS"):
    if required not in repo_text:
        fail(f"DownloadRepository missing {required!r}")
for i in range(10):
    if f"{MODEL_FILE}.part{i:02d}" not in repo_text:
        fail(f"DownloadRepository missing model part {i:02d}")
if ",".join(str(x) for x in PART_SIZES) not in repo_text:
    fail("DownloadRepository multipart sizes mismatch")

worker_text = (ANDROID_ROOT / "app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt").read_text(encoding="utf-8")
for required in (
    "MCP238 Qwen3.5 multipart reconstruction",
    "MessageDigest.getInstance(\"SHA-256\")",
    "Multipart SHA-256 mismatch",
):
    if required not in worker_text:
        fail(f"DownloadWorker multipart integrity path missing {required!r}")

final_helper = helper_path.read_text(encoding="utf-8")
for required in (
    sentinel,
    'data class LlmModelInstance(val engine: Engine, var conversation: Conversation)',
    'val engine = instance.engine',
    'COMPAT_FRESH_REASON_TOP_LEVEL',
    'COMPAT_FRESH_REASON_TOOL_CONTINUATION',
    'put("enable_thinking", false)',
    'put("thinking_token_budget", 0)',
):
    if required not in final_helper:
        fail(f"stable Agent invariant missing after MCP247 patch: {required!r}")
for forbidden in (
    "MCP240_QWEN35_RECURRENT_STATE_RESET",
    "MCP241_QWEN35_AGENT_LOGITS_FIX",
    "MCP242_LOCAL_LITERTLM_AAR",
    "prefillPrefaceOnInit",
    "instance.engineConfig",
):
    if forbidden in final_helper:
        fail(f"forbidden failed-experiment marker leaked into MCP247 helper: {forbidden!r}")

print(
    "MCP247 verified Qwen3.5-2B registration PASS: "
    f"file={MODEL_FILE} size={MODEL_SIZE} sha256={MODEL_SHA256} context=32768 backend=CPU"
)
