#!/usr/bin/env python3
"""MCP240: make Qwen3.5-2B CPU Agent generation behave like a normal model turn.

This patch is intentionally model-correctness focused. It does not add output truncation,
repetition watchdog cancellation, no-repeat ngram hard bans, or a reduced max-output ceiling.

Applied after the retained MCP238 multipart patch and MCP239 CPU patch, it:
  * moves the app to the corrected MCP240 model release/file so the old downloaded bundle
    cannot be silently reused;
  * applies Qwen3.5's official non-thinking text sampler defaults in the allowlist;
  * passes the runtime-supported soft presence/repetition penalties on COMPAT inference;
  * performs a true Engine rebuild when Qwen3.5 starts a fresh COMPAT conversation, avoiding
    LiteRT-LM 0.15 cross-conversation recurrent-state reuse for gated-delta hybrids.

The model release itself is repaired by the MCP240 workflow: stable ChatML rendering plus both
Qwen3.5 natural stop token IDs are written into LlmMetadata while ExecutorMetadata is preserved.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = ROOT.parents[1]

OLD_MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza"
NEW_MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza MCP240"
OLD_MODEL_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm"
NEW_MODEL_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-4096-mcp240.litertlm"
NEW_MODEL_ID = "local-agent-plaza/Qwen3.5-2B-Q8-4096-MCP240"
NEW_MODEL_VERSION = "qwen35-2b-q8-4096-mcp240-natural-v2"
MODEL_SIZE = int(os.environ.get("MCP240_MODEL_SIZE", "0"))
MODEL_SHA256 = os.environ.get("MCP240_MODEL_SHA256", "").strip().lower()
PART_BYTES = 480_000_000


def fail(message: str) -> None:
    print(f"MCP240 patch failure: {message}", file=sys.stderr)
    raise SystemExit(1)


if MODEL_SIZE <= 0:
    fail("MCP240_MODEL_SIZE is missing or invalid")
if not re.fullmatch(r"[0-9a-f]{64}", MODEL_SHA256):
    fail("MCP240_MODEL_SHA256 is missing or invalid")

full_parts, remainder = divmod(MODEL_SIZE, PART_BYTES)
PART_SIZES = [PART_BYTES] * full_parts + ([remainder] if remainder else [])
if not PART_SIZES:
    fail("computed multipart list is empty")
PART_URLS = [
    f"https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/"
    f"{NEW_MODEL_VERSION}/{NEW_MODEL_FILE}.part{i:02d}"
    for i in range(len(PART_SIZES))
]


def patch_allowlist(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    models = data.get("models")
    if not isinstance(models, list):
        fail(f"allowlist has no models list: {path}")
    matches = [m for m in models if m.get("name") == OLD_MODEL_NAME]
    if len(matches) != 1:
        fail(f"expected exactly one MCP239 source model in {path}, found {len(matches)}")
    model = matches[0]
    model["name"] = NEW_MODEL_NAME
    model["modelId"] = NEW_MODEL_ID
    model["modelFile"] = NEW_MODEL_FILE
    model["sizeInBytes"] = MODEL_SIZE
    model["commitHash"] = NEW_MODEL_VERSION
    model["url"] = PART_URLS[0]
    model["description"] = (
        "Local Agent Plaza MCP240 Qwen3.5 2B Q8/4096 CPU bundle. ChatML rendering and Qwen3.5 "
        "natural stop-token metadata are repaired for LiteRT-LM 0.15; Agent COMPAT uses the "
        "official non-thinking sampler profile plus soft presence penalty. No host output truncation "
        "or repetition-cancel watchdog is used."
    )
    default = model.setdefault("defaultConfig", {})
    default["topK"] = 20
    default["topP"] = 1.0
    default["temperature"] = 1.0
    default["maxTokens"] = 4096
    default["accelerators"] = "cpu"
    default["maxContextLength"] = 4096
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    patch_allowlist(allowlist)

# Retarget the MCP238 multipart downloader to the corrected release/file and full-file digest.
repo_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt"
repo = repo_path.read_text(encoding="utf-8")
if OLD_MODEL_NAME not in repo:
    fail("MCP238 multipart model-name anchor missing from DownloadRepository")
repo = repo.replace(OLD_MODEL_NAME, NEW_MODEL_NAME)
repo = repo.replace("qwen35-2b-q8-4096-v1", NEW_MODEL_VERSION)
repo = repo.replace(OLD_MODEL_FILE, NEW_MODEL_FILE)
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

helper_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
helper = helper_path.read_text(encoding="utf-8")

# MCP239 CPU guard is applied first; retarget it to the new model identity.
if OLD_MODEL_NAME not in helper:
    fail("MCP239 CPU guard model-name anchor missing from LlmChatModelHelper")
helper = helper.replace(OLD_MODEL_NAME, NEW_MODEL_NAME)

# LiteRT-LM 0.15 exposes the soft repetition controls directly on sendMessageAsync.
import_anchor = "import com.google.ai.edge.litertlm.MessageCallback\n"
if "import com.google.ai.edge.litertlm.RepetitionPenaltyConfig\n" not in helper:
    if helper.count(import_anchor) != 1:
        fail("MessageCallback import anchor missing")
    helper = helper.replace(
        import_anchor,
        import_anchor + "import com.google.ai.edge.litertlm.RepetitionPenaltyConfig\n",
        1,
    )

# Keep EngineConfig with the live instance so recurrent-state hybrids can truly reset engine state.
old_instance = "data class LlmModelInstance(val engine: Engine, var conversation: Conversation)"
new_instance = (
    "data class LlmModelInstance(\n"
    "  var engine: Engine,\n"
    "  var conversation: Conversation,\n"
    "  val engineConfig: EngineConfig,\n"
    ")"
)
if helper.count(old_instance) != 1:
    fail(f"LlmModelInstance anchor count={helper.count(old_instance)}")
helper = helper.replace(old_instance, new_instance, 1)

old_assign = "model.instance = LlmModelInstance(engine = engine, conversation = conversation)"
new_assign = (
    "model.instance =\n"
    "        LlmModelInstance(engine = engine, conversation = conversation, engineConfig = engineConfig)"
)
if helper.count(old_assign) != 1:
    fail(f"LlmModelInstance assignment anchor count={helper.count(old_assign)}")
helper = helper.replace(old_assign, new_assign, 1)

# A fresh Conversation on a shared v0.15 Engine is not a guaranteed clean recurrent-state boundary
# for gated-delta/Mamba-like models. For MCP240 Qwen3.5 only, rebuild the Engine at that boundary.
old_reset = """      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
"""
new_reset = f"""      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      // MCP240_QWEN35_RECURRENT_STATE_RESET: a new Conversation on a shared LiteRT-LM 0.15
      // Engine can reuse recurrent state for gated-delta hybrids. Rebuild the Engine so each
      // COMPAT fresh pass starts from the model's actual zero state. This is a state-correctness
      // reset; it does not truncate model output or impose a generation watchdog.
      val hardResetQwen35Engine = model.name == \"{NEW_MODEL_NAME}\"
      val engine =
        if (hardResetQwen35Engine) {{
          instance.engine.close()
          Engine(instance.engineConfig).also {{ it.initialize() }}
        }} else {{
          instance.engine
        }}
