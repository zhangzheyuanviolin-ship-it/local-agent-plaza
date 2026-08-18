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
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import java.util.concurrent.Executors

private const val DIAGNOSTICS_SCHEMA = "mcp211.agent_perf.v6"
private const val SESSION_DIAGNOSTICS_SCHEMA = "mcp211.agent_session_perf.v4"
private const val LITERT_LM_VERSION = "0.15.0"
private const val LITERT_VERSION = "2.1.6"
private const val FINALIZATION_DELAY_MS = 1200L
private const val FINAL_MEMORY_REFRESH_DELAY_MS = 500L
private const val TOOL_EVENT_WINDOW_MS = 5000L

private data class LiteRtNativeBenchmark(
  val initTimeMs: Double?,
  val nativeTtftMs: Double?,
  val prefillTokenCount: Int?,
  val decodeTokenCount: Int?,
  val prefillTokensPerSecond: Double?,
  val decodeTokensPerSecond: Double?,
  val kvTokenCount: Int?,
  val benchmarkReadMs: Double,
  val errorClass: String?,
)

private data class InferencePassTiming(
  val index: Int,
  val kind: String,
  val inputChars: Int,
  val submitNanos: Long,
  val thinkingOverride: String,
  var firstCallbackNanos: Long? = null,
  var firstVisibleNanos: Long? = null,
  var firstThoughtNanos: Long? = null,
  var lastVisibleNanos: Long? = null,
  var lastThoughtNanos: Long? = null,
  var callbackCount: Int = 0,
  var visibleCallbackCount: Int = 0,
  var thoughtCallbackCount: Int = 0,
  var visibleChars: Int = 0,
  var thoughtChars: Int = 0,
  var doneNanos: Long? = null,
  var outputChars: Int = 0,
  var nativeBenchmark: LiteRtNativeBenchmark? = null,
)

private data class ToolExecutionTiming(
  val index: Int,
  val toolName: String,
  val elapsedMs: Double,
  val loggedDetailChars: Int,
  val success: Boolean,
  val postGenerationGapMs: Double?,
)

private data class MemorySnapshot(
  val stage: String,
  val pssKb: Long,
  val javaHeapUsedBytes: Long,
  val nativeHeapAllocatedBytes: Long,
  val sampleDelayMs: Double,
)

/**
 * Per-request MCP211 Agent trace. User prompts, tool arguments/results, paths and secrets are never
 * stored. MCP211 adds LiteRT-LM native benchmark counters after each pass has already completed so
 * benchmark JNI reads cannot inflate app-observed TTFT or decode timing.
 */
