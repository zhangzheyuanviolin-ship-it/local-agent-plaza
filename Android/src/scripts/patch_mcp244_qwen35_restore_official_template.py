#!/usr/bin/env python3
"""MCP244: retarget the Qwen3.5 Agent product to the official tool-aware-template bundle.

Run after MCP238 + MCP239 + MCP240 + MCP241.  The MCP244 model is rebuilt from the
physically tool-proven MCP238 bundle, preserving its official Qwen3.5 Jinja template
byte-for-byte and adding only natural stop token 248046 beside 248044.

This app patch only changes the model identity/download metadata.  It deliberately keeps
the official Maven LiteRT-LM 0.15 runtime and MCP241's disabled runtime repetition
processor.  It adds no native runtime, hard truncation, watchdog, or no-repeat constraint.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]  # Android/src
REPO_ROOT = ROOT.parents[1]

OLD_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP240"
NEW_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP244"
OLD_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp240.litertlm"
NEW_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp244.litertlm"
OLD_TAG = "qwen35-2b-q8-4096-mcp240-natural-v2"
NEW_TAG = "qwen35-2b-q8-4096-mcp244-official-tool-template-v1"
NEW_ID = "local-agent-plaza/Qwen3.5-2B-Q8-4096-MCP244"
PART_BYTES = 480_000_000
MODEL_SIZE = int(os.environ.get("MCP244_MODEL_SIZE", "0"))
MODEL_SHA256 = os.environ.get("MCP244_MODEL_SHA256", "").strip().lower()


def fail(msg: str) -> None:
    print(f"MCP244 patch failure: {msg}", file=sys.stderr)
    raise SystemExit(1)


if MODEL_SIZE <= 0:
    fail("MCP244_MODEL_SIZE missing/invalid")
if not re.fullmatch(r"[0-9a-f]{64}", MODEL_SHA256):
    fail("MCP244_MODEL_SHA256 missing/invalid")

full_parts, remainder = divmod(MODEL_SIZE, PART_BYTES)
PART_SIZES = [PART_BYTES] * full_parts + ([remainder] if remainder else [])
if not PART_SIZES:
    fail("computed multipart list is empty")
PART_URLS = [
    f"https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/"
    f"{NEW_TAG}/{NEW_FILE}.part{i:02d}"
    for i in range(len(PART_SIZES))
]


def patch_allowlist(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    models = data.get("models")
    if not isinstance(models, list):
        fail(f"allowlist models missing: {path}")
    matches = [m for m in models if m.get("name") == OLD_NAME]
    if len(matches) != 1:
        fail(f"expected one MCP240 source model in {path}, found {len(matches)}")
    model = matches[0]
    model["name"] = NEW_NAME
    model["modelId"] = NEW_ID
    model["modelFile"] = NEW_FILE
    model["commitHash"] = NEW_TAG
    model["sizeInBytes"] = MODEL_SIZE
    model["url"] = PART_URLS[0]
    model["description"] = (
        "Local Agent Plaza MCP244 Qwen3.5 2B Q8/4096 CPU bundle. Rebuilt from the physically "
        "tool-proven MCP238 conversion while preserving the official Qwen3.5 tool-aware Jinja "
        "template byte-for-byte; natural stop IDs 248044 and 248046 are declared. Official "
        "LiteRT-LM 0.15 runtime; no runtime repetition processor or host hard-stop workaround."
    )
    cfg = model.setdefault("defaultConfig", {})
    cfg["topK"] = 20
    cfg["topP"] = 1.0
    cfg["temperature"] = 1.0
    cfg["maxTokens"] = 4096
    cfg["accelerators"] = "cpu"
    cfg["maxContextLength"] = 4096
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    patch_allowlist(allowlist)

# Retarget MCP238 multipart metadata already rewritten by MCP240.
repo_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt"
repo = repo_path.read_text(encoding="utf-8")
for old, new, label in (
    (OLD_NAME, NEW_NAME, "model name"),
    (OLD_TAG, NEW_TAG, "release tag"),
    (OLD_FILE, NEW_FILE, "model file"),
):
    count = repo.count(old)
    if count < 1:
        fail(f"DownloadRepository {label} anchor missing: {old}")
    repo = repo.replace(old, new)

repo, count_sizes = re.subn(
    r'(\.putString\("KEY_MODEL_MULTIPART_PART_SIZES", ")[^"]+("\))',
    lambda m: m.group(1) + ",".join(str(x) for x in PART_SIZES) + m.group(2),
    repo,
    count=1,
)
repo, count_sha = re.subn(
    r'(\.putString\("KEY_MODEL_EXPECTED_SHA256", ")[0-9a-fA-F]{64}("\))',
    lambda m: m.group(1) + MODEL_SHA256 + m.group(2),
    repo,
    count=1,
)
if count_sizes != 1 or count_sha != 1:
    fail(f"multipart metadata anchors failed: sizes={count_sizes} sha={count_sha}")
repo_path.write_text(repo, encoding="utf-8")

# Retarget Qwen-specific CPU/reset guards from MCP240 identity to the corrected MCP244 identity.
helper_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
helper = helper_path.read_text(encoding="utf-8")
count_name = helper.count(OLD_NAME)
if count_name < 1:
    fail("MCP240 helper model identity anchor missing")
helper = helper.replace(OLD_NAME, NEW_NAME)
helper_path.write_text(helper, encoding="utf-8")

# Fail-fast product invariants.
final_helper = helper_path.read_text(encoding="utf-8")
final_repo = repo_path.read_text(encoding="utf-8")
for forbidden in (
    "RepetitionPenaltyConfig",
    "presencePenalty = 2.0f",
    "repetitionPenalty = 1.0f",
    "NoRepeatNgramConfig",
    "maxOutputToken =",
    OLD_NAME,
    OLD_FILE,
    OLD_TAG,
):
    if forbidden in final_helper or forbidden in final_repo:
        fail(f"forbidden/obsolete value remained after MCP244 patch: {forbidden}")
for required in (
    "MCP241_QWEN35_AGENT_LOGITS_FIX",
    "MCP240_QWEN35_RECURRENT_STATE_RESET",
    NEW_NAME,
):
    if required not in final_helper:
        fail(f"helper postcondition missing: {required}")
for required in (NEW_NAME, NEW_FILE, NEW_TAG, MODEL_SHA256):
    if required not in final_repo:
        fail(f"download postcondition missing: {required}")

for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    models = json.loads(allowlist.read_text(encoding="utf-8"))["models"]
    matches = [m for m in models if m.get("name") == NEW_NAME]
    if len(matches) != 1:
        fail(f"new model identity postcondition failed: {allowlist}")
    m = matches[0]
    cfg = m["defaultConfig"]
    if m.get("modelFile") != NEW_FILE or m.get("commitHash") != NEW_TAG:
        fail(f"new model file/tag mismatch: {allowlist}")
    if m.get("sizeInBytes") != MODEL_SIZE:
        fail(f"new model size mismatch: {allowlist}")
    if (cfg.get("topK"), cfg.get("topP"), cfg.get("temperature"), cfg.get("accelerators")) != (
        20,
        1.0,
        1.0,
        "cpu",
    ):
        fail(f"sampler/backend mismatch: {allowlist}")
    if cfg.get("maxTokens") != 4096 or cfg.get("maxContextLength") != 4096:
        fail(f"4096 config mismatch: {allowlist}")

print(
    f"MCP244 app patch complete: model_bytes={MODEL_SIZE} parts={len(PART_SIZES)} "
    f"sha256={MODEL_SHA256}; official runtime retained"
)
