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

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatPerformanceRegistry
import com.google.ai.edge.gallery.ui.llmchat.LlmRuntimePerformanceSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val DIAGNOSTICS_SCHEMA = "mcp202.agent_perf.v1"
private const val LITERT_LM_VERSION = "0.15.0"
private const val LITERT_VERSION = "2.1.6"

private data class InferencePassTiming(
  val index: Int,
  val kind: String,
  val inputChars: Int,
  val submitNanos: Long,
  var firstTokenNanos: Long? = null,
  var doneNanos: Long? = null,
  var outputChars: Int = 0,
)

private data class ToolExecutionTiming(
  val index: Int,
  val toolName: String,
  val elapsedMs: Double,
  val resultChars: Int,
  val success: Boolean,
)

private data class MemorySnapshot(
  val stage: String,
  val pssKb: Long,
  val javaHeapUsedBytes: Long,
  val nativeHeapAllocatedBytes: Long,
)

/**
 * Per-request MCP202 Agent performance trace.
 *
 * Privacy rule: this class stores lengths and timings only. It never stores user prompts, tool
 * arguments, tool results, workspace file contents, secrets, API keys, or access tokens.
 */
class AgentPerformanceTrace(
  private val context: Context,
  private val model: Model,
  private val toolMode: String,
  private val originalInputChars: Int,
  private val activeSkillCount: Int,
  private val enabledMcpCount: Int,
) {
  private val requestId = UUID.randomUUID().toString().substring(0, 8)
  private val startedAtWallMillis = System.currentTimeMillis()
  private val startedAtNanos = SystemClock.elapsedRealtimeNanos()
  private val runtimeAtStart: LlmRuntimePerformanceSnapshot? =
    LlmChatPerformanceRegistry.snapshot(model.name)
  private val passes = mutableListOf<InferencePassTiming>()
  private val tools = mutableListOf<ToolExecutionTiming>()
  private val memory = mutableListOf<MemorySnapshot>()
  private var compatPrefaceChars: Int? = null
  private var toolParseAttempts = 0
  private var toolParseTotalMs = 0.0
  private var retryCount = 0
  private var finishedAtNanos: Long? = null
  private var finalStatus = "RUNNING"
  private var errorChars = 0
  private var finalRuntimeSnapshot: LlmRuntimePerformanceSnapshot? = null

  private val configuredContextWindow = runCatching { model.getConfiguredContextWindow() }.getOrNull()
  private val maxOutputTokens =
    runCatching {
        model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
      }
      .getOrNull()
  private val topK =
    runCatching { model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK) }
      .getOrNull()
  private val topP =
    runCatching { model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP) }
      .getOrNull()
  private val temperature =
    runCatching {
        model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
      }
      .getOrNull()
  private val accelerator =
    runCatching {
        model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.GPU.label)
      }
      .getOrNull()
  private val modelFileBytes =
    runCatching {
        val path = model.getPath(context.applicationContext)
        File(path).takeIf { it.isFile }?.length()
      }
      .getOrNull()
  private val memoryClassMb =
    (context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
      ?.memoryClass

  init {
    captureMemory("request_start")
  }

  @Synchronized
  fun markInitialInferenceSubmitted(inputChars: Int, prefaceChars: Int?) {
    compatPrefaceChars = prefaceChars
    if (passes.isEmpty()) {
      passes +=
        InferencePassTiming(
          index = 1,
          kind = "initial",
          inputChars = inputChars,
          submitNanos = SystemClock.elapsedRealtimeNanos(),
        )
    }
  }

  @Synchronized
  fun markContinuationSubmitted(inputChars: Int) {
    passes +=
      InferencePassTiming(
        index = passes.size + 1,
        kind = "continuation",
        inputChars = inputChars,
        submitNanos = SystemClock.elapsedRealtimeNanos(),
      )
  }

  @Synchronized
  fun markFirstToken() {
    val pass = ensureOpenPass()
    if (pass.firstTokenNanos == null) {
      pass.firstTokenNanos = SystemClock.elapsedRealtimeNanos()
      captureMemory("first_token_${pass.index}")
    }
  }

  @Synchronized
  fun markGenerationDone(outputChars: Int) {
    val pass = ensureOpenPass()
    if (pass.doneNanos == null) {
      pass.doneNanos = SystemClock.elapsedRealtimeNanos()
      pass.outputChars = outputChars
    }
  }

  @Synchronized
  fun recordToolParse(elapsedMs: Double) {
    toolParseAttempts += 1
    toolParseTotalMs += elapsedMs.coerceAtLeast(0.0)
  }

  @Synchronized
  fun recordToolExecution(toolName: String, elapsedMs: Double, resultChars: Int, success: Boolean) {
    tools +=
      ToolExecutionTiming(
        index = tools.size + 1,
        toolName = sanitizeToolName(toolName),
        elapsedMs = elapsedMs.coerceAtLeast(0.0),
        resultChars = resultChars.coerceAtLeast(0),
        success = success,
      )
    captureMemory("after_tool_${tools.size}")
  }

  @Synchronized fun recordRetry() { retryCount += 1 }

  @Synchronized
  fun finish(status: String, errorLength: Int = 0) {
    if (finishedAtNanos == null || finalStatus == "RUNNING") {
      finishedAtNanos = SystemClock.elapsedRealtimeNanos()
    }
    finalStatus = status
    errorChars = errorLength.coerceAtLeast(0)
    finalRuntimeSnapshot = LlmChatPerformanceRegistry.snapshot(model.name)
    captureMemory("final")
  }

  @Synchronized
  fun buildReport(): String {
    val runtimeNow = finalRuntimeSnapshot ?: LlmChatPerformanceRegistry.snapshot(model.name)
    val finishNanos = finishedAtNanos ?: SystemClock.elapsedRealtimeNanos()
    val totalMs = nanosToMs(finishNanos - startedAtNanos)
    val initialPass = passes.firstOrNull()
    val continuationPasses = passes.drop(1)
    val totalToolExecMs = tools.sumOf { it.elapsedMs }
    val toolResultChars = tools.sumOf { it.resultChars }
    val resetDelta =
      if (runtimeNow != null && runtimeAtStart != null) {
        (runtimeNow.conversationResetCount - runtimeAtStart.conversationResetCount).coerceAtLeast(0)
      } else {
        null
      }

    return buildString {
      appendLine("=== MCP202 Agent 性能诊断 ===")
      appendLine("schema=$DIAGNOSTICS_SCHEMA")
      appendLine("request_id=$requestId")
      appendLine("status=$finalStatus")
      appendLine("started_at=${formatWallTime(startedAtWallMillis)}")
      appendLine("app_version=${BuildConfig.VERSION_NAME}")
      appendLine("model_name=${model.name}")
      appendLine("tool_mode=$toolMode")
      appendLine("litert_lm_version=$LITERT_LM_VERSION")
      appendLine("litert_version=$LITERT_VERSION")
      appendLine("android_sdk=${Build.VERSION.SDK_INT}")
      appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
      appendLine("available_processors=${Runtime.getRuntime().availableProcessors()}")
      appendLine("memory_class_mb=${valueOrUnavailable(memoryClassMb)}")
      appendLine("model_file_bytes=${valueOrUnavailable(modelFileBytes)}")
      appendLine("configured_context_window=${valueOrUnavailable(configuredContextWindow)}")
      appendLine("max_output_tokens=${valueOrUnavailable(maxOutputTokens)}")
      appendLine("top_k=${valueOrUnavailable(topK)}")
      appendLine("top_p=${floatOrUnavailable(topP)}")
      appendLine("temperature=${floatOrUnavailable(temperature)}")
      appendLine("accelerator=${accelerator ?: "unavailable"}")
      appendLine("active_skill_count=$activeSkillCount")
      appendLine("enabled_mcp_count=$enabledMcpCount")
      appendLine("original_input_chars=$originalInputChars")
      appendLine("compat_static_preface_chars=${valueOrUnavailable(compatPrefaceChars)}")
      appendLine("thinking_override=${if (toolMode == "COMPAT") "disabled" else "runtime_default"}")
      appendLine()

      appendLine("[runtime_initialization]")
      appendLine("engine_init_ms=${msOrUnavailable(runtimeNow?.engineInitMs)}")
      appendLine("conversation_init_ms=${msOrUnavailable(runtimeNow?.conversationInitMs)}")
      appendLine("last_conversation_reset_ms=${msOrUnavailable(runtimeNow?.lastConversationResetMs)}")
      appendLine("conversation_reset_count_total=${valueOrUnavailable(runtimeNow?.conversationResetCount)}")
      appendLine("conversation_resets_during_request=${valueOrUnavailable(resetDelta)}")
      appendLine("engine_max_num_tokens=${valueOrUnavailable(runtimeNow?.engineMaxNumTokens)}")
      appendLine("runtime_max_output_tokens=${valueOrUnavailable(runtimeNow?.maxOutputTokens)}")
      appendLine("runtime_accelerator=${runtimeNow?.accelerator ?: "unavailable"}")
      appendLine(
        "speculative_decoding=${runtimeNow?.speculativeDecodingEnabled?.toString() ?: "unavailable"}"
      )
      appendLine()

      appendLine("[request_timing]")
      appendLine("total_ms=${formatMs(totalMs)}")
      appendLine("llm_pass_count=${passes.size}")
      appendLine("continuation_count=${continuationPasses.size}")
      appendLine("initial_input_chars=${valueOrUnavailable(initialPass?.inputChars)}")
      appendLine("initial_ttft_ms=${passTtft(initialPass)}")
      appendLine("initial_decode_after_first_token_ms=${passDecode(initialPass)}")
      appendLine("initial_total_generation_ms=${passTotal(initialPass)}")
      for (pass in continuationPasses) {
        val continuationIndex = pass.index - 1
        appendLine("continuation_${continuationIndex}_input_chars=${pass.inputChars}")
        appendLine("continuation_${continuationIndex}_ttft_ms=${passTtft(pass)}")
        appendLine(
          "continuation_${continuationIndex}_decode_after_first_token_ms=${passDecode(pass)}"
        )
        appendLine("continuation_${continuationIndex}_total_generation_ms=${passTotal(pass)}")
        appendLine("continuation_${continuationIndex}_output_chars=${pass.outputChars}")
        val previousPass = passes.getOrNull(pass.index - 2)
        appendLine(
          "before_continuation_${continuationIndex}_gap_ms=${interPassGap(previousPass, pass)}"
        )
      }
      appendLine("retry_count=$retryCount")
      appendLine("error_chars=$errorChars")
      appendLine()

      appendLine("[tool_timing]")
      appendLine("tool_parse_attempts=$toolParseAttempts")
      appendLine("tool_parse_total_ms=${formatMs(toolParseTotalMs)}")
      appendLine("tool_call_count=${tools.size}")
      appendLine("tool_exec_total_ms=${formatMs(totalToolExecMs)}")
      appendLine("tool_result_chars_total=$toolResultChars")
      if (tools.isEmpty()) {
        appendLine("tool_detail=none_or_native_provider_not_observable")
      } else {
        for (tool in tools) {
          appendLine("tool_${tool.index}_name=${tool.toolName}")
          appendLine("tool_${tool.index}_exec_ms=${formatMs(tool.elapsedMs)}")
          appendLine("tool_${tool.index}_result_chars=${tool.resultChars}")
          appendLine("tool_${tool.index}_success=${tool.success}")
        }
      }
      appendLine()

      appendLine("[memory_snapshots]")
      if (memory.isEmpty()) {
        appendLine("memory=unavailable")
      } else {
        for (snapshot in memory) {
          appendLine("${snapshot.stage}.pss_mb=${kbToMb(snapshot.pssKb)}")
          appendLine(
            "${snapshot.stage}.java_heap_used_mb=${bytesToMb(snapshot.javaHeapUsedBytes)}"
          )
          appendLine(
            "${snapshot.stage}.native_heap_allocated_mb=${bytesToMb(snapshot.nativeHeapAllocatedBytes)}"
          )
        }
      }
      appendLine()
      appendLine("privacy=user_prompt_not_logged;tool_arguments_not_logged;tool_result_content_not_logged;secrets_not_logged")
      append("=== MCP202 Agent 性能诊断结束 ===")
    }
  }

  private fun ensureOpenPass(): InferencePassTiming {
    return passes.lastOrNull { it.doneNanos == null }
      ?: InferencePassTiming(
          index = passes.size + 1,
          kind = if (passes.isEmpty()) "initial" else "continuation_unmarked",
          inputChars = 0,
          submitNanos = SystemClock.elapsedRealtimeNanos(),
        )
        .also { passes += it }
  }

  private fun captureMemory(stage: String) {
    val runtime = Runtime.getRuntime()
    memory +=
      MemorySnapshot(
        stage = stage,
        pssKb = Debug.getPss().toLong(),
        javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
        nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
      )
  }

  private fun passTtft(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstTokenNanos ?: return "unavailable"
    return formatMs(nanosToMs(first - pass.submitNanos))
  }

  private fun passDecode(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstTokenNanos ?: return "unavailable"
    val done = pass.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs(done - first))
  }

  private fun passTotal(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val done = pass.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs(done - pass.submitNanos))
  }

  private fun interPassGap(previous: InferencePassTiming?, current: InferencePassTiming): String {
    val previousDone = previous?.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs(current.submitNanos - previousDone))
  }

  private fun nanosToMs(nanos: Long): Double = nanos.coerceAtLeast(0L) / 1_000_000.0

  private fun formatMs(value: Double): String = String.format(Locale.US, "%.2f", value)

  private fun msOrUnavailable(value: Double?): String = value?.let { formatMs(it) } ?: "unavailable"

  private fun floatOrUnavailable(value: Float?): String =
    value?.let { String.format(Locale.US, "%.4f", it) } ?: "unavailable"

  private fun valueOrUnavailable(value: Any?): String = value?.toString() ?: "unavailable"

  private fun kbToMb(kb: Long): String = String.format(Locale.US, "%.2f", kb / 1024.0)

  private fun bytesToMb(bytes: Long): String =
    String.format(Locale.US, "%.2f", bytes / (1024.0 * 1024.0))

  private fun formatWallTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(epochMillis))

  private fun sanitizeToolName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(120)
}

@Composable
fun AgentPerformanceDiagnosticsPanel(reportText: String, modifier: Modifier = Modifier) {
  if (reportText.isBlank()) return
  val context = androidx.compose.ui.platform.LocalContext.current
  var expanded by remember(reportText) { mutableStateOf(false) }
  var copied by remember(reportText) { mutableStateOf(false) }

  Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledTonalButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(if (expanded) "MCP202 性能诊断：点击收起" else "MCP202 性能诊断：点击展开")
      }

      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "下面整段内容可以直接复制发送，用于定位 Agent 各阶段性能。",
            style = MaterialTheme.typography.bodyMedium,
          )
          SelectionContainer {
            Text(
              text = reportText,
              fontFamily = FontFamily.Monospace,
              fontSize = 12.sp,
              lineHeight = 17.sp,
            )
          }
          Button(
            onClick = {
              val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
              clipboard.setPrimaryClip(
                ClipData.newPlainText("MCP202 Agent Performance Diagnostics", reportText)
              )
              copied = true
            },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(if (copied) "已复制全部诊断数据" else "复制全部诊断数据")
          }
        }
      }
    }
  }
}