class AgentPerformanceTrace(
  private val context: Context,
  private val model: Model,
  private val toolMode: String,
  private val originalInputChars: Int,
  private val runtimeInputChars: Int,
  private val compatAddedInputChars: Int?,
  private val activeSkillCount: Int,
  private val enabledMcpCount: Int,
) {
  companion object {
    private val memorySampler =
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AgentPerfMemorySampler").apply { isDaemon = true }
      }
  }

  private val requestId = UUID.randomUUID().toString().substring(0, 8)
  private val startedAtWallMillis = System.currentTimeMillis()
  private val startedAtNanos = SystemClock.elapsedRealtimeNanos()
  private val runtimeAtStart: LlmRuntimePerformanceSnapshot? =
    LlmChatPerformanceRegistry.snapshot(model.name)
  private val passes = mutableListOf<InferencePassTiming>()
  private val tools = mutableListOf<ToolExecutionTiming>()
  private val memory = mutableListOf<MemorySnapshot>()
  private var retryCount = 0
  private var finishedAtNanos: Long? = null
  private var finalStatus = "RUNNING"
  private var errorChars = 0
  private var finalRuntimeSnapshot: LlmRuntimePerformanceSnapshot? = null
  private var finalMemoryCaptureScheduled = false

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
    captureMemoryAsync("request_start")
  }

  @Synchronized
  fun markInferenceSubmitted(inputChars: Int, thinkingOverride: String) {
    reopen()
    val kind = if (passes.isEmpty()) "initial" else "continuation"
    passes +=
      InferencePassTiming(
        index = passes.size + 1,
        kind = kind,
        inputChars = inputChars,
        submitNanos = SystemClock.elapsedRealtimeNanos(),
        thinkingOverride = thinkingOverride,
      )
  }

  @Synchronized
  fun markStreamChunk(visibleChars: Int, thoughtChars: Int) {
    val pass = ensureOpenPass()
    val now = SystemClock.elapsedRealtimeNanos()
    pass.callbackCount += 1
    if (pass.firstCallbackNanos == null) {
      pass.firstCallbackNanos = now
      captureMemoryAsync("first_callback_${pass.index}")
    }
    if (visibleChars > 0) {
      pass.visibleCallbackCount += 1
      pass.visibleChars += visibleChars
      if (pass.firstVisibleNanos == null) pass.firstVisibleNanos = now
      pass.lastVisibleNanos = now
    }
    if (thoughtChars > 0) {
      pass.thoughtCallbackCount += 1
      pass.thoughtChars += thoughtChars
      if (pass.firstThoughtNanos == null) pass.firstThoughtNanos = now
      pass.lastThoughtNanos = now
    }
  }

  @Synchronized
  fun markLegacyFirstCallback(): Boolean {
    val pass = ensureOpenPass()
    if (pass.firstCallbackNanos != null) return false
    pass.firstCallbackNanos = SystemClock.elapsedRealtimeNanos()
    captureMemoryAsync("first_callback_${pass.index}")
    return true
  }

  @Synchronized
  fun markGenerationDone(outputChars: Int): Long {
    val pass = ensureOpenPass()
    if (pass.doneNanos == null) {
      pass.doneNanos = SystemClock.elapsedRealtimeNanos()
      pass.outputChars = outputChars.coerceAtLeast(0)
    }
    return pass.doneNanos ?: SystemClock.elapsedRealtimeNanos()
  }

  @Synchronized
  fun recordLiteRtNativeBenchmark(
    initTimeMs: Double?,
    nativeTtftMs: Double?,
    prefillTokenCount: Int?,
    decodeTokenCount: Int?,
    prefillTokensPerSecond: Double?,
    decodeTokensPerSecond: Double?,
    kvTokenCount: Int?,
    benchmarkReadMs: Double,
    errorClass: String?,
  ) {
    val pass = passes.lastOrNull() ?: return
    pass.nativeBenchmark =
      LiteRtNativeBenchmark(
        initTimeMs = initTimeMs,
        nativeTtftMs = nativeTtftMs,
        prefillTokenCount = prefillTokenCount,
        decodeTokenCount = decodeTokenCount,
        prefillTokensPerSecond = prefillTokensPerSecond,
        decodeTokensPerSecond = decodeTokensPerSecond,
        kvTokenCount = kvTokenCount,
        benchmarkReadMs = benchmarkReadMs.coerceAtLeast(0.0),
        errorClass = errorClass?.replace(Regex("[^A-Za-z0-9_.-]"), "_")?.take(120),
      )
  }

  @Synchronized
  fun recordToolExecution(
    toolName: String,
    startNanos: Long,
    endNanos: Long,
    loggedDetailChars: Int,
    success: Boolean,
  ) {
    reopen()
    val previousGenerationDone = passes.lastOrNull()?.doneNanos
    val postGenerationGapMs =
      previousGenerationDone?.let { nanosToMs((startNanos - it).coerceAtLeast(0L)) }
    tools +=
      ToolExecutionTiming(
        index = tools.size + 1,
        toolName = sanitizeToolName(toolName),
        elapsedMs = nanosToMs((endNanos - startNanos).coerceAtLeast(0L)),
        loggedDetailChars = loggedDetailChars.coerceAtLeast(0),
        success = success,
        postGenerationGapMs = postGenerationGapMs,
      )
    captureMemoryAsync("after_tool_${tools.size}")
  }

  @Synchronized
  fun recordRetry() {
    retryCount += 1
  }

  @Synchronized
  fun reopen() {
    finishedAtNanos = null
    finalStatus = "RUNNING"
    finalRuntimeSnapshot = null
  }

  @Synchronized
  fun finish(
    status: String,
    errorLength: Int = 0,
    atNanos: Long? = null,
    captureFinalMemory: Boolean = true,
  ) {
    finishedAtNanos =
      atNanos ?: passes.lastOrNull()?.doneNanos ?: SystemClock.elapsedRealtimeNanos()
    finalStatus = status
    errorChars = errorLength.coerceAtLeast(0)
    finalRuntimeSnapshot = LlmChatPerformanceRegistry.snapshot(model.name)
    if (captureFinalMemory && !finalMemoryCaptureScheduled) {
      finalMemoryCaptureScheduled = true
      captureMemoryAsync("final")
    }
  }

  @Synchronized
  fun buildReport(): String {
    val runtimeNow = finalRuntimeSnapshot ?: LlmChatPerformanceRegistry.snapshot(model.name)
    val compatRuntime = AgentCompatRuntimeCoordinator.snapshot(model.name)
    val finishNanos = finishedAtNanos ?: SystemClock.elapsedRealtimeNanos()
    val totalMs = nanosToMs((finishNanos - startedAtNanos).coerceAtLeast(0L))
    val initialPass = passes.firstOrNull()
    val continuationPasses = passes.drop(1)
    val totalToolExecMs = tools.sumOf { it.elapsedMs }
    val totalLoggedDetailChars = tools.sumOf { it.loggedDetailChars }
    val resetDelta =
      if (runtimeNow != null && runtimeAtStart != null) {
        (runtimeNow.conversationResetCount - runtimeAtStart.conversationResetCount).coerceAtLeast(0)
      } else {
        null
      }

    return buildString {
      appendLine("=== MCP211 Agent 性能诊断 ===")
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
      appendLine("conversation_max_output_override=none")
      appendLine("top_k=${valueOrUnavailable(topK)}")
      appendLine("top_p=${floatOrUnavailable(topP)}")
      appendLine("temperature=${floatOrUnavailable(temperature)}")
      appendLine("accelerator=${accelerator ?: "unavailable"}")
      appendLine("active_skill_count=$activeSkillCount")
      appendLine("enabled_mcp_count=$enabledMcpCount")
      appendLine("original_input_chars=$originalInputChars")
      appendLine("runtime_input_chars=$runtimeInputChars")
      appendLine("compat_added_input_chars=${valueOrUnavailable(compatAddedInputChars)}")
      appendLine("thinking_override=per_pass;see_stream_channels")
      appendLine("native_benchmark_enabled=true_for_agent_engine")
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

      appendLine("[mcp211_runtime]")
      appendLine("session_id=${compatRuntime?.sessionId ?: "unavailable"}")
      appendLine("user_turn_index=${valueOrUnavailable(compatRuntime?.userTurnIndex)}")
      appendLine("session_history_turn_count=${valueOrUnavailable(compatRuntime?.sessionHistoryTurnCount)}")
      appendLine("session_history_chars=${valueOrUnavailable(compatRuntime?.sessionHistoryChars)}")
      appendLine("session_completed_turn_count=${valueOrUnavailable(compatRuntime?.sessionCompletedTurnCount)}")
      appendLine("conversation_generation_id=${valueOrUnavailable(compatRuntime?.conversationGenerationId)}")
      appendLine("last_fresh_conversation_reason=${compatRuntime?.lastFreshConversationReason ?: "unavailable"}")
      appendLine("top_level_reset_count=${valueOrUnavailable(compatRuntime?.topLevelResetCount)}")
      appendLine("top_level_prepare_ms_total=${msOrUnavailable(compatRuntime?.topLevelPrepareMsTotal)}")
      appendLine("last_top_level_prepare_ms=${msOrUnavailable(compatRuntime?.lastTopLevelPrepareMs)}")
      appendLine("last_top_level_reset_ms=${msOrUnavailable(compatRuntime?.lastTopLevelResetMs)}")
      appendLine("top_level_raw_input_chars=${valueOrUnavailable(compatRuntime?.topLevelRawInputChars)}")
      appendLine("top_level_effective_input_chars=${valueOrUnavailable(compatRuntime?.topLevelEffectiveInputChars)}")
      appendLine("pre_submit_wait_ms_total=${msOrUnavailable(compatRuntime?.preSubmitWaitMsTotal)}")
      appendLine("continuation_prepare_ms_total=${msOrUnavailable(compatRuntime?.continuationPrepareMsTotal)}")
      appendLine("last_continuation_prepare_ms=${msOrUnavailable(compatRuntime?.lastContinuationPrepareMs)}")
      appendLine("last_continuation_reset_ms=${msOrUnavailable(compatRuntime?.lastContinuationResetMs)}")
      appendLine("continuation_raw_input_chars=${valueOrUnavailable(compatRuntime?.continuationRawInputChars)}")
      appendLine("continuation_effective_input_chars=${valueOrUnavailable(compatRuntime?.continuationEffectiveInputChars)}")
      appendLine("continuation_session_history_included=false")
      appendLine("compat_history_step_count=${valueOrUnavailable(compatRuntime?.historyStepCount)}")
      appendLine("compat_history_chars=${valueOrUnavailable(compatRuntime?.historyChars)}")
      appendLine("repeated_tool_call_count=${valueOrUnavailable(compatRuntime?.repeatedToolCallCount)}")
      appendLine("tool_result_prompt_build_ms=unavailable")
      appendLine("audit_write_ms=unavailable")
      appendLine("diagnostic_memory_async=true")
      appendLine()

      appendLine("[request_timing]")
      appendLine("total_ms=${formatMs(totalMs)}")
      appendLine("llm_pass_count=${passes.size}")
      appendLine("continuation_count=${continuationPasses.size}")
      appendLine("initial_input_chars=${valueOrUnavailable(initialPass?.inputChars)}")
      appendLine("initial_ttft_ms=${passFirstCallbackTtft(initialPass)}")
      appendLine("initial_decode_after_first_token_ms=${passDecodeFromFirstCallback(initialPass)}")
      appendLine("initial_total_generation_ms=${passTotal(initialPass)}")
      appendLine("initial_output_chars=${valueOrUnavailable(initialPass?.outputChars)}")
      for (pass in continuationPasses) {
        val continuationIndex = pass.index - 1
        appendLine("continuation_${continuationIndex}_input_chars=${pass.inputChars}")
        appendLine("continuation_${continuationIndex}_ttft_ms=${passFirstCallbackTtft(pass)}")
        appendLine("continuation_${continuationIndex}_decode_after_first_token_ms=${passDecodeFromFirstCallback(pass)}")
        appendLine("continuation_${continuationIndex}_total_generation_ms=${passTotal(pass)}")
        appendLine("continuation_${continuationIndex}_output_chars=${pass.outputChars}")
        val previousPass = passes.getOrNull(pass.index - 2)
        appendLine("before_continuation_${continuationIndex}_gap_ms=${interPassGap(previousPass, pass)}")
      }
      appendLine("retry_count=$retryCount")
      appendLine("error_chars=$errorChars")
      appendLine()

      appendLine("[stream_channels]")
      appendStreamPass(this, "initial", initialPass)
      for (pass in continuationPasses) {
        appendStreamPass(this, "continuation_${pass.index - 1}", pass)
      }
      appendLine()

      appendLine("[litert_native_benchmark]")
      appendLine("capture_phase=after_generation_done_before_tool_orchestration")
      appendLine("benchmark_read_excluded_from_app_generation_timing=true")
      appendNativeBenchmarkPass(this, "initial", initialPass)
      for (pass in continuationPasses) {
        appendNativeBenchmarkPass(this, "continuation_${pass.index - 1}", pass)
      }
      appendLine()

      appendLine("[tool_timing]")
      appendLine("compat_parser_exact_ms=unavailable")
      appendLine("compat_parser_note=post_generation_to_tool_start_ms includes parser, benchmark-read, and orchestration overhead")
      appendLine("observed_tool_event_count=${tools.size}")
      appendLine("observed_tool_exec_total_ms=${formatMs(totalToolExecMs)}")
      appendLine("observed_tool_logged_detail_chars_total=$totalLoggedDetailChars")
      if (tools.isEmpty()) {
        appendLine("tool_detail=none_observed")
      } else {
        for (tool in tools) {
          appendLine("tool_${tool.index}_name=${tool.toolName}")
          appendLine("tool_${tool.index}_exec_ms=${formatMs(tool.elapsedMs)}")
          appendLine("tool_${tool.index}_post_generation_to_start_ms=${msOrUnavailable(tool.postGenerationGapMs)}")
          appendLine("tool_${tool.index}_logged_detail_chars=${tool.loggedDetailChars}")
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
          appendLine("${snapshot.stage}.java_heap_used_mb=${bytesToMb(snapshot.javaHeapUsedBytes)}")
          appendLine("${snapshot.stage}.native_heap_allocated_mb=${bytesToMb(snapshot.nativeHeapAllocatedBytes)}")
          appendLine("${snapshot.stage}.sample_delay_ms=${formatMs(snapshot.sampleDelayMs)}")
        }
      }
      appendLine()
      appendLine("privacy=user_prompt_not_logged;tool_arguments_not_logged;tool_result_content_not_logged;workspace_paths_not_logged;secrets_not_logged")
      append("=== MCP211 Agent 性能诊断结束 ===")
    }
  }

  private fun appendStreamPass(builder: StringBuilder, prefix: String, pass: InferencePassTiming?) {
    builder.appendLine("${prefix}_thinking_override=${pass?.thinkingOverride ?: "unavailable"}")
    builder.appendLine("${prefix}_first_callback_ttft_ms=${passFirstCallbackTtft(pass)}")
    builder.appendLine("${prefix}_first_visible_ttft_ms=${passFirstVisibleTtft(pass)}")
    builder.appendLine("${prefix}_first_thought_ttft_ms=${passFirstThoughtTtft(pass)}")
    builder.appendLine("${prefix}_callback_count=${valueOrUnavailable(pass?.callbackCount)}")
    builder.appendLine("${prefix}_visible_callback_count=${valueOrUnavailable(pass?.visibleCallbackCount)}")
    builder.appendLine("${prefix}_thought_callback_count=${valueOrUnavailable(pass?.thoughtCallbackCount)}")
    builder.appendLine("${prefix}_visible_chars=${valueOrUnavailable(pass?.visibleChars)}")
    builder.appendLine("${prefix}_thought_chars=${valueOrUnavailable(pass?.thoughtChars)}")
    builder.appendLine("${prefix}_thought_window_ms=${passThoughtWindow(pass)}")
    builder.appendLine("${prefix}_visible_window_ms=${passVisibleWindow(pass)}")
    builder.appendLine("${prefix}_first_visible_to_done_ms=${passVisibleToDone(pass)}")
    builder.appendLine("${prefix}_thought_to_first_visible_gap_ms=${passThoughtToVisibleGap(pass)}")
  }

  private fun appendNativeBenchmarkPass(
    builder: StringBuilder,
    prefix: String,
    pass: InferencePassTiming?,
  ) {
    val benchmark = pass?.nativeBenchmark
    val appTtft = passFirstCallbackTtftValue(pass)
    val estimatedPrefillMs = estimateStageMs(benchmark?.prefillTokenCount, benchmark?.prefillTokensPerSecond)
    val estimatedDecodeMs = estimateStageMs(benchmark?.decodeTokenCount, benchmark?.decodeTokensPerSecond)
    val appMinusNative =
      if (appTtft != null && benchmark?.nativeTtftMs != null) appTtft - benchmark.nativeTtftMs else null
    val nativeMinusPrefill =
      if (benchmark?.nativeTtftMs != null && estimatedPrefillMs != null) {
        benchmark.nativeTtftMs - estimatedPrefillMs
      } else {
        null
      }

    builder.appendLine("${prefix}_native_init_ms=${doubleOrUnavailable(benchmark?.initTimeMs)}")
    builder.appendLine("${prefix}_native_ttft_ms=${doubleOrUnavailable(benchmark?.nativeTtftMs)}")
    builder.appendLine("${prefix}_prefill_tokens=${valueOrUnavailable(benchmark?.prefillTokenCount)}")
    builder.appendLine("${prefix}_decode_tokens=${valueOrUnavailable(benchmark?.decodeTokenCount)}")
    builder.appendLine("${prefix}_prefill_tokens_per_sec=${doubleOrUnavailable(benchmark?.prefillTokensPerSecond, 3)}")
    builder.appendLine("${prefix}_decode_tokens_per_sec=${doubleOrUnavailable(benchmark?.decodeTokensPerSecond, 3)}")
    builder.appendLine("${prefix}_estimated_prefill_ms=${doubleOrUnavailable(estimatedPrefillMs)}")
    builder.appendLine("${prefix}_estimated_decode_ms=${doubleOrUnavailable(estimatedDecodeMs)}")
    builder.appendLine("${prefix}_app_minus_native_ttft_ms=${doubleOrUnavailable(appMinusNative)}")
    builder.appendLine("${prefix}_native_ttft_minus_estimated_prefill_ms=${doubleOrUnavailable(nativeMinusPrefill)}")
    builder.appendLine("${prefix}_kv_token_count=${valueOrUnavailable(benchmark?.kvTokenCount)}")
    builder.appendLine("${prefix}_benchmark_read_ms=${doubleOrUnavailable(benchmark?.benchmarkReadMs)}")
    builder.appendLine("${prefix}_benchmark_error=${benchmark?.errorClass ?: if (benchmark == null) "unavailable" else "none"}")
  }

  private fun ensureOpenPass(): InferencePassTiming {
    return passes.lastOrNull { it.doneNanos == null }
      ?: InferencePassTiming(
        index = passes.size + 1,
        kind = if (passes.isEmpty()) "initial_unmarked" else "continuation_unmarked",
        inputChars = 0,
        submitNanos = SystemClock.elapsedRealtimeNanos(),
        thinkingOverride = "unmarked",
      )
        .also { passes += it }
  }

  private fun captureMemoryAsync(stage: String) {
    val eventNanos = SystemClock.elapsedRealtimeNanos()
    memorySampler.execute {
      val runtime = Runtime.getRuntime()
      val pssKb = Debug.getPss().toLong()
      val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
      val nativeHeapAllocated = Debug.getNativeHeapAllocatedSize()
      val sampleDelayMs =
        nanosToMs((SystemClock.elapsedRealtimeNanos() - eventNanos).coerceAtLeast(0L))
      synchronized(this) {
        if (memory.none { it.stage == stage }) {
          memory +=
            MemorySnapshot(
              stage = stage,
              pssKb = pssKb,
              javaHeapUsedBytes = javaHeapUsed,
              nativeHeapAllocatedBytes = nativeHeapAllocated,
              sampleDelayMs = sampleDelayMs,
            )
        }
      }
    }
  }

  private fun passFirstCallbackTtftValue(pass: InferencePassTiming?): Double? {
    if (pass == null) return null
    val first = pass.firstCallbackNanos ?: return null
    return nanosToMs((first - pass.submitNanos).coerceAtLeast(0L))
  }

  private fun passFirstCallbackTtft(pass: InferencePassTiming?): String =
    doubleOrUnavailable(passFirstCallbackTtftValue(pass))

  private fun passFirstVisibleTtft(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstVisibleNanos ?: return "unavailable"
    return formatMs(nanosToMs((first - pass.submitNanos).coerceAtLeast(0L)))
  }

  private fun passFirstThoughtTtft(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstThoughtNanos ?: return "unavailable"
    return formatMs(nanosToMs((first - pass.submitNanos).coerceAtLeast(0L)))
  }

  private fun passDecodeFromFirstCallback(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstCallbackNanos ?: return "unavailable"
    val done = pass.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs((done - first).coerceAtLeast(0L)))
  }

  private fun passTotal(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val done = pass.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs((done - pass.submitNanos).coerceAtLeast(0L)))
  }

  private fun passThoughtWindow(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstThoughtNanos ?: return "unavailable"
    val last = pass.lastThoughtNanos ?: return "unavailable"
    return formatMs(nanosToMs((last - first).coerceAtLeast(0L)))
  }

  private fun passVisibleWindow(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstVisibleNanos ?: return "unavailable"
    val last = pass.lastVisibleNanos ?: return "unavailable"
    return formatMs(nanosToMs((last - first).coerceAtLeast(0L)))
  }

  private fun passVisibleToDone(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val first = pass.firstVisibleNanos ?: return "unavailable"
    val done = pass.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs((done - first).coerceAtLeast(0L)))
  }

  private fun passThoughtToVisibleGap(pass: InferencePassTiming?): String {
    if (pass == null) return "unavailable"
    val thought = pass.firstThoughtNanos ?: return "unavailable"
    val visible = pass.firstVisibleNanos ?: return "unavailable"
    return formatMs(nanosToMs((visible - thought).coerceAtLeast(0L)))
  }

  private fun interPassGap(previous: InferencePassTiming?, current: InferencePassTiming): String {
    val previousDone = previous?.doneNanos ?: return "unavailable"
    return formatMs(nanosToMs((current.submitNanos - previousDone).coerceAtLeast(0L)))
  }

  private fun estimateStageMs(tokens: Int?, tokensPerSecond: Double?): Double? {
    if (tokens == null || tokensPerSecond == null || tokensPerSecond <= 0.0 || tokens < 0) return null
    return tokens.toDouble() / tokensPerSecond * 1000.0
  }

  private fun nanosToMs(nanos: Long): Double = nanos / 1_000_000.0
  private fun formatMs(value: Double): String = String.format(Locale.US, "%.2f", value)
  private fun msOrUnavailable(value: Double?): String = value?.let { formatMs(it) } ?: "unavailable"
  private fun doubleOrUnavailable(value: Double?, decimals: Int = 2): String =
    value?.let { String.format(Locale.US, "%.${decimals}f", it) } ?: "unavailable"
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

