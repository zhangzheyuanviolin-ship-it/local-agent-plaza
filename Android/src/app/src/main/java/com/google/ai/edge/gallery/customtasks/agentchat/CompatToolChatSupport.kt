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
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.GalleryEvent
import com.google.ai.edge.gallery.common.AskInfoAgentAction
import com.google.ai.edge.gallery.common.CallJsAgentAction
import com.google.ai.edge.gallery.common.LOCAL_URL_BASE
import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.firebaseAnalytics
import com.google.ai.edge.gallery.ui.common.GalleryWebView
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageInfo
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWebView
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import java.lang.Exception
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

private const val TAG = "AGCompatToolChat"

data class CompatToolChatBindings(
  val onBeforeSendMessage: (Model, List<ChatMessage>) -> Unit,
  val transformOutgoingText: (Model, String) -> String,
  val onGenerateResponseDone: (Model) -> Unit,
  val onStopButtonClickedOverride: (Model) -> Unit,
  val onResetSessionClickedOverride: (Task, Model, List<ChatMessage>) -> Unit,
  val onSkillClicked: () -> Unit,
  val getActiveSkills: () -> List<String>,
  val handleSystemPromptChanged: (Task, Model, String, String) -> Unit,
  val composableBelowMessageList: @Composable (Model) -> Unit,
  val showSkillsPicker: Boolean,
)

