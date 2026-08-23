#!/usr/bin/env python3
"""MCP250 isolated compatibility fixes for the failed MCP249 model set.

Hard scope:
- LocoOperator-4B LiteRTLM and Gemma-4-12B-it (experimental) stay on the existing MCP249/MCP247
  COMPAT parser, prompts, parameter passing, continuation and loop behavior.
- Media/native generation code is untouched.
- Only the exact failed model names receive new parsing, continuation, loop, thinking or engine
  safety behavior.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "Android/src"
AGENT = ANDROID / "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat"
UI = ANDROID / "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat"


def fail(msg: str) -> None:
    print(f"MCP250 patch failure: {msg}", file=sys.stderr)
    raise SystemExit(1)


def once(text: str, old: str, new: str, label: str) -> str:
    c = text.count(old)
    if c != 1:
        fail(f"{label}: expected one anchor, found {c}")
    return text.replace(old, new, 1)


# Exact-name helper. The two protected baselines are deliberately named here only as negative guards.
helper_path = AGENT / "Mcp250TargetAgentCompat.kt"
helper = r'''/* Copyright 2026 Google LLC */
package com.google.ai.edge.gallery.customtasks.agentchat

import org.json.JSONObject

/** MCP250 exact-model compatibility policy. No generic model is eligible for this path. */
internal object Mcp250TargetAgentCompat {
  const val MINISTRAL = "Ministral-3-3B-Instruct-2512 LiteRT"
  const val PHI = "Phi-4-mini-instruct Q8 4096 LiteRT"
  const val FALCON = "Falcon-H1-3B-Instruct INT8 LiteRT"
  const val JAN = "Jan-nano 4B reasoning LiteRT"
  const val FASTCONTEXT = "FastContext-1.0-4B-SFT LiteRT block32"
  const val LAGUNA = "Laguna XS.2 phone k4 fold3 LiteRT experimental"
  const val GEMMA26 = "Gemma-4-26B-A4B-it Box web artifact experimental"

  const val PROTECTED_LOCO = "LocoOperator-4B LiteRTLM"
  const val PROTECTED_GEMMA12 = "Gemma-4-12B-it (experimental)"

  private val agentTargets = setOf(MINISTRAL, PHI, FALCON, JAN, FASTCONTEXT)

  fun isTargetAgentModel(modelName: String): Boolean = modelName in agentTargets
  fun isLaguna(modelName: String): Boolean = modelName == LAGUNA
  fun isGemma26(modelName: String): Boolean = modelName == GEMMA26
  fun allowNativeThinking(modelName: String): Boolean = modelName == JAN
  fun usesTight4kBudget(modelName: String): Boolean = modelName in agentTargets

  fun isProtectedCore(modelName: String): Boolean =
    modelName == PROTECTED_LOCO || modelName == PROTECTED_GEMMA12

  /** Preserve the canonical parser first; recover Ministral's observed payload alias only on Ministral. */
  fun parseTargetToolCall(modelName: String, rawText: String): ParsedCompatToolCall? {
    parseCompatToolCall(rawText)?.let { return it }
    if (modelName != MINISTRAL) return null
    val open = rawText.indexOf("<tool_call>", ignoreCase = true)
    if (open < 0) return null
    val close = rawText.indexOf("</tool_call>", startIndex = open, ignoreCase = true)
    val block = rawText.substring(open + "<tool_call>".length, if (close > open) close else rawText.length).trim()
    val root = runCatching { JSONObject(block) }.getOrNull() ?: return null
    val tool = root.optString("tool").ifBlank { root.optString("name") }.trim()
    val payload = root.optJSONObject("payload") ?: return null
    if (tool.isBlank()) return null
    return ParsedCompatToolCall(toolName = tool, arguments = payload)
  }

  fun topLevelRule(modelName: String): String = when (modelName) {
    MINISTRAL ->
      "MCP250_MINISTRAL_STATE_V1: Use one tool only when needed. Host tool calls use <tool_call> JSON. After a successful relevant web result, answer from that evidence; do not reformulate the same search."
    PHI ->
      "MCP250_PHI_STATE_V1: A tool call is an action, and the following TOOL_HISTORY is its completed response. After a successful response, leave tool-control mode and write ordinary assistant prose."
    FALCON ->
      "MCP250_FALCON_STATE_V1: For a simple current-information request, one successful web search is normally sufficient. Do not issue repeated web searches with alternate wording."
    JAN ->
      "MCP250_JAN_STATE_V1: Internal reasoning is allowed. Use tools deliberately; after enough evidence is available, stop tool use and produce the final answer."
    FASTCONTEXT ->
      "MCP250_FASTCONTEXT_STATE_V1: This host task is answer-oriented, not repository exploration. Use the single most relevant enabled tool and finalize from its result instead of exploring additional tools."
    else -> ""
  }

  fun shortContinuationRule(modelName: String): String = when (modelName) {
    MINISTRAL ->
      "MCP250_MINISTRAL_POST_TOOL_V1. TOOL_HISTORY above contains the completed tool response. If it succeeded and contains relevant evidence, answer the original user request directly now. A further tool call is reserved for an explicit failure or a genuinely missing requested fact."
    PHI ->
      "MCP250_PHI_POST_TOOL_V1. The tool response is already complete in TOOL_HISTORY. Return the natural-language answer to the original user now. Do not emit control JSON, a no-tool message, or another tool call after a successful result."
    FALCON ->
      "MCP250_FALCON_POST_TOOL_V1. Use the successful visible tool evidence to answer now. Do not search the same topic again with a rephrased query."
    JAN ->
      "MCP250_JAN_POST_TOOL_V1. You may reason internally, then answer from the visible tool evidence. Avoid repeating a successful search for the same user request."
    FASTCONTEXT ->
      "MCP250_FASTCONTEXT_POST_TOOL_V1. The evidence-gathering phase is complete. Stop exploration and answer the original user request from TOOL_HISTORY."
    else -> ""
  }

  fun shouldForceFinalization(
    modelName: String,
    completedToolSteps: Int,
    requestedToolName: String,
    previousToolName: String?,
  ): Boolean {
    if (completedToolSteps < 1 || modelName !in agentTargets) return false
    if (modelName == PHI || modelName == FASTCONTEXT) return true
    val requested = requestedToolName.lowercase()
    val previous = previousToolName.orEmpty().lowercase()
    val webLike = requested.contains("search") || requested.contains("run_js")
    val previousWebLike = previous.contains("search") || previous.contains("run_js")
    return webLike && previousWebLike
  }

  fun buildLoopGuardToolResult(originalUserRequest: String, requestedToolName: String): String = """