private data class ActiveTraceState(
  val context: Context,
  val trace: AgentPerformanceTrace,
  var activityGeneration: Long = 0,
  var lastActivityNanos: Long = SystemClock.elapsedRealtimeNanos(),
  var toolEventWindowUntilNanos: Long = Long.MAX_VALUE,
)

private data class ToolStartState(
  val modelName: String,
  val startNanos: Long,
)

/** Process-local MCP211 bridge between Agent UI, LiteRT-LM callbacks, and tool diagnostics. */
object AgentPerformanceCoordinator {
  val reports = mutableStateMapOf<String, String>()
  val sessionReports = mutableStateMapOf<String, String>()

  private val traces = mutableMapOf<String, ActiveTraceState>()
  private val openTools = mutableMapOf<String, ToolStartState>()
  private val archivedRequestReports = mutableMapOf<String, MutableList<String>>()
  private val boundSessionIds = mutableMapOf<String, String>()
  private val handler = Handler(Looper.getMainLooper())
  private var activeModelName: String? = null

  @Synchronized
  fun startRequest(
    context: Context,
    model: Model,
    toolMode: String,
    originalInputChars: Int,
    runtimeInputChars: Int,
    compatAddedInputChars: Int?,
    activeSkillCount: Int,
    enabledMcpCount: Int,
  ) {
    val previous = reports[model.name]
    if (!previous.isNullOrBlank() && isFinalRequestReport(previous)) {
      val archive = archivedRequestReports.getOrPut(model.name) { mutableListOf() }
      val previousRequestId = reportValue(previous, "request_id")
      if (
        previousRequestId.isNotBlank() &&
          archive.none { reportValue(it, "request_id") == previousRequestId }
      ) {
        archive += previous
      }
    }

    val trace =
      AgentPerformanceTrace(
        context = context.applicationContext,
        model = model,
        toolMode = toolMode,
        originalInputChars = originalInputChars,
        runtimeInputChars = runtimeInputChars,
        compatAddedInputChars = compatAddedInputChars,
        activeSkillCount = activeSkillCount,
        enabledMcpCount = enabledMcpCount,
      )
    val state = ActiveTraceState(context = context.applicationContext, trace = trace)
    traces[model.name] = state
    activeModelName = model.name
    reports[model.name] = trace.buildReport()
    updateSessionReport(model.name)
  }