@Composable
fun rememberCompatToolChatBindings(
  task: Task,
  curSystemPrompt: String,
  viewModel: LlmChatViewModel,
  modelManagerViewModel: ModelManagerViewModel,
  agentTools: AgentTools,
  skillManagerViewModel: SkillManagerViewModel = hiltViewModel(),
): CompatToolChatBindings {
  val context = LocalContext.current
  agentTools.context = context
  agentTools.skillManagerViewModel = skillManagerViewModel
  var showSkillManagerBottomSheet by remember { mutableStateOf(false) }
  var showAskInfoDialog by remember { mutableStateOf(false) }
  var currentAskInfoAction by remember { mutableStateOf<AskInfoAgentAction?>(null) }
  var askInfoInputValue by remember { mutableStateOf("") }
  var webViewRef: WebView? by remember { mutableStateOf(null) }
  val chatWebViewClient = remember { ChatWebViewClient(context = context) }
  val chatViewJavascriptInterface = remember { ChatWebViewJavascriptInterface() }
  val coroutineScope = rememberCoroutineScope()
  val compatToolStepsByModel = remember { mutableStateMapOf<String, Int>() }
  LaunchedEffect(Unit) { skillManagerViewModel.loadSkills {} }

  val isCompatToolsEnabledForModel: (Model) -> Boolean = { model ->
    shouldEnableCompatToolsForChat(model = model, skillManagerViewModel = skillManagerViewModel)
  }
  val buildCompatInstructionPayload = { model: Model ->
    buildCompatChatInstructionPayload(
      model = model,
      baseSystemPrompt = curSystemPrompt,
      skillManagerViewModel = skillManagerViewModel,
    )
  }

  val handleCompatError: (Model, String) -> Unit = { model, errorMessage ->
    AgentDiagnosticsLogger.log(
      context = context,
      category = "chat.error",
      message = "Compatibility chat error for model ${model.name}",
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
  val handleGenerationDone: (Model) -> Unit = handleGenerationDone@{ model ->
    val lastAgentText =
      viewModel.getLastMessageWithTypeAndSide(
        model = model,
        type = ChatMessageType.TEXT,
        side = ChatSide.AGENT,
      ) as? ChatMessageText

    if (isCompatToolsEnabledForModel(model) && lastAgentText != null) {
      val parsedToolCall = parseCompatToolCall(lastAgentText.content)
      if (parsedToolCall != null) {
        val currentSteps = compatToolStepsByModel[model.name] ?: 0
        if (currentSteps >= MAX_COMPAT_TOOL_STEPS) {
          viewModel.removeLastMessage(model = model)
          viewModel.addMessage(
            model = model,
            message =
              ChatMessageInfo(
                content = "兼容工具调用已停止：连续工具调用超过 $MAX_COMPAT_TOOL_STEPS 步。"
              ),
          )
          compatToolStepsByModel.remove(model.name)
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
                      "recovery_hint" to "检查工具参数后重试。",
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
          flushCompatToolArtifacts(viewModel = viewModel, model = model, agentTools = agentTools)
          continueCompatConversation?.invoke(
            model,
            buildCompatToolResultPrompt(
              toolName = executionResult.toolName,
              result = executionResult.result,
            ),
          )
        }
        return@handleGenerationDone
      }
    }

    compatToolStepsByModel.remove(model.name)
  }

  continueCompatConversation = { model, input ->
    val compatInstructionPayload = buildCompatInstructionPayload(model)
    viewModel.generateResponse(
      model = model,
      input =
        if (isCompatToolsEnabledForModel(model) && compatInstructionPayload.isNotBlank()) {
          buildCompatContinuationInput(
            continuationPayload = input,
            compatInstructionPayload = compatInstructionPayload,
          )
        } else {
          input
        },
      onDone = { handleGenerationDone(model) },
      onError = { errorMessage -> handleCompatError(model, errorMessage) },
      allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
    )
  }

  val handleSystemPromptChanged: (Task, Model, String, String) -> Unit =
    { changedTask, model, newPrompt, systemPromptUpdatedMessage ->
      if (isCompatToolsEnabledForModel(model)) {
        viewModel.persistSystemPrompt(task = changedTask, newPrompt = newPrompt)
        compatToolStepsByModel.remove(model.name)
        viewModel.resetSession(
          task = changedTask,
          model = model,
          systemInstruction = null,
          supportImage = false,
          supportAudio = false,
          onDone = {
            viewModel.addMessage(model, ChatMessageInfo(content = systemPromptUpdatedMessage))
          },
        )
      } else {
        viewModel.applySystemPromptChange(
          task = changedTask,
          model = model,
          newPrompt = newPrompt,
          systemPromptUpdatedMessage = systemPromptUpdatedMessage,
        )
      }
    }

  return CompatToolChatBindings(
    onBeforeSendMessage = { model, _ -> compatToolStepsByModel.remove(model.name) },
    transformOutgoingText = { model, text ->
      val compatInstructionPayload = buildCompatInstructionPayload(model)
      if (isCompatToolsEnabledForModel(model) && compatInstructionPayload.isNotBlank()) {
        buildCompatUserInput(
          userInput = text,
          compatInstructionPayload = compatInstructionPayload,
        )
      } else {
        text
      }
    },
    onGenerateResponseDone = handleGenerationDone,
    onStopButtonClickedOverride = { model ->
      compatToolStepsByModel.remove(model.name)
      viewModel.stopResponse(model = model)
      viewModel.addMessage(model = model, message = ChatMessageInfo(content = "已手动停止当前对话。"))
    },
    onResetSessionClickedOverride = { resetTask, model, initialMessages ->
      compatToolStepsByModel.remove(model.name)
      val litertMessages =
        initialMessages.mapNotNull { chatMessage ->
          if (chatMessage is ChatMessageText) {
            if (chatMessage.side == ChatSide.USER) {
              Message.user(chatMessage.content)
            } else {
              Message.model(chatMessage.content)
            }
          } else {
            null
          }
        }
      viewModel.resetSession(
        task = resetTask,
        model = model,
        systemInstruction =
          if (isCompatToolsEnabledForModel(model)) {
            null
          } else {
            Contents.of(curSystemPrompt)
          },
        supportImage = false,
        supportAudio = false,
        initialMessages = litertMessages,
      )
    },
    onSkillClicked = { showSkillManagerBottomSheet = true },
    getActiveSkills = {
      if (!skillManagerViewModel.uiState.value.loading && isCompatToolsEnabledForModel(modelManagerViewModel.uiState.value.selectedModel)) {
        skillManagerViewModel.getSelectedSkills().map { skillManagerViewModel.getSkillShortId(it) }
      } else {
        emptyList()
      }
    },
    handleSystemPromptChanged = handleSystemPromptChanged,
    composableBelowMessageList = { model ->
      val currentModel by rememberUpdatedState(model)
      val actionChannel = agentTools.actionChannel
      LaunchedEffect(actionChannel) {
        for (action in actionChannel) {
          when (action) {
            is SkillProgressAgentAction -> {
              val infoText =
                buildString {
                  append(action.label)
                  if (action.addItemDescription.isNotBlank()) {
                    append("\n")
                    append(action.addItemDescription)
                  }
                }
              viewModel.addMessage(
                model = currentModel,
                message = ChatMessageInfo(content = infoText),
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
              try {
                suspendCancellableCoroutine<Unit> { continuation ->
                  chatWebViewClient.setPageLoadListener {
                    chatWebViewClient.setPageLoadListener(null)
                    continuation.resume(Unit)
                  }
                  webViewRef?.loadUrl(action.url)
                }

                chatViewJavascriptInterface.onResultListener = { result ->
                  action.result.complete(result)
                  val isSuccess = !result.contains("\"error\":")
                  firebaseAnalytics?.logEvent(
                    GalleryEvent.SKILL_EXECUTION.id,
                    Bundle().apply {
                      putString("skill_name", skillName)
                      putString("skill_id", skillName)
                      putBoolean("success", isSuccess)
                      putString("error_type", if (isSuccess) "" else "js_error")
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
                webViewRef?.evaluateJavascript(script, null)
              } catch (e: Exception) {
                Log.e(TAG, "JS skill execution failed", e)
                action.result.completeExceptionally(e)
              }
            }
            is AskInfoAgentAction -> {
              currentAskInfoAction = action
              askInfoInputValue = ""
              showAskInfoDialog = true
            }
          }
        }
      }

      GalleryWebView(
        modifier = Modifier.size(1.dp),
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
          handleCompatToolConsoleMessage(
            context = context,
            consoleMessage = consoleMessage,
          )
        },
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
            showSkillManagerBottomSheet = false
            if (selectedSkillsChanged) {
              val selectedModel = modelManagerViewModel.uiState.value.selectedModel
              compatToolStepsByModel.remove(selectedModel.name)
              viewModel.resetSession(
                task = task,
                model = selectedModel,
                systemInstruction =
                  if (isCompatToolsEnabledForModel(selectedModel)) {
                    null
                  } else {
                    Contents.of(curSystemPrompt)
                  },
                supportImage = false,
                supportAudio = false,
              )
            }
          },
        )
      }
    },
    showSkillsPicker = true,
  )
}

private fun handleCompatToolConsoleMessage(
  context: Context,
  consoleMessage: ConsoleMessage?,
) {
  consoleMessage?.let { curConsoleMessage ->
    AgentDiagnosticsLogger.log(
      context = context,
      category = "chat.webview_console",
      message = curConsoleMessage.messageLevel().name,
      detail =
        "${curConsoleMessage.sourceId()}:${curConsoleMessage.lineNumber()} ${curConsoleMessage.message()}",
    )
  }
}

private fun flushCompatToolArtifacts(
  viewModel: LlmChatViewModel,
  model: Model,
  agentTools: AgentTools,
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
              maxSize = 320.dp.value.toInt(),
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
