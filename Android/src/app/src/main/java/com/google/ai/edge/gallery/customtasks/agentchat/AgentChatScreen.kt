/*
 * Copyright 2026 Google LLC
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

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.BaseGalleryWebViewClient
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.common.buildTrackableUrlAnnotatedString
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageCollapsableProgressPanel
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWebView
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.LogMessage
import com.google.ai.edge.gallery.ui.common.chat.LogMessageLevel
import com.google.ai.edge.gallery.ui.common.chat.SendMessageTrigger
import com.google.ai.edge.gallery.ui.llmchat.LlmChatScreen
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val TAG = "AGAgentChatScreen"
private val chatViewJavascriptInterface = ChatWebViewJavascriptInterface()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentChatScreen(
  task: Task,
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  agentTools: AgentTools,
  viewModel: LlmChatViewModel = hiltViewModel(),
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
  mcpManagerViewModel: McpManagerViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel
  agentTools.mcpManagerViewModel = mcpManagerViewModel
  val density = LocalDensity.current
  val windowInfo = LocalWindowInfo.current
  val screenWidthDp = remember { with(density) { windowInfo.containerSize.width.toDp() } }
  var showSkillManagerBottomSheet by remember { mutableStateOf(false) }
  var showMcpManagerBottomSheet by remember { mutableStateOf(false) }
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var askInfoInputValue by remember { mutableStateOf("") }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val chatWebViewClient = remember { ChatWebViewClient(context = context) }
  var curSystemPrompt by remember { mutableStateOf(task.defaultSystemPrompt) }
  val systemPromptUpdatedMessage = stringResource(R.string.system_prompt_updated)
  var sendMessageTrigger by remember { mutableStateOf<SendMessageTrigger?>(null) }
  var showAlertForDisabledSkill by remember { mutableStateOf(false) }
  var disabledSkillName by remember { mutableStateOf("") }
  val coroutineScope = rememberCoroutineScope()
  val compatToolStepsByModel = remember { mutableStateMapOf<String, Int>() }
  LaunchedEffect(task) { viewModel.loadSystemPrompt(task) }
  val uiSystemPrompt by viewModel.uiSystemPrompt.collectAsState()
  LaunchedEffect(uiSystemPrompt) { curSystemPrompt = uiSystemPrompt }
  val buildCompatInstructionPayload = { model: Model ->
    createAgentSessionConfig(
      model = model,
      baseSystemPrompt = curSystemPrompt,
      skillManagerViewModel = skillManagerViewModel,
    )
      .compatInstructionPayload
      .orEmpty()
  }

  val interceptPartialResult: (Model, String, Boolean, String?) -> Boolean =
    { _, partialResult, _, partialThinkingResult ->
      if (!partialThinkingResult.isNullOrEmpty()) {
        false
      } else if (!IntentHandler.hasPendingAssistantWrite()) {
        false
      } else {
        IntentHandler.appendPendingAssistantWrite(context = context, contentChunk = partialResult)
      }
    }
  val handleFirstToken: (Model) -> Unit = { model ->
    AgentDiagnosticsLogger.log(
      context = context,
      category = "chat.first_token",
      message = "First token received for model ${model.name}",
    )
    updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
  }
  val handleCompatError: (Model, String) -> Unit = { model, errorMessage ->
    AgentDiagnosticsLogger.log(
      context = context,
      category = "chat.error",
      message = "Inference error for model ${model.name}",
      detail = errorMessage,
    )
    compatToolStepsByModel.remove(model.name)
    viewModel.handleError(
      context = context,
      task = task,
      model = model,
      errorMessage = errorMessage,
      modelManagerViewModel = modelManagerViewModel,
    )
  }
  var continueCompatConversation: ((Model, String) -> Unit)? = null
  val handleGenerationDone: (Model) -> Unit = handleGenerationDone@ { model ->
    val lastAgentText =
      viewModel.getLastMessageWithTypeAndSide(
        model = model,
        type = ChatMessageType.TEXT,
        side = ChatSide.AGENT,
      ) as? ChatMessageText
    val pendingWriteResult =
      IntentHandler.commitPendingAssistantWrite(context = context, content = lastAgentText?.content)
    if (pendingWriteResult != null) {
      if (lastAgentText != null) {
        viewModel.removeLastMessage(model = model)
      }
      val savedMessage =
        "已将上一条回复写入 ${pendingWriteResult.path}，共 ${pendingWriteResult.bytesWritten} 字节。"
      viewModel.addMessage(model = model, message = ChatMessageInfo(content = savedMessage))
      AgentDiagnosticsLogger.log(
        context = context,
        category = "chat.pending_write_committed",
        message = "Saved assistant reply to ${pendingWriteResult.path}",
        detail = "bytes=${pendingWriteResult.bytesWritten}",
      )
    }
    AgentDiagnosticsLogger.log(
      context = context,
      category = "chat.generation_done",
      message = "Generation finished for model ${model.name}",
    )
    flushAgentToolArtifacts(
      viewModel = viewModel,
      model = model,
      agentTools = agentTools,
      screenWidthDp = screenWidthDp.value,
    )

    if (resolveAgentToolMode(model) == ResolvedAgentToolMode.COMPAT && lastAgentText != null) {
      val parsedToolCall = parseCompatToolCall(lastAgentText.content)
      val visibleCompatText = stripCompatThinkingText(lastAgentText.content)
      if (parsedToolCall == null && visibleCompatText != lastAgentText.content) {
        viewModel.removeLastMessage(model = model)
        if (visibleCompatText.isNotBlank()) {
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageText(
                content = visibleCompatText,
                side = lastAgentText.side,
                latencyMs = lastAgentText.latencyMs,
                isMarkdown = lastAgentText.isMarkdown,
                llmBenchmarkResult = lastAgentText.llmBenchmarkResult,
                accelerator = lastAgentText.accelerator,
                hideSenderLabel = lastAgentText.hideSenderLabel,
                data = lastAgentText.data,
              ),
          )
        } else {
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageInfo(
                content = "兼容工具调用已停止：模型只输出了思考内容，没有生成可展示的最终回复。请降低单轮输出长度或换用 Qwen3-4B-Instruct-2507。"
              ),
          )
          compatToolStepsByModel.remove(model.name)
          updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
          return@handleGenerationDone
        }
      }
      if (parsedToolCall != null) {
        val originalUserRequest =
          (viewModel.getLastMessageWithTypeAndSide(
            model = model,
            type = ChatMessageType.TEXT,
            side = ChatSide.USER,
          ) as? ChatMessageText)
            ?.content
            .orEmpty()
        val currentSteps = compatToolStepsByModel[model.name] ?: 0
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
        viewModel.removeLastMessage(model = model)
        viewModel.addMessage(
          model = model,
          message = ChatMessageInfo(content = "兼容工具调用：正在执行 ${parsedToolCall.toolName}。"),
        )
        coroutineScope.launch(Dispatchers.Default) {
          val executionResult =
            runCatching { agentTools.executeCompatToolCall(parsedToolCall.toolName, parsedToolCall.arguments) }
              .getOrElse { throwable ->
                AgentTools.CompatToolExecutionResult(
                  toolName = parsedToolCall.toolName,
                  result =
                    mapOf(
                      "status" to "failed",
                      "error" to (throwable.message ?: "Compatibility tool execution failed."),
                      "recovery_hint" to "检查工具参数后重试，或切换到原生模式。",
                    ),
                )
              }
          val summary = summarizeCompatToolResult(executionResult.result)
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageInfo(
                content =
                  if (summary.isBlank()) {
                    "兼容工具调用已完成：${executionResult.toolName}。"
                  } else {
                    "兼容工具调用已完成：${executionResult.toolName}，$summary"
                  },
              ),
          )
          flushAgentToolArtifacts(
            viewModel = viewModel,
            model = model,
            agentTools = agentTools,
            screenWidthDp = screenWidthDp.value,
          )
          updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
          val toolResultPrompt =
            buildCompatToolResultPrompt(
              toolName = executionResult.toolName,
              result = executionResult.result,
              originalUserRequest = originalUserRequest,
            )
          val auditPath =
            agentTools.saveCompatToolAudit(
              toolName = executionResult.toolName,
              originalUserRequest = originalUserRequest,
              result = executionResult.result,
              modelPrompt = toolResultPrompt,
            )
          if (auditPath != null) {
            appendProgressLog(
              viewModel = viewModel,
              model = model,
              level = LogMessageLevel.Info,
              source = "tool_audit",
              message = "完整工具调用审计已保存到 $auditPath",
            )
            viewModel.addMessage(
              model = model,
              message = ChatMessageInfo(content = "工具调用审计已保存：$auditPath"),
            )
          }
          continueCompatConversation?.invoke(
            model,
            toolResultPrompt,
          )
        }
        return@handleGenerationDone
      }
    }

    compatToolStepsByModel.remove(model.name)
    updateProgressPanel(viewModel = viewModel, model = model, agentTools = agentTools)
  }
  continueCompatConversation = { model, input ->
    viewModel.generateResponse(
      model = model,
      input = input,
      onFirstToken = handleFirstToken,
      onDone = { handleGenerationDone(model) },
      onError = { errorMessage -> handleCompatError(model, errorMessage) },
      allowThinking = false,
      extraContextOverride = mapOf("enable_thinking" to "false"),
      onInterceptPartialResult = interceptPartialResult,
    )
  }

  LlmChatScreen(
    modelManagerViewModel = modelManagerViewModel,
    taskId = BuiltInTaskId.LLM_AGENT_CHAT,
    navigateUp = navigateUp,
    onBeforeSendMessage = { model, _ -> compatToolStepsByModel.remove(model.name) },
    transformOutgoingText = { model, text ->
      val compatInstructionPayload = buildCompatInstructionPayload(model)
      if (
        resolveAgentToolMode(model) == ResolvedAgentToolMode.COMPAT &&
          compatInstructionPayload.isNotBlank()
      ) {
        buildCompatUserInput(userInput = text, compatInstructionPayload = compatInstructionPayload)
      } else {
        text
      }
    },
    onStopButtonClickedOverride = { model ->
      compatToolStepsByModel.remove(model.name)
      viewModel.stopResponse(model = model)
      val pendingWriteResult = IntentHandler.commitPendingAssistantWrite(context = context)
      IntentHandler.clearPendingAssistantWrite()
      if (pendingWriteResult != null) {
        viewModel.addMessage(
          model = model,
          message =
            ChatMessageInfo(
              content =
                "已手动停止当前任务，并保留 ${pendingWriteResult.path} 中已写入的 ${pendingWriteResult.bytesWritten} 字节内容。"
            ),
        )
      } else {
        viewModel.addMessage(model = model, message = ChatMessageInfo(content = "已手动停止当前任务。"))
      }
      AgentDiagnosticsLogger.log(
        context = context,
        category = "chat.manual_stop",
        message = "User stopped the current task for model ${model.name}",
      )
    },
    onInterceptPartialResult = interceptPartialResult,
    onFirstToken = handleFirstToken,
    onGenerateResponseDone = handleGenerationDone,
    onResetSessionClickedOverride = { task, _, initialMessages ->
      resetSessionWithCurrentSkills(
        viewModel,
        modelManagerViewModel,
        skillManagerViewModel,
        task,
        curSystemPrompt,
        agentTools,
        mcpManagerViewModel,
        initialMessages = initialMessages,
      )
    },
    onSkillClicked = { showSkillManagerBottomSheet = true },
    onMcpClicked = { showMcpManagerBottomSheet = true },
    skillCount = skillManagerViewModel.uiState.collectAsState().value.skills.count { it.skill.selected },
    mcpCount = mcpManagerViewModel.uiState.collectAsState().value.mcpServers.count { it.mcpServer.enabled },
    showImagePicker = true,
    showAudioPicker = true,
    getActiveSkills = {
      skillManagerViewModel.getSelectedSkills().map { skill ->
        skillManagerViewModel.getSkillShortId(skill)
      }
    },
    extraContextForModel = { model ->
      if (resolveAgentToolMode(model) == ResolvedAgentToolMode.COMPAT) {
        mapOf("enable_thinking" to "false")
      } else {
        null
      }
    },
    composableBelowMessageList = { model ->
      val actionChannel = agentTools.actionChannel
      val doneIcon = ImageVector.vectorResource(R.drawable.skill)
      // Use rememberUpdatedState to ensure that LaunchedEffect captures the
      // latest active model when the model is switched during an ongoing skill execution.
      val currentModel by androidx.compose.runtime.rememberUpdatedState(model)
      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          Log.d(TAG, "Handling action: $action")
          when (action) {
            is SkillProgressAgentAction -> {
              viewModel.updateCollapsableProgressPanelMessage(
                model = currentModel,
                title = action.label,
                inProgress = action.inProgress,
                doneIcon = doneIcon,
                addItemTitle = action.addItemTitle,
                addItemDescription = action.addItemDescription,
                customData = action.customData,
              )
              appendProgressLog(
                viewModel = viewModel,
                model = currentModel,
                level = LogMessageLevel.Info,
                source = "agent_action",
                message =
                  buildString {
                    append(action.label)
                    if (action.addItemDescription.isNotBlank()) {
                      append(" | ")
                      append(action.addItemDescription)
                    }
                  },
              )
              AgentDiagnosticsLogger.log(
                context = context,
                category = "chat.skill_progress",
                message = action.label,
                detail = action.addItemDescription,
              )
            }
            is CallJsAgentAction -> {
              val skillName =
                if (action.url.contains("/skills/")) {
                  action.url.substringAfter("/skills/").substringBefore("/")
                } else if (action.url.startsWith(LOCAL_URL_BASE + "/")) {
                  action.url.substringAfter(LOCAL_URL_BASE + "/").substringBefore("/")
                } else {
                  action.url
                }
              val skill = skillManagerViewModel.getSkill(name = skillName)
              val skillId = skill?.let { skillManagerViewModel.getSkillShortId(it) } ?: "xxxx"
              try {
                appendProgressLog(
                  viewModel = viewModel,
                  model = currentModel,
                  level = LogMessageLevel.Info,
                  source = "js_dispatch",
                  message = "Dispatching JS skill $skillName.",
                )
                AgentDiagnosticsLogger.log(
                  context = context,
                  category = "chat.js_dispatch",
                  message = "Dispatching JS skill $skillName",
                  detail = "url=${action.url}",
                )
                // Load url.
                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    appendProgressLog(
                      viewModel = viewModel,
                      model = currentModel,
                      level = LogMessageLevel.Info,
                      source = "js_page",
                      message = "Skill page loaded for $skillName.",
                    )
                    AgentDiagnosticsLogger.log(
                      context = context,
                      category = "chat.js_page_loaded",
                      message = "Skill page loaded for $skillName",
                    )
                    continuation.resume(Unit)
                  }
                  Log.d(TAG, "Loading url: ${action.url}")
                  appendProgressLog(
                    viewModel = viewModel,
                    model = currentModel,
                    level = LogMessageLevel.Info,
                    source = "js_page",
                    message = "Loading skill page ${action.url.replace(LOCAL_URL_BASE, "")}",
                  )
                  AgentDiagnosticsLogger.log(
                    context = context,
                    category = "chat.js_page_load_start",
                    message = "Loading skill page for $skillName",
                    detail = action.url,
                  )
                  webViewRef?.loadUrl(action.url)
                }

                // Execute JS.
                Log.d(TAG, "Start to run js")
                chatViewJavascriptInterface.onResultListener = { result ->
                  Log.d(TAG, "Got result:\n$result")
                  appendProgressLog(
                    viewModel = viewModel,
                    model = currentModel,
                    level = LogMessageLevel.Info,
                    source = "js_result",
                    message = "JS skill $skillName returned ${result.length} chars.",
                  )
                  AgentDiagnosticsLogger.log(
                    context = context,
                    category = "chat.js_result",
                    message = "JS skill $skillName returned",
                    detail = result.take(1000),
                  )
                  action.result.complete(result)
                  val isSuccess = !result.contains("\"error\":")
                  val errorType = if (isSuccess) "" else "js_error"
                  Log.d(
                    TAG,
                    "Analytics: skill_execution, skill_name=$skillName, success=$isSuccess, error_type=$errorType",
                  )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.SKILL_EXECUTION.id,
                    Bundle().apply {
                      putString("skill_name", skillName)
                      putString("skill_id", skillId)
                      putBoolean("success", isSuccess)
                      putString("error_type", errorType)
                    },
                  )
                }

                val safeData = JSONObject.quote(action.data)
                val safeSecret = JSONObject.quote(action.secret)
                val safeConfig = JSONObject.quote(action.config)
                val script =
                  """
                  (async function() {
                      var startTs = Date.now();
                      while(true) {
                        if (typeof ai_edge_gallery_get_result === 'function') {
                          break;
                        }
                        await new Promise(resolve=>{
                          setTimeout(resolve, 100)
                        });
                        if (Date.now() - startTs > 10000) {
                          break;
                        }
                      }
                      var result = await ai_edge_gallery_get_result($safeData, $safeSecret, $safeConfig);
                      AiEdgeGallery.onResultReady(result);
                  })()
                  """
                    .trimIndent()
                appendProgressLog(
                  viewModel = viewModel,
                  model = currentModel,
                  level = LogMessageLevel.Info,
                  source = "js_exec",
                  message = "Executing ai_edge_gallery_get_result for $skillName.",
                )
                AgentDiagnosticsLogger.log(
                  context = context,
                  category = "chat.js_eval_start",
                  message = "Executing JS bridge for $skillName",
                  detail = action.data.take(1000),
                )
                webViewRef?.evaluateJavascript(script, null)
              } catch (e: Exception) {
                appendProgressLog(
                  viewModel = viewModel,
                  model = currentModel,
                  level = LogMessageLevel.Error,
                  source = "js_exception",
                  message = "JS skill $skillName crashed: ${e.message ?: "Unknown error"}",
                )
                AgentDiagnosticsLogger.log(
                  context = context,
                  category = "chat.js_exception",
                  message = "JS skill $skillName crashed",
                  detail = e.stackTraceToString(),
                )
                Log.d(
                  TAG,
                  "Analytics: skill_execution, skill_name=$skillName, success=false, error_type=exception",
                )
                firebaseAnalytics?.logEvent(
                  GalleryEvent.SKILL_EXECUTION.id,
                  Bundle().apply {
                    putString("skill_name", skillName)
                    putString("skill_id", skillId)
                    putBoolean("success", false)
                    putString("error_type", "exception")
                  },
                )
                action.result.completeExceptionally(e)
              }
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInputValue = "" // Reset input
              showAskInfoDialog = true
            }
          }
        }
      }

      GalleryWebView(
        modifier = Modifier.size(300.dp),
        onWebViewCreated = { webView ->
          webViewRef = webView
          chatViewJavascriptInterface.appContext = context.applicationContext
          chatViewJavascriptInterface.onExaResultListener = { requestId, result ->
            val safeRequestId = JSONObject.quote(requestId)
            val safeResult = JSONObject.quote(result)
            webView.post {
              webView.evaluateJavascript(
                "window.ai_edge_gallery_receive_exa_result($safeRequestId, $safeResult);",
                null,
              )
            }
          }
          webView.addJavascriptInterface(chatViewJavascriptInterface, "AiEdgeGallery")
        },
        customWebViewClient = chatWebViewClient,
        onConsoleMessage = { consoleMessage ->
          consoleMessage?.let { curConsoleMessage ->
            // Create a LogMessage from the ConsoleMessage and add it to the progress panel.
            val logMessage =
              LogMessage(
                level =
                  when (curConsoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.LOG -> LogMessageLevel.Info
                    ConsoleMessage.MessageLevel.ERROR -> LogMessageLevel.Error
                    ConsoleMessage.MessageLevel.WARNING -> LogMessageLevel.Warning
                    else -> LogMessageLevel.Info
                  },
                source = curConsoleMessage.sourceId(),
                lineNumber = curConsoleMessage.lineNumber(),
                message = curConsoleMessage.message(),
              )
            viewModel.addLogMessageToLastCollapsableProgressPanel(
              model = model,
              logMessage = logMessage,
            )
            AgentDiagnosticsLogger.log(
              context = context,
              category = "chat.webview_console",
              message = curConsoleMessage.messageLevel().name,
              detail =
                "${curConsoleMessage.sourceId()}:${curConsoleMessage.lineNumber()} ${curConsoleMessage.message()}",
            )
            Log.d(
              TAG,
              "${curConsoleMessage.message()} " +
                "-- From line ${curConsoleMessage.lineNumber()} of ${curConsoleMessage.sourceId()}",
            )
          }
        },
      )
    },
    allowEditingSystemPrompt = true,
    curSystemPrompt = curSystemPrompt,
    onSystemPromptChanged = { newPrompt ->
      curSystemPrompt = newPrompt
      viewModel.applySystemPromptChange(
        task = task,
        model = modelManagerViewModel.uiState.value.selectedModel,
        newPrompt = newPrompt,
        systemPromptUpdatedMessage = systemPromptUpdatedMessage,
      )
    },
    emptyStateComposable = { model ->
      val uiState by viewModel.uiState.collectAsState()
      val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
      val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[model.name]
      Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
          !WindowInsets.isImeVisible,
          enter = fadeIn(animationSpec = tween(200)),
          exit = fadeOut(animationSpec = tween(200)),
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
              modifier =
                Modifier.align(Alignment.Center)
                  .padding(horizontal = 48.dp)
                  .padding(bottom = 48.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                stringResource(R.string.introducing),
                style = MaterialTheme.typography.headlineSmall,
              )
              Text(
                stringResource(R.string.agent_skills),
                style =
                  MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Medium,
                    brush =
                      Brush.linearGradient(colors = listOf(Color(0xFF85B1F8), Color(0xFF3174F1))),
                  ),
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
              )
              Text(
                buildAnnotatedString {
                  append("通过加载不同技能或")
                  append(
                    buildTrackableUrlAnnotatedString(
                      url = "https://github.com/google-ai-edge/gallery/tree/main/skills",
                      linkText = "创建您自己的技能",
                    )
                  )
                  append("来获得更专业、更高阶的推理能力。\n\n点击下方示例提示词即可体验智能体技能。")
                },
                style =
                  MaterialTheme.typography.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        }

        Row(
          modifier =
            Modifier.align(Alignment.BottomCenter)
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (promptChip in TRYOUT_CHIPS) {
            FilledTonalButton(
              enabled =
                modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED &&
                  !uiState.isResettingSession,
              onClick = {
                // Skill is selected, trigger sending the message.
                if (skillManagerViewModel.isSkillSelected(promptChip.skillName)) {
                  sendMessageTrigger =
                    SendMessageTrigger(
                      model = model,
                      messages =
                        listOf(ChatMessageText(content = promptChip.prompt, side = ChatSide.USER)),
                    )
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.BUTTON_CLICKED.id,
                    Bundle().apply {
                      putString("event_type", "agent_skills_prompt_chip")
                      putString("button_id", promptChip.label)
                    },
                  )
                }
                // Skill is not selected, show alert dialog.
                else {
                  disabledSkillName = promptChip.skillName
                  showAlertForDisabledSkill = true
                }
              },
              contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
              Icon(promptChip.icon, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(promptChip.label)
            }
          }
        }
      }
    },
    sendMessageTrigger = sendMessageTrigger,
  )

  if (showAskInfoDialog && currentAskInfoAction != null) {
    val action = currentAskInfoAction!!
    SecretEditorDialog(
      title = action.dialogTitle,
      fieldLabel = action.fieldLabel,
      value = askInfoInputValue,
      onValueChange = { askInfoInputValue = it },
      onDone = {
        action.result.complete(askInfoInputValue)
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
      onDismiss = {
        action.result.complete("")
        showAskInfoDialog = false
        currentAskInfoAction = null
      },
    )
  }

  if (showSkillManagerBottomSheet) {
    SkillManagerBottomSheet(
      agentTools = agentTools,
      skillManagerViewModel = skillManagerViewModel,
      onDismiss = { selectedSkillsChanged ->
        // Hide sheet.
        showSkillManagerBottomSheet = false

        // Reset session when selected skills changed.
        if (selectedSkillsChanged) {
          Log.d(TAG, "Selected skill changed. Resetting conversation.")
          resetSessionWithCurrentSkills(
            viewModel,
            modelManagerViewModel,
            skillManagerViewModel,
            task,
            curSystemPrompt,
            agentTools,
            mcpManagerViewModel,
          )
        }
      },
    )
  }

  if (showMcpManagerBottomSheet) {
    McpManagerBottomSheet(
      mcpManagerViewModel = mcpManagerViewModel,
      onDismiss = { selectedMcpsAndToolsChanged ->
        showMcpManagerBottomSheet = false
        if (selectedMcpsAndToolsChanged) {
          Log.d(TAG, "Selected MCPs or tools changed. Resetting conversation.")
          resetSessionWithCurrentSkills(
            viewModel,
            modelManagerViewModel,
            skillManagerViewModel,
            task,
            curSystemPrompt,
            agentTools,
            mcpManagerViewModel,
          )
        }
      },
    )
  }
  if (showAlertForDisabledSkill) {
    AlertDialog(
      onDismissRequest = { showAlertForDisabledSkill = false },
      title = { Text(stringResource(R.string.skill_disabled_dialog_title, disabledSkillName)) },
      text = { Text(stringResource(R.string.enable_skill_dialog_content)) },
      confirmButton = {
        Button(onClick = { showAlertForDisabledSkill = false }) {
          Text(stringResource(R.string.ok))
        }
      },
    )
  }
}

private fun updateProgressPanel(viewModel: LlmChatViewModel, model: Model, agentTools: AgentTools) {
  // Update status.
  val lastProgressPanelMessage =
    viewModel.getLastMessageWithType(
      model = model,
      type = ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
    )
  if (
    lastProgressPanelMessage != null &&
      lastProgressPanelMessage is ChatMessageCollapsableProgressPanel
  ) {
    if (lastProgressPanelMessage.title.startsWith("Loading")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Loading", "Loaded"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Calling")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Calling", "Called"),
          inProgress = false,
        )
      )
    } else if (lastProgressPanelMessage.title.startsWith("Executing")) {
      agentTools.sendAgentAction(
        SkillProgressAgentAction(
          label = lastProgressPanelMessage.title.replace("Executing", "Executed"),
          inProgress = false,
        )
      )
    }
  }
}

private fun resetSessionWithCurrentSkills(
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  skillManagerViewModel: SkillManagerViewModel,
  task: Task,
  curSystemPrompt: String,
  agentTools: AgentTools,
  mcpManagerViewModel: McpManagerViewModel,
  onDone: (Model) -> Unit = {},
  initialMessages: List<ChatMessage> = listOf(),
) {
  IntentHandler.clearPendingAssistantWrite()
  val model = modelManagerViewModel.uiState.value.selectedModel
  val litertMessages = initialMessages.mapNotNull { chatMessage ->
    if (chatMessage is ChatMessageText) {
      if (chatMessage.side == ChatSide.USER) {
        Message.user(chatMessage.content)
      } else {
        Message.model(chatMessage.content)
      }
    } else null
  }
  val sessionConfig =
    createAgentSessionConfig(
      model = model,
      baseSystemPrompt = curSystemPrompt,
      skillManagerViewModel = skillManagerViewModel,
    )
  val mcpToolsPrompt = mcpManagerViewModel.getToolsPrompt()
  val finalSystemInstruction =
    if (mcpToolsPrompt.isNotBlank() && sessionConfig.systemInstruction != null) {
      val baseText = sessionConfig.systemInstruction.toString()
      Contents.of(
        buildString {
          append(baseText)
          append("\n\n--- MCP TOOLS ---\n")
          append(mcpToolsPrompt)
          append(
            "\n\nWhen the user request matches an MCP tool above, call the tool `runMcpTool` with parameters `toolName` and `input` (JSON-encoded arguments)."
          )
        }
      )
    } else {
      sessionConfig.systemInstruction
    }
  viewModel.resetSession(
    task = task,
    model = model,
    systemInstruction = finalSystemInstruction,
    tools = if (sessionConfig.useNativeTools) listOf(com.google.ai.edge.litertlm.tool(agentTools)) else listOf(),
    supportImage = false,
    supportAudio = false,
    onDone = { onDone(model) },
    enableConversationConstrainedDecoding = sessionConfig.enableConversationConstrainedDecoding,
    initialMessages = litertMessages,
  )
}

private fun flushAgentToolArtifacts(
  viewModel: LlmChatViewModel,
  model: Model,
  agentTools: AgentTools,
  screenWidthDp: Float,
) {
  agentTools.resultImageToShow?.let { resultImage ->
    resultImage.base64?.let { base64 ->
      decodeBase64ToBitmap(base64String = base64)?.let { bitmap ->
        viewModel.addMessage(
          model = model,
          message =
            ChatMessageImage(
              bitmaps = listOf(bitmap),
              imageBitMaps = listOf(bitmap.asImageBitmap()),
              side = ChatSide.AGENT,
              maxSize = (screenWidthDp * 0.8f).toInt(),
              latencyMs = -1.0f,
              hideSenderLabel = true,
            ),
        )
      }
    }
    agentTools.resultImageToShow = null
  }

  agentTools.resultWebviewToShow?.let { webview ->
    val url = webview.url ?: ""
    val iframe = webview.iframe == true
    val aspectRatio = webview.aspectRatio ?: 1.333f
    viewModel.addMessage(
      model = model,
      message =
        ChatMessageWebView(
          url = url,
          iframe = iframe,
          aspectRatio = aspectRatio,
          hideSenderLabel = true,
        ),
    )
    agentTools.resultWebviewToShow = null
  }
}

class ChatWebViewJavascriptInterface {
  var onResultListener: ((String) -> Unit)? = null
  var onExaResultListener: ((String, String) -> Unit)? = null
  var appContext: Context? = null

  @JavascriptInterface
  fun onResultReady(result: String) {
    appContext?.let {
      AgentDiagnosticsLogger.log(
        context = it,
        category = "js.on_result_ready",
        message = "WebView returned JS result",
        detail = result.take(1000),
      )
    }
    onResultListener?.invoke(result)
  }

  @JavascriptInterface
  fun exaSearchAsync(requestId: String, requestBody: String, secret: String) {
    appContext?.let {
      AgentDiagnosticsLogger.log(
        context = it,
        category = "exa.async_start",
        message = "Starting Exa native bridge request",
        detail = requestBody.take(1000),
      )
    }
    val trimmedSecret = secret.trim()
    if (trimmedSecret.isEmpty()) {
      onExaResultListener?.invoke(
        requestId,
        JSONObject().put("error", "Missing Exa API key.").toString(),
      )
      return
    }

    Thread {
      onExaResultListener?.invoke(requestId, performExaSearch(requestBody = requestBody, secret = trimmedSecret))
    }.start()
  }

  private fun performExaSearch(requestBody: String, secret: String): String {
    val requestMeta = parseExaRequestMeta(requestBody = requestBody)
    var connection: HttpURLConnection? = null
    return try {
      connection = (URL("https://api.exa.ai/search").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 20000
        readTimeout = 20000
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("x-api-key", secret)
      }

      connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(requestBody)
      }

      val statusCode = connection.responseCode
      val stream =
        if (statusCode in 200..299) {
          connection.inputStream
        } else {
          connection.errorStream ?: connection.inputStream
        }
      val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"

      if (statusCode !in 200..299) {
        JSONObject().put("error", extractExaErrorMessage(body = body, statusCode = statusCode)).toString()
      } else {
        JSONObject().put("result", formatExaResponse(body = body, requestMeta = requestMeta)).toString()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Exa native bridge request failed", e)
      appContext?.let {
        AgentDiagnosticsLogger.log(
          context = it,
          category = "exa.async_failed",
          message = "Exa native bridge request failed",
          detail = e.stackTraceToString(),
        )
      }
      JSONObject()
        .put("error", "Failed to run Exa search: ${e.message ?: "Unknown error"}")
        .toString()
    } finally {
      connection?.disconnect()
    }
  }

  private fun parseExaRequestMeta(requestBody: String): ExaRequestMeta {
    val request = runCatching { JSONObject(requestBody) }.getOrNull()
    val category = request?.optString("category").orEmpty()
    val contents = request?.optJSONObject("contents")
    val detailMode =
      when {
        contents?.optBoolean("text") == true -> "full"
        contents != null &&
          contents.optJSONObject("summary") != null &&
          contents.optJSONObject("highlights") != null ->
          "standard"
        contents != null && contents.optJSONObject("highlights") != null -> "light"
        else -> "summary"
      }
    val topic =
      when (category) {
        "news" -> "news"
        "financial report" -> "finance"
        else -> "general"
      }
    return ExaRequestMeta(
      query = request?.optString("query").orEmpty(),
      topic = topic,
      searchType = request?.optString("type").takeUnless { it.isNullOrBlank() } ?: "auto",
      detailMode = detailMode,
      resultCount = request?.optInt("numResults", 3) ?: 3,
    )
  }

  private fun extractExaErrorMessage(body: String, statusCode: Int): String {
    val payload = runCatching { JSONObject(body) }.getOrNull()
    return payload?.optString("error").takeUnless { it.isNullOrBlank() }
      ?: payload?.optString("detail").takeUnless { it.isNullOrBlank() }
      ?: payload?.optString("message").takeUnless { it.isNullOrBlank() }
      ?: "Exa request failed with HTTP $statusCode."
  }

  private fun formatExaResponse(body: String, requestMeta: ExaRequestMeta): String {
    val response = runCatching { JSONObject(body) }.getOrNull()
    if (response == null) {
      return "搜索查询: ${requestMeta.query}\nExa 返回了无法解析的响应。"
    }

    val lines = mutableListOf<String>()
    lines += "搜索查询: ${requestMeta.query}"
    lines += "主题: ${requestMeta.topic}"
    lines += "搜索类型: ${requestMeta.searchType}"
    lines += "详细度: ${requestMeta.detailMode}"
    lines += "请求条目数: ${requestMeta.resultCount}"

    val requestId = response.optString("requestId")
    if (requestId.isNotBlank()) {
      lines += "请求 ID: $requestId"
    }
    val resolvedSearchType = response.optString("searchType")
    if (resolvedSearchType.isNotBlank()) {
      lines += "实际搜索类型: $resolvedSearchType"
    }

    val results = response.optJSONArray("results")
    if (results != null && results.length() > 0) {
      lines += ""
      lines += "来源:"
      for (i in 0 until results.length()) {
        val result = results.optJSONObject(i) ?: continue
        lines += formatExaResultBlock(result = result, index = i, detailMode = requestMeta.detailMode)
        lines += ""
      }
    } else {
      lines += ""
      lines += "来源: 无"
    }

    val usage = response.optJSONObject("costDollars")
    if (usage != null) {
      lines += "用量:"
      lines += capText(usage.toString(), 300)
    }

    val totalLimitByMode =
      mapOf(
        "summary" to 2400,
        "light" to 4200,
        "standard" to 7600,
        "full" to 12000,
      )
    return capText(lines.joinToString("\n").trim(), totalLimitByMode[requestMeta.detailMode] ?: 4200)
  }

  private fun formatExaResultBlock(result: JSONObject, index: Int, detailMode: String): String {
    val parts = mutableListOf<String>()
    parts += "[${index + 1}] ${capText(result.optString("title").ifBlank { "Untitled" }, 120)}"

    val url = result.optString("url")
    if (url.isNotBlank()) {
      parts += "URL: $url"
    }
    val publishedDate = result.optString("publishedDate")
    if (publishedDate.isNotBlank()) {
      parts += "发布时间: $publishedDate"
    }
    val author = result.optString("author")
    if (author.isNotBlank()) {
      parts += "作者: ${capText(author, 120)}"
    }

    val summaryLimitByMode =
      mapOf(
        "summary" to 320,
        "light" to 480,
        "standard" to 720,
        "full" to 900,
      )
    val highlightLimitByMode =
      mapOf(
        "summary" to 0,
        "light" to 600,
        "standard" to 960,
        "full" to 1280,
      )
    val textLimitByMode =
      mapOf(
        "summary" to 0,
        "light" to 0,
        "standard" to 0,
        "full" to 1600,
      )

    val summary = result.optString("summary")
    if (summary.isNotBlank()) {
      parts += "摘要: ${capText(summary, summaryLimitByMode[detailMode] ?: 480)}"
    }

    val highlightLimit = highlightLimitByMode[detailMode] ?: 0
    val highlights = result.optJSONArray("highlights")
    if (highlightLimit > 0 && highlights != null && highlights.length() > 0) {
      val items = mutableListOf<String>()
      for (i in 0 until highlights.length()) {
        val value = highlights.optString(i)
        if (value.isNotBlank()) {
          items += value
        }
      }
      if (items.isNotEmpty()) {
        parts += "要点: ${capText(items.joinToString(" | "), highlightLimit)}"
      }
    }

    val textLimit = textLimitByMode[detailMode] ?: 0
    val text = result.optString("text")
    if (textLimit > 0 && text.isNotBlank()) {
      parts += "正文摘录: ${capText(text, textLimit)}"
    }

    return parts.joinToString("\n")
  }

  private fun capText(value: String, maxChars: Int): String {
    val normalized =
      value.replace(Regex("\\s+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    if (maxChars <= 0 || normalized.length <= maxChars) {
      return normalized
    }
    return normalized.take(maxChars) + "\n...[TRUNCATED]"
  }
}

private data class ExaRequestMeta(
  val query: String,
  val topic: String,
  val searchType: String,
  val detailMode: String,
  val resultCount: Int,
)

private fun appendProgressLog(
  viewModel: LlmChatViewModel,
  model: Model,
  level: LogMessageLevel,
  source: String,
  message: String,
) {
  viewModel.addLogMessageToLastCollapsableProgressPanel(
    model = model,
    logMessage = LogMessage(level = level, source = source, message = message),
  )
}

class ChatWebViewClient(val context: Context) : BaseGalleryWebViewClient(context = context) {
  private var onPageLoaded: (() -> Unit)? = null

  fun setPageLoadListener(listener: (() -> Unit)?) {
    onPageLoaded = listener
  }

  override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    Log.d(TAG, "page loaded")
    onPageLoaded?.invoke()
  }
}