  @Synchronized
  fun onInferenceSubmitted(
    modelName: String,
    inputChars: Int,
    thinkingOverride: String = "runtime_default",
  ) {
    val state = traces[modelName] ?: return
    val runtimeSnapshot = AgentCompatRuntimeCoordinator.snapshot(modelName)
    val sessionId = runtimeSnapshot?.sessionId
    if (sessionId != null && boundSessionIds[modelName] != sessionId) {
      archivedRequestReports.remove(modelName)
      boundSessionIds[modelName] = sessionId
    }

    state.activityGeneration += 1
    state.lastActivityNanos = SystemClock.elapsedRealtimeNanos()
    state.toolEventWindowUntilNanos = Long.MAX_VALUE
    state.trace.markInferenceSubmitted(inputChars, thinkingOverride)
    activeModelName = modelName
    publish(modelName, state)
  }

  @Synchronized
  fun onStreamChunk(modelName: String, visibleChars: Int, thoughtChars: Int) {
    val state = traces[modelName] ?: return
    state.trace.markStreamChunk(visibleChars = visibleChars, thoughtChars = thoughtChars)
    state.lastActivityNanos = SystemClock.elapsedRealtimeNanos()
  }

  @Synchronized
  fun onFirstToken(modelName: String) {
    val state = traces[modelName] ?: return
    if (!state.trace.markLegacyFirstCallback()) return
    state.lastActivityNanos = SystemClock.elapsedRealtimeNanos()
    publish(modelName, state)
  }

