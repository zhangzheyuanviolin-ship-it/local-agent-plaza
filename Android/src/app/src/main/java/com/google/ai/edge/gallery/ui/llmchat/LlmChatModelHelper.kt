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
import com.google.ai.edge.gallery.data.Accelerator
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

data class LlmModelInstance(val engine: Engine, var conversation: Conversation)

object LlmChatModelHelper : LlmModelHelper {
  // Indexed by model name.
  private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
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
    // Prepare options.
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
        visionBackend = if (shouldEnableImage) visionBackend else null, // must be GPU for Gemma 3n
        audioBackend = if (shouldEnableAudio) Backend.CPU() else null, // must be CPU for Gemma 3n
        maxNumTokens = engineMaxNumTokens,
        cacheDir =
          if (modelPath.startsWith("/data/local/tmp"))
            context.getExternalFilesDir(null)?.absolutePath
          else null,
      )

    // Check if the model file supports speculative decoding.
    var supportsSpeculativeDecoding = false
    try {
      com.google.ai.edge.litertlm.Capabilities(modelPath).use {
        supportsSpeculativeDecoding = it.hasSpeculativeDecodingSupport()
      }
    } catch (e: Exception) {
      // Ignore exceptions and assume not supported.
    }
    // Create an instance of LiteRT LM engine and conversation.
    try {
      var speculativeDecoding = false
      // Check if the model supports speculative decoding for the given task type and if the
      // speculative decoding is enabled in the settings.
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
      Log.d(TAG, "Speculative decoding enabled: $speculativeDecoding")

      val engineInitStartNanos = SystemClock.elapsedRealtimeNanos()
      val engine = Engine(engineConfig)
      engine.initialize()
      val engineInitMs = elapsedMsSince(engineInitStartNanos)
      ExperimentalFlags.enableSpeculativeDecoding = false

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
        "MCP208 init metrics for '${model.name}': engine=${"%.2f".format(engineInitMs)}ms conversation=${"%.2f".format(conversationInitMs)}ms",
      )
    } catch (e: Exception) {
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
    onDone("")
  }

  @OptIn(ExperimentalApi::class) // opt-in experimental flags
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
      val shouldEnableImage = supportImage
      val shouldEnableAudio = supportAudio
      Log.d(TAG, "Enable image: $shouldEnableImage, enable audio: $shouldEnableAudio")

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
    if (onCleanUp != null) {
      onCleanUp()
    }
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
        val errorMessage = "Failed to prepare COMPAT continuation: ${e.message ?: "Unknown error"}"
        AgentPerformanceCoordinator.finishWithError(model.name, errorMessage.length)
        onError(errorMessage)
        return
      }

    val compatRuntime = AgentCompatRuntimeCoordinator.snapshot(model.name)
    val forceContinuationThinkingOff =
      compatRuntime?.lastFreshConversationReason == COMPAT_FRESH_REASON_TOOL_CONTINUATION

    val effectiveExtraContext =
      mutableMapOf<String, Any>().apply {
        extraContext?.forEach { (key, value) -> put(key, value) }
        if (forceContinuationThinkingOff) {
          // Gemma 4 uses enable_thinking in its Jinja chat template. Keep the template and the
          // native decoding configuration aligned; either layer alone proved insufficient on
          // MCP207 after a COMPAT tool result.
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
          for (image in images) {
            contents.add(Content.ImageBytes(image.toPngByteArray()))
          }
          for (audioClip in audioClips) {
            contents.add(Content.AudioBytes(audioClip))
          }
          if (effectiveInput.trim().isNotEmpty()) {
            contents.add(Content.Text(effectiveInput))
          }
          Message.user(Contents.of(contents))
        }

    val thinkingOverrideLabel =
      if (forceContinuationThinkingOff) {
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
        "disabled_template_native_budget0_continuation;$renderAudit"
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
            val decision =
              AgentCompatRuntimeCoordinator.onGenerationCompleted(
                modelName = model.name,
                generatedText = generatedText.toString(),
              )
            AgentPerformanceCoordinator.onInferenceDone(model.name, generatedChars)
            if (decision.blockedRepeatedToolCall) {
              val errorMessage =
                "兼容工具调用已停止：检测到连续三次完全相同的工具调用，已在再次执行前拦截，避免重复操作。"
              Log.w(TAG, "MCP208 blocked repeated COMPAT tool call for '${model.name}'.")
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
        if (forceContinuationThinkingOff) {
          ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0)
        } else {
          null
        },
    )
  }

  private fun prepareCompatAgentInput(model: Model, input: String): String {
    val prepareStartedNanos = SystemClock.elapsedRealtimeNanos()
    val prepared =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = model.name,
        rawInput = input,
        historyBudgetChars = resolveCompatRuntimeHistoryBudget(model),
      )
    if (!prepared.requiresFreshConversation) {
      return prepared.input
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
      rawInputChars = prepared.rawInputChars,
      effectiveInputChars = prepared.effectiveInputChars,
      historyStepCount = prepared.historyStepCount,
      historyChars = prepared.historyChars,
    )
    Log.d(
      TAG,
      "MCP208 explicit COMPAT fresh conversation for '${model.name}': reason=${prepared.freshConversationReason} reset=${"%.2f".format(resetMs)}ms prepare=${"%.2f".format(prepareMs)}ms rawChars=${prepared.rawInputChars} effectiveChars=${prepared.effectiveInputChars} historySteps=${prepared.historyStepCount} historyChars=${prepared.historyChars}",
    )
    return prepared.input
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
