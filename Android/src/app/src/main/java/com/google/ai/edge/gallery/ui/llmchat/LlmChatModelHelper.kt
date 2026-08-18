/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.gallery.common.cleanUpMediapipeTaskErrorMessage
import com.google.ai.edge.gallery.customtasks.agentchat.AgentCompatRuntimeCoordinator
import com.google.ai.edge.gallery.customtasks.agentchat.AgentPerformanceCoordinator
import com.google.ai.edge.gallery.customtasks.agentchat.COMPAT_FRESH_REASON_TOOL_CONTINUATION
import com.google.ai.edge.gallery.customtasks.agentchat.COMPAT_FRESH_REASON_TOP_LEVEL
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_VISION_ACCELERATOR
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolProvider
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AGLlmChatModelHelper"
private const val COMPAT_RUNTIME_PROMPT_OVERHEAD_TOKENS = 1600
private const val DEFAULT_COMPAT_HISTORY_BUDGET_CHARS = 3600
private const val MIN_COMPAT_HISTORY_BUDGET_CHARS = 1600
private const val MAX_COMPAT_HISTORY_BUDGET_CHARS = 8000
private const val COMPAT_INSTRUCTIONS_MARKER = "COMPAT_AGENT_INSTRUCTIONS"
private const val COMPAT_USER_REQUEST_SEPARATOR = "\n\nUSER_REQUEST\n"
private const val COMPAT_AVAILABLE_TOOLS_MARKER = "Available compatibility tools:"
private const val COMPAT_ENABLED_SKILLS_MARKER = "Enabled skills for this session:"
private const val COMPAT_NEXT_ACTION_MARKER = "\n\nNEXT_ACTION\n"

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class)
  override fun initialize(
    context: Context,
    model: Model,
    taskId: String,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    val configuredContextWindow = model.getConfiguredContextWindow()
    val maxOutputTokens =
      model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN).let {
        if (configuredContextWindow > 0) it.coerceAtMost(configuredContextWindow) else it
      }
    val engineMaxNumTokens = configuredContextWindow.takeIf { it > 0 } ?: maxOutputTokens
    val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
    val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
    val temperature =
      model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
    val accelerator =
      model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
    val visionAccelerator =
      model.getStringConfigValue(
        key = ConfigKeys.VISION_ACCELERATOR,
        defaultValue = DEFAULT_VISION_ACCELERATOR.label,
      )
    val visionBackend =
      when (visionAccelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.GPU()
      }
    val shouldEnableImage = supportImage
    val shouldEnableAudio = supportAudio
    val preferredBackend =
      when (accelerator) {
        Accelerator.CPU.label -> Backend.CPU()
        Accelerator.GPU.label -> Backend.GPU()
        Accelerator.NPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        Accelerator.TPU.label ->
          Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        else -> Backend.CPU()
      }
    Log.d(TAG, "Preferred backend: $preferredBackend")

    val modelPath = model.getPath(context = context)
    Log.d(
      TAG,
      "Token config for '${model.name}': engineMaxNumTokens=$engineMaxNumTokens, maxOutputTokens=$maxOutputTokens, configuredContextWindow=$configuredContextWindow",
    )
    val engineConfig =
      EngineConfig(
        modelPath = modelPath,
        backend = preferredBackend,
        visionBackend = if (shouldEnableImage) visionBackend else null,
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null,
        maxNumTokens = engineMaxNumTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    var supportsSpeculativeDecoding = false
    try {
      com.google.ai.edge.litertlm.Capabilities(modelPath).use {
        supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }

    try {
      var speculativeDecoding = false
      if (
        supportsSpeculativeDecoding &&
          model.capabilityToTaskTypes[ModelCapability.SPECULATIVE_DECODING]?.contains(taskId) == true
      ) {
        speculativeDecoding =
          model.getBooleanConfigValue(
            key = ConfigKeys.ENABLE_SPECULATIVE_DECODING,
            defaultValue = false,
          )
      }
      ExperimentalFlags.enableSpeculativeDecoding = speculativeDecoding
      // MCP211 is a measurement-only Agent build. LiteRT-LM 0.15.0 reads this flag exactly once
      // when the Engine is created, so reset it immediately after initialization to avoid changing
      // unrelated engines created later in this process.
      ExperimentalFlags.enableBenchmark = taskId == BuiltInTaskId.LLM_AGENT_CHAT
      Log.d(
        TAG,
        "Speculative decoding enabled: $speculativeDecoding; native benchmark enabled for engine: ${ExperimentalFlags.enableBenchmark}",
      )

      val engineInitStartNanos = SystemClock.elapsedRealtimeNanos()
      val engine = Engine(engineConfig)
      engine.initialize()
      val engineInitMs = elapsedMsSince(engineInitStartNanos)
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableBenchmark = false

      ExperimentalFlags.enableConversationConstrainedDecoding =
        enableConversationConstrainedDecoding
      val conversationInitStartNanos = SystemClock.elapsedRealtimeNanos()
      val conversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (preferredBackend is Backend.NPU) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
          )
        )
      val conversationInitMs = elapsedMsSince(conversationInitStartNanos)
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      model.instance = LlmModelInstance(engine = engine, conversation = conversation)

      LlmChatPerformanceRegistry.recordInitialization(
        modelName = model.name,
        engineInitMs = engineInitMs,
        conversationInitMs = conversationInitMs,
        configuredContextWindow = configuredContextWindow,
        engineMaxNumTokens = engineMaxNumTokens,
        maxOutputTokens = maxOutputTokens,
        accelerator = accelerator,
        speculativeDecodingEnabled = speculativeDecoding,
      )
      Log.d(
        TAG,
        "MCP211 init metrics for '${model.name}': engine=${"%.2f".format(engineInitMs)}ms conversation=${"%.2f".format(conversationInitMs)}ms",
      )
    } catch (e: Exception) {
      ExperimentalFlags.enableBenchmark = false
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  @OptIn(ExperimentalApi::class)
  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    initialMessages: List<Message>,
  ) {
    val resetStartNanos = SystemClock.elapsedRealtimeNanos()
    try {
      Log.d(TAG, "Resetting conversation for model '${model.name}'")

      val instance = model.instance as LlmModelInstance? ?: return
      instance.conversation.close()

      val engine = instance.engine
      val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
      val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
      val temperature =
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      Log.d(TAG, "Enable image: $supportImage, enable audio: $supportAudio")

      val accelerator =
        model.getStringConfigValue(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = Accelerator.GPU.label,
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = enableConversationConstrainedDecoding
      val newConversation =
        engine.createConversation(
          ConversationConfig(
            samplerConfig =
              if (accelerator == Accelerator.NPU.label || accelerator == Accelerator.TPU.label) {
                null
              } else {
                SamplerConfig(
                  topK = topK,
                  topP = topP.toDouble(),
                  temperature = temperature.toDouble(),
                )
              },
            systemInstruction = systemInstruction,
            tools = tools,
            initialMessages = initialMessages,
          )
        )
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      instance.conversation = newConversation

      val resetMs = elapsedMsSince(resetStartNanos)
      LlmChatPerformanceRegistry.recordConversationReset(modelName = model.name, elapsedMs = resetMs)
      Log.d(TAG, "Resetting done in ${"%.2f".format(resetMs)}ms")
    } catch (e: Exception) {
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      Log.d(TAG, "Failed to reset conversation", e)
      throw e
    }
  }

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    if (model.instance == null) {
      AgentCompatRuntimeCoordinator.clear(model.name)
      return
    }

    val instance = model.instance as LlmModelInstance
    try {
      instance.conversation.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the conversation: ${e.message}")
    }
    try {
      instance.engine.close()
    } catch (e: Exception) {
      Log.e(TAG, "Failed to close the engine: ${e.message}")
    }

    val onCleanUp = cleanUpListeners.remove(model.name)
    if (onCleanUp != null) onCleanUp()
    AgentCompatRuntimeCoordinator.clear(model.name)
    model.instance = null
    LlmChatPerformanceRegistry.clear(model.name)

    onDone()
    Log.d(TAG, "Clean up done.")
  }

  override fun stopResponse(model: Model) {
    val instance = model.instance as? LlmModelInstance ?: return
    instance.conversation.cancelProcess()
  }

  @OptIn(ExperimentalApi::class)
  override fun runInference(
    model: Model,
    input: String,
    message: Message?,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? LlmModelInstance
    if (instance == null) {
      AgentPerformanceCoordinator.finishWithError(model.name, "LlmModelInstance is not initialized.".length)
      onError("LlmModelInstance is not initialized.")
      return
    }

    if (!cleanUpListeners.containsKey(model.name)) {
      cleanUpListeners[model.name] = cleanUpListener
    }

    val effectiveInput =
      try {
        prepareCompatAgentInput(model = model, input = input)
      } catch (e: Exception) {
        AgentCompatRuntimeCoordinator.onGenerationFailed(model.name)
        val errorMessage = "Failed to prepare COMPAT input: ${e.message ?: "Unknown error"}"
        AgentPerformanceCoordinator.finishWithError(model.name, errorMessage.length)
        onError(errorMessage)
        return
      }

    val compatRuntime = AgentCompatRuntimeCoordinator.snapshot(model.name)
    val compatReason = compatRuntime?.lastFreshConversationReason
    val isCompatPass =
      compatReason == COMPAT_FRESH_REASON_TOP_LEVEL ||
        compatReason == COMPAT_FRESH_REASON_TOOL_CONTINUATION
    val compatPassKind =
      when (compatReason) {
        COMPAT_FRESH_REASON_TOP_LEVEL -> "top_level"
        COMPAT_FRESH_REASON_TOOL_CONTINUATION -> "continuation"
        else -> "none"
      }

    val effectiveExtraContext =
      mutableMapOf<String, Any>().apply {
        extraContext?.forEach { (key, value) -> put(key, value) }
        if (isCompatPass) {
          put("enable_thinking", false)
          put("preserve_thinking", false)
          put("thinking_token_budget", 0)
        }
      }

    val conversation = instance.conversation
    val messageToSend =
      message
        ?: run {
          val contents = mutableListOf<Content>()
          for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
          for (audioClip in audioClips) contents.add(Content.AudioBytes(audioClip))
          if (effectiveInput.trim().isNotEmpty()) contents.add(Content.Text(effectiveInput))
          Message.user(Contents.of(contents))
        }

    val thinkingOverrideLabel =
      if (isCompatPass) {
        val renderAudit =
          runCatching {
              val rendered =
                conversation.renderMessageIntoString(
                  message = messageToSend,
                  extraContext = effectiveExtraContext,
                )
              val lastThoughtOpen = rendered.lastIndexOf("<|channel>thought\n")
              val lastChannelClose = rendered.lastIndexOf("<channel|>")
              val openThoughtAtTail = lastThoughtOpen >= 0 && lastThoughtOpen > lastChannelClose
              val closedThoughtCue = rendered.contains("<|channel>thought\n<channel|>")
              "render_chars=${rendered.length};render_open_thought=$openThoughtAtTail;render_closed_thought_cue=$closedThoughtCue"
            }
            .getOrElse { "render_audit_error=${it.javaClass.simpleName}" }
        "disabled_compat_global_template_native_budget0_$compatPassKind;$renderAudit"
      } else {
        "runtime_default"
      }

    AgentPerformanceCoordinator.onInferenceSubmitted(
      modelName = model.name,
      inputChars = effectiveInput.length,
      thinkingOverride = thinkingOverrideLabel,
    )
    var generatedChars = 0
    val generatedText = StringBuilder()

    conversation.sendMessageAsync(
      message = messageToSend,
      callback =
        object : MessageCallback {
          override fun onMessage(message: Message) {
            val text = message.toString()
            val thought = message.channels["thought"].orEmpty()
            generatedChars += text.length
            generatedText.append(text)
            AgentPerformanceCoordinator.onStreamChunk(
              modelName = model.name,
              visibleChars = text.length,
              thoughtChars = thought.length,
            )
            resultListener(text, false, thought.takeIf { it.isNotBlank() })
          }

          override fun onDone() {
            // Mark app-observed generation done before reading diagnostics so the JNI benchmark read
            // cannot inflate decode_after_first_token_ms. Its small orchestration cost is measured
            // separately and will naturally appear in the post-generation/tool gap.
            AgentPerformanceCoordinator.onInferenceDone(model.name, generatedChars)

            if (isCompatPass) {
              val benchmarkReadStartedNanos = SystemClock.elapsedRealtimeNanos()
              val benchmarkResult = runCatching { conversation.getBenchmarkInfo() }
              val tokenCountResult = runCatching { conversation.getTokenCount() }
              val benchmarkReadMs = elapsedMsSince(benchmarkReadStartedNanos)
              val benchmark = benchmarkResult.getOrNull()
              val benchmarkError =
                benchmarkResult.exceptionOrNull()?.javaClass?.simpleName
                  ?: tokenCountResult.exceptionOrNull()?.javaClass?.simpleName
              AgentPerformanceCoordinator.onLiteRtNativeBenchmark(
                modelName = model.name,
                initTimeMs = benchmark?.initTimeInSecond?.times(1000.0),
                nativeTtftMs = benchmark?.timeToFirstTokenInSecond?.times(1000.0),
                prefillTokenCount = benchmark?.lastPrefillTokenCount,
                decodeTokenCount = benchmark?.lastDecodeTokenCount,
                prefillTokensPerSecond = benchmark?.lastPrefillTokensPerSecond,
                decodeTokensPerSecond = benchmark?.lastDecodeTokensPerSecond,
                kvTokenCount = tokenCountResult.getOrNull(),
                benchmarkReadMs = benchmarkReadMs,
                errorClass = benchmarkError,
              )
            }

            val decision =
              AgentCompatRuntimeCoordinator.onGenerationCompleted(
                modelName = model.name,
                generatedText = generatedText.toString(),
              )
            if (decision.blockedRepeatedToolCall) {
              val errorMessage =
                "兼容工具调用已停止：检测到连续三次完全相同的工具调用，已在再次执行前拦截，避免重复操作。"
              Log.w(TAG, "MCP211 blocked repeated COMPAT tool call for '${model.name}'.")
              onError(errorMessage)
              return
            }
            resultListener("", true, null)
          }

          override fun onError(throwable: Throwable) {
            AgentCompatRuntimeCoordinator.onGenerationFailed(model.name)
            if (throwable is CancellationException) {
              Log.i(TAG, "The inference is cancelled.")
              resultListener("", true, null)
            } else {
              Log.e(TAG, "onError", throwable)
              val errorMessage = "Error: ${throwable.message}"
              AgentPerformanceCoordinator.finishWithError(model.name, errorMessage.length)
              onError(errorMessage)
            }
          }
        },
      extraContext = effectiveExtraContext,
      thinkingConfig =
        if (isCompatPass) {
          ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
        } else {
          null
        },
    )
  }

  private fun prepareCompatAgentInput(model: Model, input: String): String {
    val prepareStartedNanos = SystemClock.elapsedRealtimeNanos()
    val compactedRawInput = compactCompatEnvelope(input)
    val prepared =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = model.name,
        rawInput = compactedRawInput,
        historyBudgetChars = resolveCompatRuntimeHistoryBudget(model),
      )
    val finalInput = compactPreparedCompatInput(prepared.input)
    if (!prepared.requiresFreshConversation) {
      return finalInput
    }

    val resetStartedNanos = SystemClock.elapsedRealtimeNanos()
    resetConversation(
      model = model,
      supportImage = false,
      supportAudio = false,
      systemInstruction = null,
      tools = listOf(),
      enableConversationConstrainedDecoding = false,
      initialMessages = listOf(),
    )
    val resetMs = elapsedMsSince(resetStartedNanos)
    val prepareMs = elapsedMsSince(prepareStartedNanos)
    AgentCompatRuntimeCoordinator.recordContinuationPreparation(
      modelName = model.name,
      prepareMs = prepareMs,
      resetMs = resetMs,
      rawInputChars = compactedRawInput.length,
      effectiveInputChars = finalInput.length,
      historyStepCount = prepared.historyStepCount,
      historyChars = prepared.historyChars,
    )
    Log.d(
      TAG,
      "MCP211 COMPAT fresh conversation for '${model.name}': reason=${prepared.freshConversationReason} reset=${"%.2f".format(resetMs)}ms prepare=${"%.2f".format(prepareMs)}ms originalRawChars=${input.length} compactRawChars=${compactedRawInput.length} finalChars=${finalInput.length} historySteps=${prepared.historyStepCount} historyChars=${prepared.historyChars}",
    )
    return finalInput
  }

  private fun compactCompatEnvelope(input: String): String {
    val trimmed = input.trimStart()
    if (!trimmed.startsWith(COMPAT_INSTRUCTIONS_MARKER)) return input
    val separatorIndex = input.indexOf(COMPAT_USER_REQUEST_SEPARATOR)
    if (separatorIndex < 0) return input
    val markerIndex = input.indexOf(COMPAT_INSTRUCTIONS_MARKER)
    if (markerIndex < 0 || markerIndex >= separatorIndex) return input

    val payloadStart = markerIndex + COMPAT_INSTRUCTIONS_MARKER.length
    val payload = input.substring(payloadStart, separatorIndex).trim()
    val compactPayload = compactCompatInstructionPayload(payload)
    if (compactPayload == payload) return input
    val userRequest = input.substring(separatorIndex + COMPAT_USER_REQUEST_SEPARATOR.length)
    return buildString {
      append(COMPAT_INSTRUCTIONS_MARKER)
      append('\n')
      append(compactPayload)
      append(COMPAT_USER_REQUEST_SEPARATOR)
      append(userRequest)
    }
  }

  private fun compactCompatInstructionPayload(payload: String): String {
    val toolsMarkerIndex = payload.indexOf(COMPAT_AVAILABLE_TOOLS_MARKER)
    val skillsMarkerIndex = payload.indexOf(COMPAT_ENABLED_SKILLS_MARKER)
    if (toolsMarkerIndex < 0 || skillsMarkerIndex <= toolsMarkerIndex) return payload

    val toolsStart = toolsMarkerIndex + COMPAT_AVAILABLE_TOOLS_MARKER.length
    val toolsSection = payload.substring(toolsStart, skillsMarkerIndex).trim()
    val skillsStart = skillsMarkerIndex + COMPAT_ENABLED_SKILLS_MARKER.length
    val skillsSection = payload.substring(skillsStart).trim()
    val compactTools = compactCompatToolLines(toolsSection)
    val compactSkills =
      skillsSection.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { line ->
          if (line.startsWith("- ") && line.contains(':')) {
            line.substringBefore(':').trim()
          } else {
            line
          }
        }
        .joinToString("\n")
        .ifBlank { "- none" }

    return """
Qwen-compatible tool mode. Reply in the user's language. Thinking is off.
If a tool is needed, output ONLY <tool_call>{"tool":"NAME","arguments":{...}}</tool_call>. One tool per turn; never mix prose with a tool call. Use enabled tools only. After TOOL_RESULT, either make one next tool call or give the final answer. Do not repeat an identical call without new information. For web search use search_web. Stop after the final answer.

Available compatibility tools:
$compactTools

Enabled skills for this session:
$compactSkills
"""
      .trimIndent()
  }

  private fun compactCompatToolLines(toolsSection: String): String {
    return toolsSection.lineSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .map { line ->
        when {
          line.startsWith("- Media Toolbox routing rule:") -> line
          line.contains("MUST") -> line
          line.startsWith("- ") && line.contains(" arguments: ") ->
            line.substringBefore(" . ").replace(" arguments: ", " ")
          else -> line
        }
      }
      .joinToString("\n")
      .ifBlank { "- No compatibility tools enabled." }
  }

  private fun compactPreparedCompatInput(input: String): String {
    val markerIndex = input.indexOf(COMPAT_NEXT_ACTION_MARKER)
    if (markerIndex < 0) return input
    val prefix = input.substring(0, markerIndex).trimEnd()
    return buildString {
      append(prefix)
      append("\n\nNEXT_ACTION\n")
      append(
        "Continue the current task. If complete, answer directly in the user's language and stop. "
      )
      append(
        "If another enabled tool is required, output exactly one <tool_call> JSON block and no prose. "
      )
      append("Do not repeat an identical call without new information. ")
      append("Preserve every XLSX 行事实 metric, unit, year, and value exactly. ")
      append(
        "If context_safety_note says truncated, use visible history only and mention the saved audit."
      )
    }
  }

  private fun resolveCompatRuntimeHistoryBudget(model: Model): Int {
    val contextWindow = runCatching { model.getConfiguredContextWindow() }.getOrDefault(0)
    if (contextWindow <= 0) return DEFAULT_COMPAT_HISTORY_BUDGET_CHARS
    val reservedOutputTokens =
      runCatching {
          model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
        }
        .getOrDefault(DEFAULT_MAX_TOKEN)
        .coerceAtLeast(512)
    val availableHistoryTokens =
      (contextWindow - reservedOutputTokens - COMPAT_RUNTIME_PROMPT_OVERHEAD_TOKENS)
        .coerceAtLeast(900)
    return (availableHistoryTokens * 1.25f).toInt()
      .coerceIn(MIN_COMPAT_HISTORY_BUDGET_CHARS, MAX_COMPAT_HISTORY_BUDGET_CHARS)
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