  @Synchronized
  fun onInferenceDone(modelName: String, outputChars: Int) {
    val state = traces[modelName] ?: return
    val doneNanos = state.trace.markGenerationDone(outputChars)
    state.activityGeneration += 1
    state.lastActivityNanos = doneNanos
    state.toolEventWindowUntilNanos = doneNanos + (TOOL_EVENT_WINDOW_MS * 1_000_000L)
    state.trace.finish(status = "PASS_COMPLETE", atNanos = doneNanos, captureFinalMemory = false)
    publish(modelName, state)
    scheduleFinalization(modelName = modelName, generation = state.activityGeneration)
  }

  @Synchronized
  fun onLiteRtNativeBenchmark(
    modelName: String,
    initTimeMs: Double?,
    nativeTtftMs: Double?,
    prefillTokenCount: Int?,
    decodeTokenCount: Int?,
    prefillTokensPerSecond: Double?,
    decodeTokensPerSecond: Double?,
    kvTokenCount: Int?,
    benchmarkReadMs: Double,
    errorClass: String?,
  ) {
    val state = traces[modelName] ?: return
    state.trace.recordLiteRtNativeBenchmark(
      initTimeMs = initTimeMs,
      nativeTtftMs = nativeTtftMs,
      prefillTokenCount = prefillTokenCount,
      decodeTokenCount = decodeTokenCount,
      prefillTokensPerSecond = prefillTokensPerSecond,
      decodeTokensPerSecond = decodeTokensPerSecond,
      kvTokenCount = kvTokenCount,
      benchmarkReadMs = benchmarkReadMs,
      errorClass = errorClass,
    )
    publish(modelName, state)
  }

