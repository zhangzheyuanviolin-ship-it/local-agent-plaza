#!/usr/bin/env python3
"""MCP249 targeted COMPAT adaptation for the MCP248 Ministral/Phi 4K artifacts.

Scope is deliberately narrow:
- preserve every existing MCP247/MCP248 Agent, model, media, and LiteRT path for all other models;
- keep the exact already-published MCP248 model artifacts (no reconversion/repacking);
- give only Ministral-3-3B-Instruct-2512 and Phi-4-mini-instruct a tighter 4K COMPAT budget policy;
- add family-specific protocol/finalization guidance;
- use a low-variance COMPAT sampler only while those two exact models are inside Agent COMPAT mode.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
ANDROID_ROOT = REPO_ROOT / "Android/src"
AGENT_DIR = ANDROID_ROOT / "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"
UI_DIR = ANDROID_ROOT / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat"

MINISTRAL_NAME = "Ministral-3-3B-Instruct-2512 LiteRT"
PHI_NAME = "Phi-4-mini-instruct Q8 4096 LiteRT"
MINISTRAL_FILE = "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm"
PHI_FILE = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"


def fail(message: str) -> None:
    print(f"MCP249 patch failure: {message}", file=sys.stderr)
    raise SystemExit(1)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


# 1. Tool-result budget: retain the proven generic formula byte-for-byte for every other model.
# For the two exact 4096-KV artifacts, reserve a practical final-answer window and raise the visible
# tool-result payload from the MCP248 ~1.2K-char floor to a bounded ~2.4K-char target.
tooling_path = AGENT_DIR / "AgentTooling.kt"
tooling = tooling_path.read_text(encoding="utf-8")
const_anchor = 'private const val COMPAT_TOOL_RESULT_PROMPT_OVERHEAD_TOKENS = 1400\n'
const_insert = const_anchor + '''// MCP249_TIGHT_4K_BUDGET_V1: exact-model-only COMPAT budgeting; generic models stay unchanged.\nprivate const val MCP249_TIGHT_4K_RESERVED_OUTPUT_TOKENS = 1024\nprivate const val MCP249_TIGHT_4K_PROMPT_OVERHEAD_TOKENS = 900\nprivate const val MCP249_TIGHT_4K_MIN_RESULT_CHARS = 2200\nprivate const val MCP249_TIGHT_4K_MAX_RESULT_CHARS = 2400\n'''
tooling = replace_once(tooling, const_anchor, const_insert, "AgentTooling constants")

budget_old = '''  val availableToolTokens =
    (contextWindow - reservedOutputTokens - COMPAT_TOOL_RESULT_PROMPT_OVERHEAD_TOKENS)
      .coerceAtLeast(900)
  return (availableToolTokens * 1.35f).toInt()
    .coerceIn(MIN_COMPAT_MODEL_TOOL_RESULT_CHARS, MAX_COMPAT_MODEL_TOOL_RESULT_CHARS)
'''
budget_new = '''  val normalizedName = model.name.lowercase()
  val mcp249Tight4kCompatModel =
    normalizedName.contains("ministral-3-3b-instruct-2512") ||
      normalizedName.contains("phi-4-mini-instruct")
  if (mcp249Tight4kCompatModel && contextWindow <= 4096) {
    val availableToolTokens =
      (
        contextWindow -
          reservedOutputTokens.coerceAtMost(MCP249_TIGHT_4K_RESERVED_OUTPUT_TOKENS) -
          MCP249_TIGHT_4K_PROMPT_OVERHEAD_TOKENS
      )
        .coerceAtLeast(1600)
    return (availableToolTokens * 1.10f).toInt()
      .coerceIn(MCP249_TIGHT_4K_MIN_RESULT_CHARS, MCP249_TIGHT_4K_MAX_RESULT_CHARS)
  }
  val availableToolTokens =
    (contextWindow - reservedOutputTokens - COMPAT_TOOL_RESULT_PROMPT_OVERHEAD_TOKENS)
      .coerceAtLeast(900)
  return (availableToolTokens * 1.35f).toInt()
    .coerceIn(MIN_COMPAT_MODEL_TOOL_RESULT_CHARS, MAX_COMPAT_MODEL_TOOL_RESULT_CHARS)
'''
tooling = replace_once(tooling, budget_old, budget_new, "AgentTooling targeted 4K budget")
tooling_path.write_text(tooling, encoding="utf-8")

# 2. Runtime COMPAT envelope/history/sampler. Exact-name helpers isolate behavior from LocoOperator,
# Qwen, Gemma, FunctionGemma, and every other previously working model.
helper_path = UI_DIR / "LlmChatModelHelper.kt"
helper = helper_path.read_text(encoding="utf-8")
helper_const_anchor = 'private const val COMPAT_NEXT_ACTION_MARKER = "\\n\\nNEXT_ACTION\\n"\n'
helper_const_insert = helper_const_anchor + '''private const val MCP249_TIGHT_4K_HISTORY_MIN_CHARS = 2200\nprivate const val MCP249_TIGHT_4K_HISTORY_MAX_CHARS = 2400\nprivate const val MCP249_TIGHT_4K_RESERVED_OUTPUT_TOKENS = 1024\nprivate const val MCP249_TIGHT_4K_PROMPT_OVERHEAD_TOKENS = 900\nprivate const val MCP249_MINISTRAL_COMPAT_MARKER = "MCP249_MINISTRAL_COMPAT_V1"\nprivate const val MCP249_PHI_COMPAT_MARKER = "MCP249_PHI4MINI_COMPAT_V1"\n'''
helper = replace_once(helper, helper_const_anchor, helper_const_insert, "LlmChatModelHelper constants")

prepare_old = '''    val compactedRawInput = compactCompatEnvelope(input)
'''
prepare_new = '''    val compactedRawInput = compactCompatEnvelope(model = model, input = input)
'''
helper = replace_once(helper, prepare_old, prepare_new, "model-aware compact envelope call")

final_old = '''    val finalInput = compactPreparedCompatInput(prepared.input)
'''
final_new = '''    val finalInput = compactPreparedCompatInput(model = model, input = prepared.input)
'''
helper = replace_once(helper, final_old, final_new, "model-aware continuation compaction call")

sig_old = '''  private fun compactCompatEnvelope(input: String): String {
'''
sig_new = '''  private fun compactCompatEnvelope(model: Model, input: String): String {
'''
helper = replace_once(helper, sig_old, sig_new, "compact envelope signature")

payload_call_old = '''    val compactPayload = compactCompatInstructionPayload(payload)
'''
payload_call_new = '''    val compactPayload = compactCompatInstructionPayload(model = model, payload = payload)
'''
helper = replace_once(helper, payload_call_old, payload_call_new, "compact payload model call")

payload_sig_old = '''  private fun compactCompatInstructionPayload(payload: String): String {
'''
payload_sig_new = '''  private fun compactCompatInstructionPayload(model: Model, payload: String): String {
'''
helper = replace_once(helper, payload_sig_old, payload_sig_new, "compact payload signature")

payload_return_old = '''    return """
Qwen-compatible tool mode. Reply in the user's language. Thinking is off.
If a tool is needed, output ONLY <tool_call>{"tool":"NAME","arguments":{...}}</tool_call>. One tool per turn; never mix prose with a tool call. Use enabled tools only. After TOOL_RESULT, either make one next tool call or give the final answer. Do not repeat an identical call without new information. For web search use search_web. Stop after the final answer.