"""
if helper.count(old_reset) != 1:
    fail(f"resetConversation engine anchor count={helper.count(old_reset)}")
helper = helper.replace(old_reset, new_reset, 1)

old_swap = """      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation
"""
new_swap = """      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.engine = engine
      instance.conversation = newConversation
"""
if helper.count(old_swap) != 1:
    fail(f"conversation swap anchor count={helper.count(old_swap)}")
helper = helper.replace(old_swap, new_swap, 1)

# Qwen3.5 official non-thinking text profile uses presence_penalty=2.0 and
# repetition_penalty=1.0. These are soft logit adjustments, not hard ngram bans.
old_send = """      extraContext = effectiveExtraContext,
      thinkingConfig =
"""
new_send = f"""      extraContext = effectiveExtraContext,
      repetitionPenaltyConfig =
        if (model.name == \"{NEW_MODEL_NAME}\" && isCompatPass) {{
          RepetitionPenaltyConfig(
            repetitionPenalty = 1.0f,
            presencePenalty = 2.0f,
          )
        }} else {{
          null
        }},
      thinkingConfig =
"""
if helper.count(old_send) != 1:
    fail(f"sendMessageAsync config anchor count={helper.count(old_send)}")
helper = helper.replace(old_send, new_send, 1)
helper_path.write_text(helper, encoding="utf-8")

# Fail-fast product invariants.
for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    models = json.loads(allowlist.read_text(encoding="utf-8"))["models"]
    matches = [m for m in models if m.get("name") == NEW_MODEL_NAME]
    if len(matches) != 1:
        fail(f"new model identity postcondition failed: {allowlist}")
    m = matches[0]
    cfg = m["defaultConfig"]
    expected = (cfg.get("topK"), cfg.get("topP"), cfg.get("temperature"), cfg.get("accelerators"))
    if expected != (20, 1.0, 1.0, "cpu"):
        fail(f"sampler/CPU postcondition failed: {allowlist}: {expected}")
    if m.get("sizeInBytes") != MODEL_SIZE or m.get("commitHash") != NEW_MODEL_VERSION:
        fail(f"model release metadata postcondition failed: {allowlist}")

final_helper = helper_path.read_text(encoding="utf-8")
for marker in (
    "MCP240_QWEN35_RECURRENT_STATE_RESET",
    "RepetitionPenaltyConfig(",
    "presencePenalty = 2.0f",
    NEW_MODEL_NAME,
):
    if marker not in final_helper:
        fail(f"helper postcondition missing {marker!r}")
if "NoRepeatNgramConfig" in final_helper:
    fail("MCP240 must not add a hard no-repeat ngram constraint")
if "maxOutputToken =" in final_helper:
    fail("MCP240 must not add a per-call maxOutputToken truncation")

print(
    f"MCP240 natural-generation patch complete: model_bytes={MODEL_SIZE} "
    f"parts={len(PART_SIZES)} sha256={MODEL_SHA256}"
)