  @Synchronized
  fun finishWithError(modelName: String, errorChars: Int) {
    val state = traces[modelName] ?: return
    state.activityGeneration += 1
    state.lastActivityNanos = SystemClock.elapsedRealtimeNanos()
    state.trace.finish(
      status = "ERROR",
      errorLength = errorChars,
      atNanos = state.lastActivityNanos,
      captureFinalMemory = true,
    )
    publish(modelName, state)
    persistFinalSummary(modelName, state)
    scheduleFinalMemoryRefresh(modelName = modelName, generation = state.activityGeneration)
  }

  @Synchronized
  fun finishStopped(modelName: String) {
    val state = traces[modelName] ?: return
    state.activityGeneration += 1
    state.lastActivityNanos = SystemClock.elapsedRealtimeNanos()
    state.trace.finish(status = "STOPPED", atNanos = state.lastActivityNanos, captureFinalMemory = true)
    publish(modelName, state)
    persistFinalSummary(modelName, state)
    scheduleFinalMemoryRefresh(modelName = modelName, generation = state.activityGeneration)
  }

  @Synchronized
  fun finishReset(modelName: String) {
    val state = traces[modelName] ?: return
    state.activityGeneration += 1
    state.lastActivityNanos = SystemClock.elapsedRealtimeNanos()
    state.trace.finish(status = "RESET", atNanos = state.lastActivityNanos, captureFinalMemory = true)
    publish(modelName, state)
    persistFinalSummary(modelName, state)
    scheduleFinalMemoryRefresh(modelName = modelName, generation = state.activityGeneration)
  }