TOOL_RESULT
original_user_request: $originalUserRequest
tool: $requestedToolName
status: succeeded
payload:
status: succeeded
result:
MCP250_TARGET_LOOP_GUARD: A previous successful tool result for this same user turn is already present in TOOL_HISTORY. No new external action was executed. Use the existing visible evidence and produce the final user-facing answer now.

Answer the original user request directly. Do not issue another tool call for the same evidence-gathering step.
""".trimIndent()
}
'''
if helper_path.exists():
    fail("Mcp250TargetAgentCompat.kt unexpectedly already exists")
helper_path.write_text(helper, encoding="utf-8")

# AgentChatScreen: target parser/loop guard only. All non-target models execute the original parser branch.
screen_path = AGENT / "AgentChatScreen.kt"
screen = screen_path.read_text(encoding="utf-8")
map_anchor = '  val compatToolStepsByModel = remember { mutableStateMapOf<String, Int>() }\n'
map_insert = map_anchor + '''  val mcp250LastCompatToolByModel = remember { mutableStateMapOf<String, String>() }\n  val mcp250LoopGuardTripsByModel = remember { mutableStateMapOf<String, Int>() }\n'''
screen = once(screen, map_anchor, map_insert, "screen target state maps")

error_old = '''    compatToolStepsByModel.remove(model.name)\n    viewModel.handleError(\n'''
error_new = '''    compatToolStepsByModel.remove(model.name)\n    mcp250LastCompatToolByModel.remove(model.name)\n    mcp250LoopGuardTripsByModel.remove(model.name)\n    viewModel.handleError(\n'''
screen = once(screen, error_old, error_new, "screen error cleanup")

parse_old = '      val parsedToolCall = parseCompatToolCall(lastAgentText.content)\n'
parse_new = '''      val parsedToolCall =\n        if (Mcp250TargetAgentCompat.isTargetAgentModel(model.name)) {\n          Mcp250TargetAgentCompat.parseTargetToolCall(model.name, lastAgentText.content)\n        } else {\n          // Protected LocoOperator/Gemma-4-12B and every non-target model stay byte-for-behavior on\n          // the already-verified canonical parser path.\n          parseCompatToolCall(lastAgentText.content)\n        }\n'''
screen = once(screen, parse_old, parse_new, "screen target parser")

steps_old = '''        val currentSteps = compatToolStepsByModel[model.name] ?: 0\n        if (currentSteps >= MAX_COMPAT_TOOL_STEPS) {\n'''
steps_new = '''        val currentSteps = compatToolStepsByModel[model.name] ?: 0\n        val mcp250ForceFinalization =\n          Mcp250TargetAgentCompat.shouldForceFinalization(\n            modelName = model.name,\n            completedToolSteps = currentSteps,\n            requestedToolName = parsedToolCall.toolName,\n            previousToolName = mcp250LastCompatToolByModel[model.name],\n          )\n        if (mcp250ForceFinalization) {\n          val guardTrips = mcp250LoopGuardTripsByModel[model.name] ?: 0\n          viewModel.removeLastMessage(model = model)\n          if (guardTrips >= 1) {\n            viewModel.addMessage(\n              model = model,\n              message = ChatMessageInfo(content = "MCP250 专项循环保护已停止再次工具调用；该模型未能在强制收敛后生成最终回复。"),\n            )\n            compatToolStepsByModel.remove(model.name)\n            mcp250LastCompatToolByModel.remove(model.name)\n            mcp250LoopGuardTripsByModel.remove(model.name)\n            updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)\n            return@handleGenerationDone\n          }\n          mcp250LoopGuardTripsByModel[model.name] = guardTrips + 1\n          viewModel.addMessage(\n            model = model,\n            message = ChatMessageInfo(content = "MCP250 专项循环保护：沿用上一轮成功工具结果并进入最终回答阶段。"),\n          )\n          continueCompatConversation?.invoke(\n            model,\n            Mcp250TargetAgentCompat.buildLoopGuardToolResult(\n              originalUserRequest = originalUserRequest,\n              requestedToolName = parsedToolCall.toolName,\n            ),\n          )\n          return@handleGenerationDone\n        }\n        if (currentSteps >= MAX_COMPAT_TOOL_STEPS) {\n'''
screen = once(screen, steps_old, steps_new, "screen target loop guard")

increment_old = '''        compatToolStepsByModel[model.name] = currentSteps + 1\n        viewModel.removeLastMessage(model = model)\n'''
increment_new = '''        compatToolStepsByModel[model.name] = currentSteps + 1\n        if (Mcp250TargetAgentCompat.isTargetAgentModel(model.name)) {\n          mcp250LastCompatToolByModel[model.name] = parsedToolCall.toolName\n        }\n        viewModel.removeLastMessage(model = model)\n'''
screen = once(screen, increment_old, increment_new, "screen remember target tool")

final_cleanup_old = '''    compatToolStepsByModel.remove(model.name)\n    updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)\n  }\n  continueCompatConversation = { model, input ->\n'''
final_cleanup_new = '''    compatToolStepsByModel.remove(model.name)\n    mcp250LastCompatToolByModel.remove(model.name)\n    mcp250LoopGuardTripsByModel.remove(model.name)\n    updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)\n  }\n  continueCompatConversation = { model, input ->\n'''
screen = once(screen, final_cleanup_old, final_cleanup_new, "screen final cleanup")

continue_old = '''      allowThinking = false,\n      extraContextOverride = mapOf("enable_thinking" to "false"),\n'''
continue_new = '''      allowThinking = Mcp250TargetAgentCompat.allowNativeThinking(model.name),\n      extraContextOverride =\n        if (Mcp250TargetAgentCompat.allowNativeThinking(model.name)) {\n          emptyMap()\n        } else {\n          mapOf("enable_thinking" to "false")\n        },\n'''
screen = once(screen, continue_old, continue_new, "screen Jan thinking continuation")

before_send_old = '    onBeforeSendMessage = { model, _ -> compatToolStepsByModel.remove(model.name) },\n'
before_send_new = '''    onBeforeSendMessage = { model, _ ->\n      compatToolStepsByModel.remove(model.name)\n      mcp250LastCompatToolByModel.remove(model.name)\n      mcp250LoopGuardTripsByModel.remove(model.name)\n    },\n'''
screen = once(screen, before_send_old, before_send_new, "screen turn cleanup")
screen_path.write_text(screen, encoding="utf-8")

# AgentTooling: expand the MCP249 4K budget branch only to the exact MCP250 small-agent targets.
tooling_path = AGENT / "AgentTooling.kt"
tooling = tooling_path.read_text(encoding="utf-8")
budget_old = '''  val normalizedName = model.name.lowercase()\n  val mcp249Tight4kCompatModel =\n    normalizedName.contains("ministral-3-3b-instruct-2512") ||\n      normalizedName.contains("phi-4-mini-instruct")\n  if (mcp249Tight4kCompatModel && contextWindow <= 4096) {\n'''
budget_new = '''  val mcp250Tight4kCompatModel = Mcp250TargetAgentCompat.usesTight4kBudget(model.name)\n  if (mcp250Tight4kCompatModel && contextWindow <= 4096) {\n'''
tooling = once(tooling, budget_old, budget_new, "target 4K result budget")
tooling_path.write_text(tooling, encoding="utf-8")

# LlmChatModelHelper: exact-model engine safety, Jan native reasoning, targeted short post-tool state.
llm_path = UI / "LlmChatModelHelper.kt"
llm = llm_path.read_text(encoding="utf-8")
import_anchor = 'import com.google.ai.edge.gallery.customtasks.agentchat.AgentPerformanceCoordinator\n'
llm = once(llm, import_anchor, import_anchor + 'import com.google.ai.edge.gallery.customtasks.agentchat.Mcp250TargetAgentCompat\n', "helper import")

engine_old = '    val engineMaxNumTokens = configuredContextWindow.takeIf { it > 0 } ?: maxOutputTokens\n'
engine_new = '''    val engineMaxNumTokens =\n      if (Mcp250TargetAgentCompat.isGemma26(model.name)) {\n        // The 15.8 GB 26B artifact native-crashed at 8192 on the target phone. Keep the artifact\n        // untouched and lower only this Engine KV allocation for survivability.\n        (configuredContextWindow.takeIf { it > 0 } ?: maxOutputTokens).coerceAtMost(2048)\n      } else {\n        configuredContextWindow.takeIf { it > 0 } ?: maxOutputTokens\n      }\n'''
llm = once(llm, engine_old, engine_new, "Gemma26 engine context safety")

init_old = '''      val engine = Engine(engineConfig)\n      engine.initialize()\n      val engineInitMs = elapsedMsSince(engineInitStartNanos)\n'''
init_new = '''      val engine =\n        if (Mcp250TargetAgentCompat.isLaguna(model.name)) {\n          val primary = Engine(engineConfig)\n          try {\n            primary.initialize()\n            primary\n          } catch (primaryError: Exception) {\n            runCatching { primary.close() }\n            Log.w(TAG, "MCP250 Laguna GPU initialization failed; retrying exact model on CPU.", primaryError)\n            val fallbackConfig =\n              EngineConfig(\n                modelPath = modelPath,\n                backend = Backend.CPU(),\n                visionBackend = if (shouldEnableImage) Backend.CPU() else null,\n                audioBackend = if (shouldEnableAudio) Backend.CPU() else null,\n                maxNumTokens = engineMaxNumTokens,\n                cacheDir =\n                  if (modelPath.startsWith("/data/local/tmp"))\n                    context.getExternalFilesDir(null)?.absolutePath\n                  else null,\n              )\n            Engine(fallbackConfig).also { it.initialize() }\n          }\n        } else {\n          // Every protected/non-target model retains the original Engine construction path.\n          Engine(engineConfig).also { it.initialize() }\n        }\n      val engineInitMs = elapsedMsSince(engineInitStartNanos)\n'''
llm = once(llm, init_old, init_new, "Laguna exact CPU fallback")

extra_old = '''    val effectiveExtraContext =\n      mutableMapOf<String, Any>().apply {\n        extraContext?.forEach { (key, value) -> put(key, value) }\n        if (isCompatPass) {\n          // MCP209: COMPAT is a latency-first mode. Hard-disable Gemma 4 thinking at both the Jinja\n          // template and native decoding layers for every COMPAT pass, including the first pass.\n          put("enable_thinking", false)\n          put("preserve_thinking", false)\n          put("thinking_token_budget", 0)\n        }\n      }\n'''
extra_new = '''    val mcp250ForceCompatThinkingOff =\n      isCompatPass && !Mcp250TargetAgentCompat.allowNativeThinking(model.name)\n    val effectiveExtraContext =\n      mutableMapOf<String, Any>().apply {\n        extraContext?.forEach { (key, value) -> put(key, value) }\n        if (mcp250ForceCompatThinkingOff) {\n          // Exact Jan-nano is trained to require a reasoning phase. All protected/non-target COMPAT\n          // models preserve the existing hard-off behavior byte-for-behavior.\n          put("enable_thinking", false)\n          put("preserve_thinking", false)\n          put("thinking_token_budget", 0)\n        }\n      }\n'''
llm = once(llm, extra_old, extra_new, "Jan reasoning exception")

thinking_config_old = '''        if (isCompatPass) {\n          ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)\n        } else {\n          null\n        },\n'''
thinking_config_new = '''        if (mcp250ForceCompatThinkingOff) {\n          ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)\n        } else {\n          null\n        },\n'''
llm = once(llm, thinking_config_old, thinking_config_new, "Jan native thinking config")

family_old = '''    val familyRule = mcp249CompatFamilyRule(model.name)\n    return buildString {\n'''
family_new = '''    val familyRule = mcp249CompatFamilyRule(model.name)\n    val mcp250FamilyRule = Mcp250TargetAgentCompat.topLevelRule(model.name)\n    return buildString {\n'''
llm = once(llm, family_old, family_new, "target top-level family rule variable")

family_append_old = '''      if (familyRule.isNotBlank()) {\n        append(familyRule)\n        append('\\n')\n      }\n      append("\\nAvailable compatibility tools:\\n")\n'''
family_append_new = '''      if (familyRule.isNotBlank()) {\n        append(familyRule)\n        append('\\n')\n      }\n      if (mcp250FamilyRule.isNotBlank()) {\n        append(mcp250FamilyRule)\n        append('\\n')\n      }\n      append("\\nAvailable compatibility tools:\\n")\n'''
llm = once(llm, family_append_old, family_append_new, "target top-level family rule")

short_old = '''    val prefix = input.substring(0, markerIndex).trimEnd()\n    return buildString {\n'''
short_new = '''    val prefix = input.substring(0, markerIndex).trimEnd()\n    val mcp250ShortRule = Mcp250TargetAgentCompat.shortContinuationRule(model.name)\n    if (mcp250ShortRule.isNotBlank()) {\n      return buildString {\n        append(prefix)\n        append("\\n\\nNEXT_ACTION\\n")\n        append(mcp250ShortRule)\n      }\n    }\n    return buildString {\n'''
llm = once(llm, short_old, short_new, "target short continuation")

history_old = '    if (isMcp249Tight4kCompatModel(model.name) && contextWindow <= 4096) {\n'
history_new = '    if (Mcp250TargetAgentCompat.usesTight4kBudget(model.name) && contextWindow <= 4096) {\n'
llm = once(llm, history_old, history_new, "target 4K history budget")
llm_path.write_text(llm, encoding="utf-8")

# Hard postconditions. New behavior is exact-name only; protected baselines cannot match any target.
all_src = helper_path.read_text(encoding="utf-8")
for marker in (
    "MCP250_MINISTRAL_STATE_V1",
    "MCP250_PHI_STATE_V1",
    "MCP250_FALCON_STATE_V1",
    "MCP250_JAN_STATE_V1",
    "MCP250_FASTCONTEXT_STATE_V1",
    "MCP250_TARGET_LOOP_GUARD",
):
    if marker not in all_src:
        fail(f"missing target marker {marker}")
if 'PROTECTED_LOCO = "LocoOperator-4B LiteRTLM"' not in all_src:
    fail("missing LocoOperator protected guard")
if 'PROTECTED_GEMMA12 = "Gemma-4-12B-it (experimental)"' not in all_src:
    fail("missing Gemma12 protected guard")
if "modelName in agentTargets" not in all_src:
    fail("target classification missing")

# Media/native implementation files are intentionally never opened or written by this patch.
for forbidden in ("AgentMediaToolboxSupport.kt", "stable-diffusion.cpp", "GoldenBox049RuntimeEngine"):
    if forbidden in __file__:
        fail("unexpected media mutation path")

print("MCP250_TARGET_AGENT_PATCH_PASS")
