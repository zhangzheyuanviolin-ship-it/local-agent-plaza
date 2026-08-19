#!/usr/bin/env python3
'MCP224 model diagnostics and user-controlled COMPAT tool-loop patch.'

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> tuple[Path, str]:
    path = ROOT / rel
    return path, path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")
    print(f"MCP224 patched {path.relative_to(ROOT)}")


def replace_once(text: str, old: str, new: str, rel: str) -> str:
    count = text.count(old)
    if count != 1:
        print(
            f"MCP224 patch expected one marker in {rel}, found {count}: {old[:160]!r}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    return text.replace(old, new, 1)


def add_import_once(text: str, anchor: str, new_import: str, rel: str) -> str:
    if new_import.strip() in text:
        return text
    return replace_once(text, anchor, anchor + new_import, rel)


# 1) Tool-mode semantics + remove obsolete eight-step constant.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentTooling.kt"
path, text = read(rel)
text = text.replace("const val MAX_COMPAT_TOOL_STEPS = 8\n", "")
old_native = '''    AgentToolModeValues.NATIVE ->
      if (supportsNativeAgentTools(model)) {
        ResolvedAgentToolMode.NATIVE
      } else {
        ResolvedAgentToolMode.COMPAT
      }
'''
if old_native in text:
    text = text.replace(
        old_native,
        "    AgentToolModeValues.NATIVE -> ResolvedAgentToolMode.NATIVE\n",
        1,
    )
elif "AgentToolModeValues.NATIVE -> ResolvedAgentToolMode.NATIVE" not in text:
    print("MCP224 failed to locate explicit NATIVE resolver branch.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)


# 2) UI COMPAT loop: remove total step cap. Keep user stop button as the owner of cancellation.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentChatScreen.kt"
path, text = read(rel)
old_limit = '''        val currentSteps = compatToolStepsByModel[model.name] ?: 0
        if (currentSteps >= MAX_COMPAT_TOOL_STEPS) {
          viewModel.removeLastMessage(model = model)
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageInfo(
                content = "兼容工具调用已停止：连续工具调用超过 $MAX_COMPAT_TOOL_STEPS 步。请调整提示词或改用原生模式。"
              ),
          )
          compatToolStepsByModel.remove(model.name)
          updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
          return@handleGenerationDone
        }
        compatToolStepsByModel[model.name] = currentSteps + 1
'''
new_limit = '''        ModelLifecycleDiagnostics.record(
          context = context,
          modelName = model.name,
          stage = "agent.compat.tool_call",
          message = "Compatibility tool call accepted without a fixed step limit",
          detail = "tool=${parsedToolCall.toolName}",
        )
'''
if old_limit in text:
    text = text.replace(old_limit, new_limit, 1)
elif "Compatibility tool call accepted without a fixed step limit" not in text:
    print("MCP224 failed to locate COMPAT step-limit block.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)


# 3) Runtime repeat guard becomes diagnostic-only. Identical calls remain counted but never auto-stop.
rel = "app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/AgentCompatRuntimeCoordinator.kt"
path, text = read(rel)
old_repeat = '''    val blocked = state.consecutiveRepeatedToolCalls >= 2
    state.awaitingToolResult = !blocked
'''
new_repeat = '''    // MCP224: repeated calls remain observable in diagnostics, but user-controlled Stop is the
    // only generic loop breaker. Small models may legitimately need several identical retries.
    val blocked = false
    state.awaitingToolResult = true
'''
if old_repeat in text:
    text = text.replace(old_repeat, new_repeat, 1)
elif "only generic loop breaker" not in text:
    print("MCP224 failed to locate repeated-tool-call blocker.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)


# 4) LiteRT-LM engine/conversation/inference lifecycle hooks.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatModelHelper.kt"
path, text = read(rel)
text = add_import_once(
    text,
    "import com.google.ai.edge.gallery.customtasks.agentchat.AgentPerformanceCoordinator\n",
    "import com.google.ai.edge.gallery.customtasks.agentchat.ModelLifecycleDiagnostics\n",
    rel,
)
old_model_path = '''    val modelPath = model.getPath(context = context)
    Log.d(
'''
new_model_path = '''    val modelPath = model.getPath(context = context)
    ModelLifecycleDiagnostics.recordModel(
      context = context,
      model = model,
      stage = "litert.engine_config",
      message = "Preparing LiteRT-LM EngineConfig",
      detail =
        "model_path=$modelPath | backend=$accelerator | vision_backend=$visionAccelerator | support_image=$shouldEnableImage | support_audio=$shouldEnableAudio | context=$configuredContextWindow | engine_max_tokens=$engineMaxNumTokens | max_output_tokens=$maxOutputTokens",
    )
    Log.d(
'''
if old_model_path in text and 'stage = "litert.engine_config"' not in text:
    text = text.replace(old_model_path, new_model_path, 1)

old_engine = '''      val engineInitStartNanos = SystemClock.elapsedRealtimeNanos()
      val engine = Engine(engineConfig)
      engine.initialize()
      val engineInitMs = elapsedMsSince(engineInitStartNanos)
'''
new_engine = '''      val engineInitStartNanos = SystemClock.elapsedRealtimeNanos()
      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.engine_initialize.start",
        message = "Engine.initialize() started",
      )
      val engine = Engine(engineConfig)
      engine.initialize()
      val engineInitMs = elapsedMsSince(engineInitStartNanos)
      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.engine_initialize.success",
        message = "Engine.initialize() succeeded",
        detail = "elapsed_ms=$engineInitMs",
      )
'''
if old_engine in text and 'stage = "litert.engine_initialize.start"' not in text:
    text = text.replace(old_engine, new_engine, 1)

old_conversation = '''      val conversationInitStartNanos = SystemClock.elapsedRealtimeNanos()
      val conversation =
'''
new_conversation = '''      val conversationInitStartNanos = SystemClock.elapsedRealtimeNanos()
      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.conversation_create.start",
        message = "Engine.createConversation() started",
        detail = "tools=${tools.size} | constrained_decoding=$enableConversationConstrainedDecoding",
      )
      val conversation =
'''
if old_conversation in text and 'stage = "litert.conversation_create.start"' not in text:
    text = text.replace(old_conversation, new_conversation, 1)

old_instance = '''      model.instance = LlmModelInstance(engine = engine, conversation = conversation)

      LlmChatPerformanceRegistry.recordInitialization(
'''
new_instance = '''      model.instance = LlmModelInstance(engine = engine, conversation = conversation)
      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = model,
        stage = "litert.model_initialize.success",
        message = "Model Engine and Conversation are ready",
        detail = "conversation_elapsed_ms=$conversationInitMs",
      )

      LlmChatPerformanceRegistry.recordInitialization(
'''
if old_instance in text and 'stage = "litert.model_initialize.success"' not in text:
    text = text.replace(old_instance, new_instance, 1)

old_init_catch = '''    } catch (e: Exception) {
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
'''
new_init_catch = '''    } catch (e: Exception) {
      ExperimentalFlags.enableSpeculativeDecoding = false
      ExperimentalFlags.enableConversationConstrainedDecoding = false
      ModelLifecycleDiagnostics.recordThrowable(
        context = context,
        model = model,
        stage = "litert.model_initialize.failure",
        throwable = e,
        detail =
          "backend=$accelerator | vision_backend=$visionAccelerator | support_image=$shouldEnableImage | support_audio=$shouldEnableAudio | configured_context=$configuredContextWindow | engine_max_tokens=$engineMaxNumTokens | max_output_tokens=$maxOutputTokens | model_path=$modelPath",
      )
      onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
      return
    }
'''
if old_init_catch in text and 'stage = "litert.model_initialize.failure"' not in text:
    text = text.replace(old_init_catch, new_init_catch, 1)

old_infer_error = '''            } else {
              Log.e(TAG, "onError", throwable)
              val errorMessage = "Error: ${throwable.message}"
'''
new_infer_error = '''            } else {
              Log.e(TAG, "onError", throwable)
              ModelLifecycleDiagnostics.recordThrowable(
                context = context,
                model = model,
                stage = "litert.inference.failure",
                throwable = throwable,
                detail = "input_chars=${effectiveInput.length} | compat_pass=$isCompatPass | compat_pass_kind=$compatPassKind",
              )
              val errorMessage = "Error: ${throwable.message}"
'''
if old_infer_error in text and 'stage = "litert.inference.failure"' not in text:
    text = text.replace(old_infer_error, new_infer_error, 1)

if 'stage = "litert.engine_config"' not in text or 'stage = "litert.model_initialize.failure"' not in text:
    print("MCP224 LiteRT lifecycle hooks incomplete.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)


# 5) Model manager: download/import/init events and retry attempts.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/modelmanager/ModelManagerViewModel.kt"
path, text = read(rel)
text = add_import_once(
    text,
    "import com.google.ai.edge.gallery.common.SystemPromptHelper\n",
    "import com.google.ai.edge.gallery.customtasks.agentchat.ModelLifecycleDiagnostics\n",
    rel,
)

old_last_error = '''      var lastError = ""
      repeat(2) { attempt ->
'''
new_last_error = '''      ModelLifecycleDiagnostics.startSession(
        context = context,
        model = model,
        taskId = task.id,
      )
      var lastError = ""
      repeat(2) { attempt ->
'''
if old_last_error in text and "ModelLifecycleDiagnostics.startSession(" not in text:
    text = text.replace(old_last_error, new_last_error, 1)

old_attempt_log = '''        Log.d(TAG, "Initializing model '${model.name}'... attempt=${attempt + 1}")
        model.initializing = true
'''
new_attempt_log = '''        Log.d(TAG, "Initializing model '${model.name}'... attempt=${attempt + 1}")
        ModelLifecycleDiagnostics.recordModel(
          context = context,
          model = model,
          stage = "model_manager.initialize.attempt",
          message = "Starting model initialization attempt ${attempt + 1}",
        )
        model.initializing = true
'''
if old_attempt_log in text and 'stage = "model_manager.initialize.attempt"' not in text:
    text = text.replace(old_attempt_log, new_attempt_log, 1)

old_success_log = '''          Log.d(TAG, "Model '${model.name}' initialized successfully on attempt ${attempt + 1}")
          updateModelInitializationStatus(
'''
new_success_log = '''          Log.d(TAG, "Model '${model.name}' initialized successfully on attempt ${attempt + 1}")
          ModelLifecycleDiagnostics.recordModel(
            context = context,
            model = model,
            stage = "model_manager.initialize.success",
            message = "Model initialized on attempt ${attempt + 1}",
          )
          updateModelInitializationStatus(
'''
if old_success_log in text and 'stage = "model_manager.initialize.success"' not in text:
    text = text.replace(old_success_log, new_success_log, 1)

old_failure_log = '''          Log.d(TAG, "Model '${model.name}' failed to initialize on attempt ${attempt + 1}: $error")
          if (attempt == 0 && shouldRetryAfterLiteRtEngineCreateFailure(error)) {
'''
new_failure_log = '''          Log.d(TAG, "Model '${model.name}' failed to initialize on attempt ${attempt + 1}: $error")
          ModelLifecycleDiagnostics.recordModel(
            context = context,
            model = model,
            stage = "model_manager.initialize.failure",
            message = "Model initialization attempt ${attempt + 1} failed",
            detail = error,
          )
          if (attempt == 0 && shouldRetryAfterLiteRtEngineCreateFailure(error)) {
'''
if old_failure_log in text and 'stage = "model_manager.initialize.failure"' not in text:
    text = text.replace(old_failure_log, new_failure_log, 1)

old_download_update = '''    _uiState.update { newUiState }
  }

  fun setInitializationStatus(
'''
new_download_update = '''    _uiState.update { newUiState }
    if (
      status.status == ModelDownloadStatusType.FAILED ||
        status.status == ModelDownloadStatusType.SUCCEEDED
    ) {
      ModelLifecycleDiagnostics.recordModel(
        context = context,
        model = curModel,
        stage =
          if (status.status == ModelDownloadStatusType.SUCCEEDED) {
            "model_download.success"
          } else {
            "model_download.failure"
          },
        message = "Model download status: ${status.status}",
        detail =
          "received_bytes=${status.receivedBytes} | total_bytes=${status.totalBytes} | error=${status.errorMessage}",
      )
    }
  }

  fun setInitializationStatus(
'''
if old_download_update in text and '"model_download.success"' not in text:
    text = text.replace(old_download_update, new_download_update, 1)

old_import_model = '''    // Create model.
    val model = createModelFromImportedModelInfo(info = info)

    val setOfTasks =
'''
new_import_model = '''    // Create model.
    val model = createModelFromImportedModelInfo(info = info)
    ModelLifecycleDiagnostics.recordModel(
      context = context,
      model = model,
      stage = "model_import.success",
      message = "Local LiteRT-LM model imported",
      detail = "file_name=${info.fileName} | file_bytes=${info.fileSize}",
    )

    val setOfTasks =
'''
if old_import_model in text and 'stage = "model_import.success"' not in text:
    text = text.replace(old_import_model, new_import_model, 1)

if 'stage = "model_manager.initialize.attempt"' not in text:
    print("MCP224 ModelManager lifecycle hooks incomplete.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)


# 6) Model cards surface load/download diagnostics even when chat cannot be entered.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/common/modelitem/ModelItem.kt"
path, text = read(rel)
text = add_import_once(
    text,
    "import com.google.ai.edge.gallery.R\n",
    "import com.google.ai.edge.gallery.customtasks.agentchat.ModelLifecycleDiagnosticsPanel\n",
    rel,
)
text = add_import_once(
    text,
    "import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel\n",
    "import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType\n",
    rel,
)
panel_anchor = '''      SharedTransitionLayout {
'''
panel_insert = '''      val initializationError =
        modelManagerUiState.modelInitializationStatus[model.name]
          ?.takeIf { it.status == ModelInitializationStatusType.ERROR }
          ?.error
          .orEmpty()
      val lifecycleFallback =
        initializationError.ifBlank {
          if (isDownloadFailed) downloadStatus?.errorMessage.orEmpty() else ""
        }
      ModelLifecycleDiagnosticsPanel(
        modelName = model.name,
        fallbackError = lifecycleFallback,
      )

      SharedTransitionLayout {
'''
if panel_anchor in text and "val initializationError =" not in text:
    text = text.replace(panel_anchor, panel_insert, 1)
if "ModelLifecycleDiagnosticsPanel(" not in text:
    print("MCP224 model-card diagnostics panel hook missing.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)


# 7) Agent chat also surfaces lifecycle/inference report next to the existing performance report.
rel = "app/src/main/java/com/google/ai/edge/gallery/ui/llmchat/LlmChatScreen.kt"
path, text = read(rel)
text = add_import_once(
    text,
    "import com.google.ai.edge.gallery.customtasks.agentchat.AgentPerformanceDiagnosticsPanel\n",
    "import com.google.ai.edge.gallery.customtasks.agentchat.ModelLifecycleDiagnosticsPanel\n",
    rel,
)
old_panel = '''      if (taskId == BuiltInTaskId.LLM_AGENT_CHAT) {
        AgentPerformanceCoordinator.reports[model.name]?.let { report ->
'''
new_panel = '''      if (taskId == BuiltInTaskId.LLM_AGENT_CHAT) {
        ModelLifecycleDiagnosticsPanel(modelName = model.name)
        AgentPerformanceCoordinator.reports[model.name]?.let { report ->
'''
if old_panel in text and "ModelLifecycleDiagnosticsPanel(modelName = model.name)" not in text:
    text = text.replace(old_panel, new_panel, 1)
if "ModelLifecycleDiagnosticsPanel(modelName = model.name)" not in text:
    print("MCP224 chat lifecycle panel hook missing.", file=sys.stderr)
    raise SystemExit(1)
write(path, text)