  @Synchronized
  fun observeDiagnosticEvent(category: String, detailChars: Int) {
    if (!category.startsWith("tool.")) return
    val now = SystemClock.elapsedRealtimeNanos()
    val modelName = activeModelName ?: return
    val state = traces[modelName] ?: return
    if (
      now > state.toolEventWindowUntilNanos &&
        state.toolEventWindowUntilNanos != Long.MAX_VALUE
    ) {
      return
    }
    val suffix = category.substringAfterLast('.')
    val base = category.substringBeforeLast('.', missingDelimiterValue = category)
    when (suffix) {
      "start" -> {
        state.activityGeneration += 1
        state.lastActivityNanos = now
        state.toolEventWindowUntilNanos = Long.MAX_VALUE
        state.trace.reopen()
        openTools[base] = ToolStartState(modelName = modelName, startNanos = now)
        publish(modelName, state)
      }
      "done", "success", "failed", "error" -> {
        val start = openTools.remove(base) ?: return
        if (start.modelName != modelName) return
        state.activityGeneration += 1
        state.lastActivityNanos = now
        state.toolEventWindowUntilNanos = now + (TOOL_EVENT_WINDOW_MS * 1_000_000L)
        state.trace.recordToolExecution(
          toolName = base,
          startNanos = start.startNanos,
          endNanos = now,
          loggedDetailChars = detailChars,
          success = suffix == "done" || suffix == "success",
        )
        state.trace.finish(status = "TOOL_COMPLETE", atNanos = now, captureFinalMemory = false)
        publish(modelName, state)
        scheduleFinalization(modelName = modelName, generation = state.activityGeneration)
      }
    }
  }

  @Synchronized
  fun reportFor(modelName: String): String? = reports[modelName]

  @Synchronized
  fun sessionReportFor(modelName: String): String? = sessionReports[modelName]

  private fun scheduleFinalization(modelName: String, generation: Long) {
    handler.postDelayed(
      {
        synchronized(this) {
          val state = traces[modelName] ?: return@synchronized
          if (state.activityGeneration != generation) return@synchronized
          state.trace.finish(
            status = "COMPLETED",
            atNanos = state.lastActivityNanos,
            captureFinalMemory = true,
          )
          publish(modelName, state)
          persistFinalSummary(modelName, state)
          scheduleFinalMemoryRefresh(modelName = modelName, generation = state.activityGeneration)
        }
      },
      FINALIZATION_DELAY_MS,
    )
  }

  private fun scheduleFinalMemoryRefresh(modelName: String, generation: Long) {
    handler.postDelayed(
      {
        synchronized(this) {
          val state = traces[modelName] ?: return@synchronized
          if (state.activityGeneration != generation) return@synchronized
          publish(modelName, state)
        }
      },
      FINAL_MEMORY_REFRESH_DELAY_MS,
    )
  }

  private fun publish(modelName: String, state: ActiveTraceState) {
    reports[modelName] = state.trace.buildReport()
    updateSessionReport(modelName)
  }