Available compatibility tools:
$compactTools

Enabled skills for this session:
$compactSkills
"""
      .trimIndent()
'''
payload_return_new = '''    val familyRule = mcp249CompatFamilyRule(model.name)
    return buildString {
      append("Qwen-compatible tool mode. Reply in the user's language. Thinking is off.\\n")
      append(
        "If a tool is needed, output ONLY <tool_call>{\\\"tool\\\":\\\"NAME\\\",\\\"arguments\\\":{...}}</tool_call>. " +
          "One tool per turn; never mix prose with a tool call. Use enabled tools only. After TOOL_RESULT, either make one next tool call or give the final answer. " +
          "Do not repeat an identical call without new information. For web search use search_web. Stop after the final answer.\\n"
      )
      if (familyRule.isNotBlank()) {
        append(familyRule)
        append('\\n')
      }
      append("\\nAvailable compatibility tools:\\n")
      append(compactTools)
      append("\\n\\nEnabled skills for this session:\\n")
      append(compactSkills)
    }
'''
helper = replace_once(helper, payload_return_old, payload_return_new, "family-specific compact protocol")

prepared_sig_old = '''  private fun compactPreparedCompatInput(input: String): String {
'''
prepared_sig_new = '''  private fun compactPreparedCompatInput(model: Model, input: String): String {
'''
helper = replace_once(helper, prepared_sig_old, prepared_sig_new, "prepared input signature")

prepared_tail_old = '''      append("Do not repeat an identical call without new information. ")
      append("Preserve every XLSX 行事实 metric, unit, year, and value exactly. ")
      append(
        "If context_safety_note says truncated, use visible history only and mention the saved audit."
      )
'''
prepared_tail_new = '''      append("Do not repeat an identical call without new information. ")
      append("Preserve every XLSX 行事实 metric, unit, year, and value exactly. ")
      append(
        "If context_safety_note says truncated, use visible history only and mention the saved audit."
      )
      val familyRule = mcp249CompatContinuationRule(model.name)
      if (familyRule.isNotBlank()) {
        append(' ')
        append(familyRule)
      }
'''
helper = replace_once(helper, prepared_tail_old, prepared_tail_new, "family-specific continuation protocol")

history_old = '''    val availableHistoryTokens =
      (contextWindow - reservedOutputTokens - COMPAT_RUNTIME_PROMPT_OVERHEAD_TOKENS)
        .coerceAtLeast(900)
    return (availableHistoryTokens * 1.25f).toInt()
      .coerceIn(MIN_COMPAT_HISTORY_BUDGET_CHARS, MAX_COMPAT_HISTORY_BUDGET_CHARS)
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
'''
history_new = '''    if (isMcp249Tight4kCompatModel(model.name) && contextWindow <= 4096) {
      val availableHistoryTokens =
        (
          contextWindow -
            reservedOutputTokens.coerceAtMost(MCP249_TIGHT_4K_RESERVED_OUTPUT_TOKENS) -
            MCP249_TIGHT_4K_PROMPT_OVERHEAD_TOKENS
        )
          .coerceAtLeast(1600)
      return (availableHistoryTokens * 1.10f).toInt()
        .coerceIn(MCP249_TIGHT_4K_HISTORY_MIN_CHARS, MCP249_TIGHT_4K_HISTORY_MAX_CHARS)
    }
    val availableHistoryTokens =
      (contextWindow - reservedOutputTokens - COMPAT_RUNTIME_PROMPT_OVERHEAD_TOKENS)
        .coerceAtLeast(900)
    return (availableHistoryTokens * 1.25f).toInt()
      .coerceIn(MIN_COMPAT_HISTORY_BUDGET_CHARS, MAX_COMPAT_HISTORY_BUDGET_CHARS)
  }

  private fun isMcp249Tight4kCompatModel(modelName: String): Boolean {
    val normalized = modelName.lowercase()
    return normalized.contains("ministral-3-3b-instruct-2512") ||
      normalized.contains("phi-4-mini-instruct")
  }

  private fun mcp249CompatFamilyRule(modelName: String): String {
    val normalized = modelName.lowercase()
    return when {
      normalized.contains("ministral-3-3b-instruct-2512") ->
        "$MCP249_MINISTRAL_COMPAT_MARKER: Treat this host <tool_call> JSON wrapper as the only tool wire format. " +
          "For a simple web-information request, one successful search should normally be enough. After a successful relevant TOOL_RESULT, answer from it; do not broaden or rephrase the same search just to gather more variants."
      normalized.contains("phi-4-mini-instruct") ->
        "$MCP249_PHI_COMPAT_MARKER: Treat this host <tool_call> JSON wrapper as the only tool wire format. " +
          "After a successful TOOL_RESULT, switch back to ordinary assistant text when the request is answerable. Never emit control JSON merely to say that no additional tool is required, and never emit a tool_call key outside the <tool_call> wrapper."
      else -> ""
    }
  }

  private fun mcp249CompatContinuationRule(modelName: String): String {
    val normalized = modelName.lowercase()
    return when {
      normalized.contains("ministral-3-3b-instruct-2512") ->
        "$MCP249_MINISTRAL_COMPAT_MARKER: A successful relevant search result is sufficient evidence to finalize. Prefer a direct final answer now; another search is allowed only when the visible result explicitly failed or clearly lacks the requested fact."
      normalized.contains("phi-4-mini-instruct") ->
        "$MCP249_PHI_COMPAT_MARKER: This is the post-tool finalization state. If the tool succeeded, output the user's natural-language answer only. Do not output JSON such as a tool_call field saying that no tool is needed."
      else -> ""
    }
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
'''
helper = replace_once(helper, history_old, history_new, "targeted history budget + family helpers")

# Low-variance sampler only for exact target models while a COMPAT state is active. Generic model
# sampler behavior is untouched. Official Ministral tool examples use a low temperature; Phi tool
# calls are also more stable under deterministic sampling.
sampler_old = '''      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")
'''
sampler_new = '''      val configuredTopK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val configuredTopP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val configuredTemperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      val mcp249TargetCompatActive =
        isMcp249Tight4kCompatModel(model.name) && AgentCompatRuntimeCoordinator.snapshot(model.name) != null
      val normalizedMcp249Name = model.name.lowercase()
      val topK = if (mcp249TargetCompatActive) configuredTopK.coerceAtMost(20) else configuredTopK
      val topP = if (mcp249TargetCompatActive) configuredTopP.coerceAtMost(0.9f) else configuredTopP
      val temperature =
        if (mcp249TargetCompatActive) {
          if (normalizedMcp249Name.contains("ministral-3-3b-instruct-2512")) 0.15f else 0.20f
        } else {
          configuredTemperature
        }
      Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")
'''
helper = replace_once(helper, sampler_old, sampler_new, "targeted COMPAT sampler")
helper_path.write_text(helper, encoding="utf-8")

# 3. Coordinator prompt: reinforce post-tool finalization for the two models while keeping the
# persistent-Engine/fresh-Conversation architecture and generic repeated-call guard unchanged.
coord_path = AGENT_DIR / "AgentCompatRuntimeCoordinator.kt"
coord = coord_path.read_text(encoding="utf-8")
coord_tail_old = '''        append(
          "If context_safety_note says tool output was truncated, answer only from the visible history and tell the user that the complete exact tool output is available in the saved audit file."
        )
'''
coord_tail_new = '''        append(
          "If context_safety_note says tool output was truncated, answer only from the visible history and tell the user that the complete exact tool output is available in the saved audit file."
        )
        val mcp249Rule = mcp249CoordinatorContinuationRule(modelName)
        if (mcp249Rule.isNotBlank()) {
          append(' ')
          append(mcp249Rule)
        }
'''
coord = replace_once(coord, coord_tail_old, coord_tail_new, "coordinator family continuation")

coord_end_old = '''  private fun canonicalizeJsonValue(value: Any?): String {
    return when (value) {
      null, JSONObject.NULL -> "null"
      is JSONObject -> canonicalizeJsonObject(value)
      is JSONArray -> canonicalizeJsonArray(value)
      is String -> JSONObject.quote(value)
      is Number, is Boolean -> value.toString()
      else -> JSONObject.quote(value.toString())
    }
  }
}
'''
coord_end_new = '''  private fun canonicalizeJsonValue(value: Any?): String {
    return when (value) {
      null, JSONObject.NULL -> "null"
      is JSONObject -> canonicalizeJsonObject(value)
      is JSONArray -> canonicalizeJsonArray(value)
      is String -> JSONObject.quote(value)
      is Number, is Boolean -> value.toString()
      else -> JSONObject.quote(value.toString())
    }
  }

  private fun mcp249CoordinatorContinuationRule(modelName: String): String {
    val normalized = modelName.lowercase()
    return when {
      normalized.contains("ministral-3-3b-instruct-2512") ->
        "MCP249_MINISTRAL_COMPAT_V1: After a successful relevant tool result, finalize from visible evidence. Do not repeat or reformulate the same web search unless the result explicitly failed or lacks the requested fact."
      normalized.contains("phi-4-mini-instruct") ->
        "MCP249_PHI4MINI_COMPAT_V1: After a successful tool result, final output must be ordinary natural-language assistant text. A JSON object saying no additional tool is required is invalid final output."
      else -> ""
    }
  }
}
'''
coord = replace_once(coord, coord_end_old, coord_end_new, "coordinator helper")
coord_path.write_text(coord, encoding="utf-8")

# 4. Hard postconditions: same MCP248 model files/context, same dual allowlists, target markers only in
# source code, and all MCP247 Agent lifecycle invariants still present.
allowlists = [
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ANDROID_ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
]
if allowlists[0].read_bytes() != allowlists[1].read_bytes():
    fail("allowlists diverged before MCP249 postconditions")
data = json.loads(allowlists[0].read_text(encoding="utf-8"))
models = data.get("models", [])
for name, filename in ((MINISTRAL_NAME, MINISTRAL_FILE), (PHI_NAME, PHI_FILE)):
    hits = [m for m in models if m.get("name") == name]
    if len(hits) != 1:
        fail(f"expected one target model {name}, got {len(hits)}")
    m = hits[0]
    if m.get("modelFile") != filename:
        fail(f"target model file changed for {name}: {m.get('modelFile')}")
    cfg = m.get("defaultConfig", {})
    if int(cfg.get("maxContextLength", -1)) != 4096:
        fail(f"target context changed for {name}: {cfg.get('maxContextLength')}")

final_helper = helper_path.read_text(encoding="utf-8")
final_tooling = tooling_path.read_text(encoding="utf-8")
final_coord = coord_path.read_text(encoding="utf-8")
for marker in (
    "MCP249_MINISTRAL_COMPAT_V1",
    "MCP249_PHI4MINI_COMPAT_V1",
    "MCP249_TIGHT_4K_BUDGET_V1",
):
    if marker not in final_helper + final_tooling + final_coord:
        fail(f"missing MCP249 marker {marker}")
for invariant in (
    'data class LlmModelInstance(val engine: Engine, var conversation: Conversation)',
    'val engine = instance.engine',
    'COMPAT_FRESH_REASON_TOP_LEVEL',
    'COMPAT_FRESH_REASON_TOOL_CONTINUATION',
    'put("enable_thinking", false)',
    'put("thinking_token_budget", 0)',
):
    if invariant not in final_helper:
        fail(f"MCP247 Agent invariant missing after MCP249 patch: {invariant}")
for forbidden in (
    "MCP240_QWEN35_RECURRENT_STATE_RESET",
    "MCP241_QWEN35_AGENT_LOGITS_FIX",
    "MCP242_LOCAL_LITERTLM_AAR",
    "prefillPrefaceOnInit",
    "instance.engineConfig",
):
    if forbidden in final_helper:
        fail(f"failed experiment leaked into MCP249 helper: {forbidden}")

# Generic formulas remain present after the targeted branches, proving previously working models
# continue through the MCP248 budget/protocol path.
for generic in (
    "contextWindow - reservedOutputTokens - COMPAT_TOOL_RESULT_PROMPT_OVERHEAD_TOKENS",
    "contextWindow - reservedOutputTokens - COMPAT_RUNTIME_PROMPT_OVERHEAD_TOKENS",
    "Do not repeat an identical call without new information.",
):
    if generic not in final_helper + final_tooling + final_coord:
        fail(f"generic COMPAT path was not preserved: {generic}")

print(
    "MCP249_TARGETED_MINISTRAL_PHI_COMPAT_PASS "
    "models=2 context=4096 tool_result_chars=2200..2400 history_chars=2200..2400 "
    "generic_models_unchanged=true"
)