  private fun persistFinalSummary(modelName: String, state: ActiveTraceState) {
    val report = state.trace.buildReport()
    reports[modelName] = report
    updateSessionReport(modelName)
    AgentDiagnosticsLogger.log(
      context = state.context,
      category = "agent.performance.summary",
      message = "MCP211 performance trace completed for $modelName",
      detail = report,
    )
  }

  private fun updateSessionReport(modelName: String) {
    val current = reports[modelName].orEmpty()
    val archived = archivedRequestReports[modelName].orEmpty()
    val allReports =
      buildList {
        addAll(archived)
        if (current.isNotBlank()) {
          val currentId = reportValue(current, "request_id")
          if (currentId.isBlank() || none { reportValue(it, "request_id") == currentId }) {
            add(current)
          }
        }
      }
    if (allReports.isEmpty()) {
      sessionReports.remove(modelName)
      return
    }

    val runtimeSnapshot = AgentCompatRuntimeCoordinator.snapshot(modelName)
    val sessionId = runtimeSnapshot?.sessionId ?: boundSessionIds[modelName] ?: "unavailable"
    val currentTurn = runtimeSnapshot?.userTurnIndex ?: allReports.size
    sessionReports[modelName] =
      buildString {
        appendLine("=== MCP211 Agent 会话性能诊断 ===")
        appendLine("schema=$SESSION_DIAGNOSTICS_SCHEMA")
        appendLine("model_name=$modelName")
        appendLine("session_id=$sessionId")
        appendLine("current_user_turn_index=$currentTurn")
        appendLine("included_request_reports=${allReports.size}")
        appendLine("privacy=diagnostic_metrics_only;user_prompt_not_logged;tool_arguments_not_logged;tool_result_content_not_logged;workspace_paths_not_logged;secrets_not_logged")
        allReports.forEachIndexed { index, report ->
          appendLine()
          appendLine("--- USER_TURN_${index + 1} ---")
          appendLine(report)
        }
        append("=== MCP211 Agent 会话性能诊断结束 ===")
      }
  }

  private fun isFinalRequestReport(report: String): Boolean {
    val status = reportValue(report, "status")
    return status == "COMPLETED" ||
      status == "ERROR" ||
      status == "STOPPED" ||
      status == "RESET"
  }

  private fun reportValue(report: String, key: String): String {
    val prefix = "$key="
    return report.lineSequence()
      .firstOrNull { it.startsWith(prefix) }
      ?.removePrefix(prefix)
      ?.trim()
      .orEmpty()
  }
}

@Composable
fun AgentPerformanceDiagnosticsPanel(reportText: String, modifier: Modifier = Modifier) {
  if (reportText.isBlank()) return
  val context = androidx.compose.ui.platform.LocalContext.current
  val modelName = reportField(reportText, "model_name")
  val sessionReportText =
    if (modelName.isNotBlank()) {
      AgentPerformanceCoordinator.sessionReports[modelName].orEmpty().ifBlank { reportText }
    } else {
      reportText
    }

  var expanded by remember { mutableStateOf(false) }
  var copiedCurrent by remember { mutableStateOf(false) }
  var copiedSession by remember { mutableStateOf(false) }

  Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val expandLabel =
        if (expanded) "收起 MCP211 Agent 性能诊断" else "展开 MCP211 Agent 性能诊断"
      FilledTonalButton(
        onClick = { expanded = !expanded },
        modifier =
          Modifier.fillMaxWidth().semantics {
            role = Role.Button
            contentDescription = expandLabel
            stateDescription = if (expanded) "已展开" else "已收起"
          },
      ) {
        Text(if (expanded) "MCP211 性能诊断：点击收起" else "MCP211 性能诊断：点击展开")
      }

      val currentCopyLabel = if (copiedCurrent) "本轮性能诊断已复制" else "复制本轮性能诊断"
      Button(
        onClick = {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clipboard.setPrimaryClip(
            ClipData.newPlainText("MCP211 Agent Current Request Diagnostics", reportText)
          )
          copiedCurrent = true
        },
        modifier =
          Modifier.fillMaxWidth().semantics {
            role = Role.Button
            contentDescription = currentCopyLabel
          },
      ) {
        Text(currentCopyLabel)
      }

      val sessionCopyLabel = if (copiedSession) "本会话性能诊断已复制" else "复制本会话性能诊断"
      Button(
        onClick = {
          val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clipboard.setPrimaryClip(
            ClipData.newPlainText("MCP211 Agent Session Diagnostics", sessionReportText)
          )
          copiedSession = true
        },
        modifier =
          Modifier.fillMaxWidth().semantics {
            role = Role.Button
            contentDescription = sessionCopyLabel
          },
      ) {
        Text(sessionCopyLabel)
      }

      AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            "下面显示当前用户任务的完整性能诊断，包含 LiteRT-LM 原生 prefill、decode、TTFT 与 KV token 指标。复制按钮常驻在上方。",
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
        }
      }
    }
  }
}

private fun reportField(report: String, key: String): String {
  val prefix = "$key="
  return report.lineSequence()
    .firstOrNull { it.startsWith(prefix) }
    ?.removePrefix(prefix)
    ?.trim()
    .orEmpty()
}
