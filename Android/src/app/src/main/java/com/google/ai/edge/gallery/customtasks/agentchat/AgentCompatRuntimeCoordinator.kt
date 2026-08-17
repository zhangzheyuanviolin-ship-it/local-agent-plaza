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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal const val COMPAT_RUNTIME_INSTRUCTIONS_MARKER = "COMPAT_AGENT_INSTRUCTIONS"
internal const val COMPAT_RUNTIME_USER_REQUEST_SEPARATOR = "\n\nUSER_REQUEST\n"
internal const val COMPAT_RUNTIME_TOOL_RESULT_MARKER = "TOOL_RESULT"

internal const val COMPAT_FRESH_REASON_TOP_LEVEL = "top_level_user_turn"
internal const val COMPAT_FRESH_REASON_TOOL_CONTINUATION = "tool_continuation"

private const val TOOL_CALL_OPEN_TAG_RUNTIME = "<tool_call>"
private const val TOOL_CALL_CLOSE_TAG_RUNTIME = "</tool_call>"
private const val MIN_HISTORY_BUDGET_CHARS = 1200
private const val MIN_HISTORY_ENTRY_CHARS = 220
private const val MIN_SESSION_ENTRY_CHARS = 180
private const val MAX_SESSION_HISTORY_BUDGET_CHARS = 2600
private const val MAX_FINGERPRINT_CHARS = 4000
private const val MAX_SESSION_USER_CHARS = 1400
private const val MAX_SESSION_ASSISTANT_CHARS = 2200
private const val MAX_SESSION_TOOL_CONTEXT_CHARS = 1800

internal data class CompatPreparedInput(
  val input: String,
  val requiresFreshConversation: Boolean,
  val freshConversationReason: String?,
  val rawInputChars: Int,
  val effectiveInputChars: Int,
  val historyStepCount: Int,
  val historyChars: Int,
  val sessionId: String?,
  val userTurnIndex: Int,
  val sessionHistoryTurnCount: Int,
  val sessionHistoryChars: Int,
)

internal data class CompatGenerationDecision(
  val blockedRepeatedToolCall: Boolean,
  val repeatedToolCallCount: Int,
)

internal data class CompatRuntimeMetricsSnapshot(
  val preSubmitWaitMsTotal: Double,
  val continuationPrepareMsTotal: Double,
  val lastContinuationPrepareMs: Double?,
  val lastContinuationResetMs: Double?,
  val continuationRawInputChars: Int?,
  val continuationEffectiveInputChars: Int?,
  val historyStepCount: Int,
  val historyChars: Int,
  val repeatedToolCallCount: Int,
  val sessionId: String,
  val userTurnIndex: Int,
  val sessionHistoryTurnCount: Int,
  val sessionHistoryChars: Int,
  val sessionCompletedTurnCount: Int,
  val topLevelPrepareMsTotal: Double,
  val lastTopLevelPrepareMs: Double?,
  val lastTopLevelResetMs: Double?,
  val topLevelResetCount: Int,
  val topLevelRawInputChars: Int?,
  val topLevelEffectiveInputChars: Int?,
  val lastFreshConversationReason: String?,
  val conversationGenerationId: Int,
)

private data class CompatToolHistoryEntry(
  val toolName: String,
  val status: String,
  val payload: String,
)

private data class CompatSessionHistoryEntry(
  val turnIndex: Int,
  val userRequest: String,
  val assistantResult: String,
  val toolContext: String,
)

private data class CompatSessionState(
  val sessionId: String = UUID.randomUUID().toString().substring(0, 8),
  var nextTurnIndex: Int = 0,
  var conversationGenerationId: Int = 1,
  val completedTurns: MutableList<CompatSessionHistoryEntry> = mutableListOf(),
)

private data class CompatRuntimeState(
  val sessionId: String,
  val userTurnIndex: Int,
  var instructionPrefix: String,
  var originalUserRequest: String,
  var sessionContextSection: String = "",
  var sessionHistoryTurnCount: Int = 0,
  var sessionHistoryChars: Int = 0,
  var awaitingToolResult: Boolean = false,
  val history: MutableList<CompatToolHistoryEntry> = mutableListOf(),
  var lastToolFingerprint: String? = null,
  var consecutiveRepeatedToolCalls: Int = 0,
  var repeatedToolCallCount: Int = 0,
  var preSubmitWaitMsTotal: Double = 0.0,
  var continuationPrepareMsTotal: Double = 0.0,
  var lastContinuationPrepareMs: Double? = null,
  var lastContinuationResetMs: Double? = null,
  var continuationRawInputChars: Int? = null,
  var continuationEffectiveInputChars: Int? = null,
  var historyChars: Int = 0,
  var topLevelPrepareMsTotal: Double = 0.0,
  var lastTopLevelPrepareMs: Double? = null,
  var lastTopLevelResetMs: Double? = null,
  var topLevelResetCount: Int = 0,
  var topLevelRawInputChars: Int? = null,
  var topLevelEffectiveInputChars: Int? = null,
  var lastFreshConversationReason: String? = null,
  var pendingFreshConversationReason: String? = null,
  var completed: Boolean = false,
)

/**
 * MCP205 process-local runtime state for COMPAT Agent requests.
 *
 * LiteRT-LM Conversation objects are intentionally short-lived. Every top-level user request and
 * every tool continuation is executed in a fresh Conversation while the Engine remains loaded.
 * Cross-user-turn memory and current-task tool history are explicitly rehydrated under bounded
 * character budgets, avoiding hidden growth of LiteRT-LM Conversation history.
 */
object AgentCompatRuntimeCoordinator {
  private val states = ConcurrentHashMap<String, CompatRuntimeState>()
  private val sessions = ConcurrentHashMap<String, CompatSessionState>()
  private val pendingPreSubmitWaitMs = ConcurrentHashMap<String, Double>()

  @Synchronized
  internal fun prepareInput(
    modelName: String,
    rawInput: String,
    historyBudgetChars: Int,
  ): CompatPreparedInput {
    val trimmed = rawInput.trimStart()
    if (isCompatInitialInput(trimmed)) {
      val separatorIndex = rawInput.indexOf(COMPAT_RUNTIME_USER_REQUEST_SEPARATOR)
      val prefix =
        if (separatorIndex > 0) rawInput.substring(0, separatorIndex).trimEnd() else rawInput.trimEnd()
      val request =
        if (separatorIndex >= 0) {
          rawInput.substring(separatorIndex + COMPAT_RUNTIME_USER_REQUEST_SEPARATOR.length).trim()
        } else {
          ""
        }

      val session = sessions.getOrPut(modelName) { CompatSessionState() }
      session.nextTurnIndex += 1
      val turnIndex = session.nextTurnIndex
      val sessionBudget =
        resolveSessionHistoryBudget(
          totalHistoryBudgetChars = historyBudgetChars,
          hasSessionHistory = session.completedTurns.isNotEmpty(),
        )
      val sessionContext =
        if (sessionBudget > 0) {
          buildSessionHistorySection(
            entries = session.completedTurns,
            sessionBudgetChars = sessionBudget,
          )
        } else {
          ""
        }

      val effectiveInput =
        buildString {
          append(prefix)
          if (sessionContext.isNotBlank()) {
            append("\n\n")
            append(sessionContext)
          }
          append(COMPAT_RUNTIME_USER_REQUEST_SEPARATOR)
          append(request)
        }

      val state =
        CompatRuntimeState(
          sessionId = session.sessionId,
          userTurnIndex = turnIndex,
          instructionPrefix = prefix,
          originalUserRequest = request,
          sessionContextSection = sessionContext,
          sessionHistoryTurnCount = session.completedTurns.size,
          sessionHistoryChars = sessionContext.length,
          preSubmitWaitMsTotal = pendingPreSubmitWaitMs.remove(modelName) ?: 0.0,
          topLevelRawInputChars = rawInput.length,
          topLevelEffectiveInputChars = effectiveInput.length,
          pendingFreshConversationReason = COMPAT_FRESH_REASON_TOP_LEVEL,
        )
      states[modelName] = state

      // MCP205 deliberately resets for every top-level COMPAT request, including turn 1.
      // The Engine remains loaded; only the lightweight Conversation object is replaced.
      return CompatPreparedInput(
        input = effectiveInput,
        requiresFreshConversation = true,
        freshConversationReason = COMPAT_FRESH_REASON_TOP_LEVEL,
        rawInputChars = rawInput.length,
        effectiveInputChars = effectiveInput.length,
        historyStepCount = 0,
        historyChars = 0,
        sessionId = session.sessionId,
        userTurnIndex = turnIndex,
        sessionHistoryTurnCount = session.completedTurns.size,
        sessionHistoryChars = sessionContext.length,
      )
    }

    val state = states[modelName]
    if (state != null) {
      pendingPreSubmitWaitMs.remove(modelName)?.let { state.preSubmitWaitMsTotal += it }
    }
    if (
      state == null ||
        !state.awaitingToolResult ||
        !trimmed.startsWith(COMPAT_RUNTIME_TOOL_RESULT_MARKER)
    ) {
      return CompatPreparedInput(
        input = rawInput,
        requiresFreshConversation = false,
        freshConversationReason = null,
        rawInputChars = rawInput.length,
        effectiveInputChars = rawInput.length,
        historyStepCount = state?.history?.size ?: 0,
        historyChars = state?.historyChars ?: 0,
        sessionId = state?.sessionId,
        userTurnIndex = state?.userTurnIndex ?: 0,
        sessionHistoryTurnCount = state?.sessionHistoryTurnCount ?: 0,
        sessionHistoryChars = state?.sessionHistoryChars ?: 0,
      )
    }

    state.awaitingToolResult = false
    val parsed = parseToolResult(rawInput)
    state.history += parsed

    val remainingToolBudget =
      (historyBudgetChars - state.sessionHistoryChars).coerceAtLeast(MIN_HISTORY_BUDGET_CHARS)
    val historySection =
      buildHistorySection(
        originalUserRequest = state.originalUserRequest,
        entries = state.history,
        historyBudgetChars = remainingToolBudget,
      )
    state.historyChars = historySection.length

    val effectiveInput =
      buildString {
        append(state.instructionPrefix)
        if (state.sessionContextSection.isNotBlank()) {
          append("\n\n")
          append(state.sessionContextSection)
        }
        append("\n\n")
        append(historySection)
        append("\n\nNEXT_ACTION\n")
        append(
          "Continue the current user task silently. If the task is complete, answer the user directly in the user's language and stop. "
        )
        append(
          "If the task is still incomplete and another enabled compatibility tool is genuinely required, output exactly one <tool_call> JSON block and no prose. "
        )
        append(
          "Do not repeat an identical tool call unless new information makes the repeat necessary. Do not output hidden reasoning, analysis, scratchpad text, or raw JSON outside the tool-call block.\n"
        )
        append(
          "For every XLSX row-fact line containing 行事实, treat that single line as authoritative and preserve the exact metric name, unit, year, and value from the same line. "
        )
        append(
          "If context_safety_note says tool output was truncated, answer only from the visible history and tell the user that the complete exact tool output is available in the saved audit file."
        )
      }

    state.continuationRawInputChars = rawInput.length
    state.continuationEffectiveInputChars = effectiveInput.length
    state.pendingFreshConversationReason = COMPAT_FRESH_REASON_TOOL_CONTINUATION
    return CompatPreparedInput(
      input = effectiveInput,
      requiresFreshConversation = true,
      freshConversationReason = COMPAT_FRESH_REASON_TOOL_CONTINUATION,
      rawInputChars = rawInput.length,
      effectiveInputChars = effectiveInput.length,
      historyStepCount = state.history.size,
      historyChars = historySection.length,
      sessionId = state.sessionId,
      userTurnIndex = state.userTurnIndex,
      sessionHistoryTurnCount = state.sessionHistoryTurnCount,
      sessionHistoryChars = state.sessionHistoryChars,
    )
  }

  @Synchronized
  internal fun onGenerationCompleted(
    modelName: String,
    generatedText: String,
  ): CompatGenerationDecision {
    val state =
      states[modelName]
        ?: return CompatGenerationDecision(
          blockedRepeatedToolCall = false,
          repeatedToolCallCount = 0,
        )
    val fingerprint = extractToolCallFingerprint(generatedText)
    if (fingerprint == null) {
      state.awaitingToolResult = false
      state.consecutiveRepeatedToolCalls = 0
      state.lastToolFingerprint = null
      completeCurrentTopLevelTurn(modelName = modelName, state = state, generatedText = generatedText)
      return CompatGenerationDecision(
        blockedRepeatedToolCall = false,
        repeatedToolCallCount = state.repeatedToolCallCount,
      )
    }

    if (fingerprint == state.lastToolFingerprint) {
      state.consecutiveRepeatedToolCalls += 1
      state.repeatedToolCallCount += 1
    } else {
      state.lastToolFingerprint = fingerprint
      state.consecutiveRepeatedToolCalls = 0
    }

    val blocked = state.consecutiveRepeatedToolCalls >= 2
    state.awaitingToolResult = !blocked
    return CompatGenerationDecision(
      blockedRepeatedToolCall = blocked,
      repeatedToolCallCount = state.repeatedToolCallCount,
    )
  }

  @Synchronized
  internal fun onGenerationFailed(modelName: String) {
    states[modelName]?.awaitingToolResult = false
  }

  @Synchronized
  internal fun recordPreSubmitWait(modelName: String, elapsedMs: Double) {
    val safeMs = elapsedMs.coerceAtLeast(0.0)
    pendingPreSubmitWaitMs[modelName] = (pendingPreSubmitWaitMs[modelName] ?: 0.0) + safeMs
  }

  @Synchronized
  internal fun recordFreshConversationPreparation(
    modelName: String,
    reason: String,
    prepareMs: Double,
    resetMs: Double,
    rawInputChars: Int,
    effectiveInputChars: Int,
    historyStepCount: Int,
    historyChars: Int,
    sessionHistoryTurnCount: Int,
    sessionHistoryChars: Int,
  ) {
    val state = states[modelName] ?: return
    val session = sessions[modelName]
    val safePrepareMs = prepareMs.coerceAtLeast(0.0)
    val safeResetMs = resetMs.coerceAtLeast(0.0)

    state.lastFreshConversationReason = reason
    state.historyChars = historyChars.coerceAtLeast(0)
    state.sessionHistoryTurnCount = sessionHistoryTurnCount.coerceAtLeast(0)
    state.sessionHistoryChars = sessionHistoryChars.coerceAtLeast(0)

    when (reason) {
      COMPAT_FRESH_REASON_TOP_LEVEL -> {
        state.topLevelPrepareMsTotal += safePrepareMs
        state.lastTopLevelPrepareMs = safePrepareMs
        state.lastTopLevelResetMs = safeResetMs
        state.topLevelResetCount += 1
        state.topLevelRawInputChars = rawInputChars.coerceAtLeast(0)
        state.topLevelEffectiveInputChars = effectiveInputChars.coerceAtLeast(0)
      }
      COMPAT_FRESH_REASON_TOOL_CONTINUATION -> {
        state.continuationPrepareMsTotal += safePrepareMs
        state.lastContinuationPrepareMs = safePrepareMs
        state.lastContinuationResetMs = safeResetMs
        state.continuationRawInputChars = rawInputChars.coerceAtLeast(0)
        state.continuationEffectiveInputChars = effectiveInputChars.coerceAtLeast(0)
      }
    }

    if (historyStepCount != state.history.size) {
      state.historyChars = historyChars.coerceAtLeast(0)
    }
    if (session != null) {
      session.conversationGenerationId += 1
    }
  }

  @Synchronized
  internal fun recordContinuationPreparation(
    modelName: String,
    prepareMs: Double,
    resetMs: Double,
    rawInputChars: Int,
    effectiveInputChars: Int,
    historyStepCount: Int,
    historyChars: Int,
  ) {
    val state = states[modelName] ?: return
    val reason =
      state.pendingFreshConversationReason
        ?: if (historyStepCount > 0) {
          COMPAT_FRESH_REASON_TOOL_CONTINUATION
        } else {
          COMPAT_FRESH_REASON_TOP_LEVEL
        }
    recordFreshConversationPreparation(
      modelName = modelName,
      reason = reason,
      prepareMs = prepareMs,
      resetMs = resetMs,
      rawInputChars = rawInputChars,
      effectiveInputChars = effectiveInputChars,
      historyStepCount = historyStepCount,
      historyChars = historyChars,
      sessionHistoryTurnCount = state.sessionHistoryTurnCount,
      sessionHistoryChars = state.sessionHistoryChars,
    )
    state.pendingFreshConversationReason = null
  }

  @Synchronized
  internal fun snapshot(modelName: String): CompatRuntimeMetricsSnapshot? {
    val state = states[modelName] ?: return null
    val session = sessions[modelName]
    return CompatRuntimeMetricsSnapshot(
      preSubmitWaitMsTotal = state.preSubmitWaitMsTotal,
      continuationPrepareMsTotal = state.continuationPrepareMsTotal,
      lastContinuationPrepareMs = state.lastContinuationPrepareMs,
      lastContinuationResetMs = state.lastContinuationResetMs,
      continuationRawInputChars = state.continuationRawInputChars,
      continuationEffectiveInputChars = state.continuationEffectiveInputChars,
      historyStepCount = state.history.size,
      historyChars = state.historyChars,
      repeatedToolCallCount = state.repeatedToolCallCount,
      sessionId = state.sessionId,
      userTurnIndex = state.userTurnIndex,
      sessionHistoryTurnCount = state.sessionHistoryTurnCount,
      sessionHistoryChars = state.sessionHistoryChars,
      sessionCompletedTurnCount = session?.completedTurns?.size ?: 0,
      topLevelPrepareMsTotal = state.topLevelPrepareMsTotal,
      lastTopLevelPrepareMs = state.lastTopLevelPrepareMs,
      lastTopLevelResetMs = state.lastTopLevelResetMs,
      topLevelResetCount = state.topLevelResetCount,
      topLevelRawInputChars = state.topLevelRawInputChars,
      topLevelEffectiveInputChars = state.topLevelEffectiveInputChars,
      lastFreshConversationReason = state.lastFreshConversationReason,
      conversationGenerationId = session?.conversationGenerationId ?: 0,
    )
  }

  @Synchronized
  internal fun clear(modelName: String) {
    states.remove(modelName)
    sessions.remove(modelName)
    pendingPreSubmitWaitMs.remove(modelName)
  }

  @Synchronized
  internal fun clearAllForTest() {
    states.clear()
    sessions.clear()
    pendingPreSubmitWaitMs.clear()
  }

  private fun completeCurrentTopLevelTurn(
    modelName: String,
    state: CompatRuntimeState,
    generatedText: String,
  ) {
    if (state.completed) return
    val session = sessions[modelName] ?: return
    val assistantResult = sanitizeAssistantForSession(generatedText)
    val toolContext =
      state.history.joinToString("\n\n") { entry ->
        buildString {
          append("tool: ")
          append(entry.toolName)
          append("\nstatus: ")
          append(entry.status)
          if (entry.payload.isNotBlank()) {
            append("\npayload:\n")
            append(entry.payload)
          }
        }
      }
    session.completedTurns +=
      CompatSessionHistoryEntry(
        turnIndex = state.userTurnIndex,
        userRequest = state.originalUserRequest.take(MAX_SESSION_USER_CHARS),
        assistantResult = assistantResult.take(MAX_SESSION_ASSISTANT_CHARS),
        toolContext = toolContext.take(MAX_SESSION_TOOL_CONTEXT_CHARS),
      )
    state.completed = true
  }

  private fun sanitizeAssistantForSession(text: String): String {
    return text
      .replace(
        Regex("<think>.*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        "",
      )
      .replace(
        Regex(
          "<tool_call>.*?</tool_call>",
          setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        "",
      )
      .trim()
      .ifBlank { "(completed without visible assistant text)" }
  }

  private fun resolveSessionHistoryBudget(
    totalHistoryBudgetChars: Int,
    hasSessionHistory: Boolean,
  ): Int {
    if (!hasSessionHistory) return 0
    val maxAllowedForSession =
      (totalHistoryBudgetChars - MIN_HISTORY_BUDGET_CHARS).coerceAtLeast(0)
    if (maxAllowedForSession == 0) return 0
    val target = (totalHistoryBudgetChars * 0.35f).toInt()
    return target
      .coerceAtMost(MAX_SESSION_HISTORY_BUDGET_CHARS)
      .coerceAtMost(maxAllowedForSession)
      .coerceAtLeast(minOf(500, maxAllowedForSession))
  }

  private fun buildSessionHistorySection(
    entries: List<CompatSessionHistoryEntry>,
    sessionBudgetChars: Int,
  ): String {
    if (entries.isEmpty() || sessionBudgetChars <= 0) return ""
    val header =
      buildString {
        append("SESSION_HISTORY\n")
        append("completed_turn_count: ")
        append(entries.size)
        append('\n')
        append(
          "Use this only to resolve references to earlier user turns. The CURRENT USER_REQUEST below has priority.\n"
        )
      }
    if (header.length >= sessionBudgetChars) {
      return truncateWithMarker(header, sessionBudgetChars, "MCP205 session history budget")
    }

    val available = (sessionBudgetChars - header.length).coerceAtLeast(MIN_SESSION_ENTRY_CHARS)
    val allocations = allocateSessionChars(entries.size, available)
    val body = StringBuilder()
    entries.forEachIndexed { index, entry ->
      val entryHeader = "TURN ${entry.turnIndex}\nuser:\n"
      val assistantHeader = "\nassistant_result:\n"
      val toolHeader = if (entry.toolContext.isNotBlank()) "\ntool_context:\n" else ""
      val fixed = entryHeader.length + assistantHeader.length + toolHeader.length
      val contentBudget = (allocations[index] - fixed).coerceAtLeast(80)
      val userBudget = (contentBudget * 0.35f).toInt().coerceAtLeast(40)
      val assistantBudget = (contentBudget * 0.45f).toInt().coerceAtLeast(40)
      val toolBudget = (contentBudget - userBudget - assistantBudget).coerceAtLeast(0)

      if (body.isNotEmpty()) body.append("\n\n")
      body.append(entryHeader)
      body.append(
        truncateWithMarker(entry.userRequest, userBudget, "MCP205 session user budget")
      )
      body.append(assistantHeader)
      body.append(
        truncateWithMarker(entry.assistantResult, assistantBudget, "MCP205 session assistant budget")
      )
      if (entry.toolContext.isNotBlank() && toolBudget > 0) {
        body.append(toolHeader)
        body.append(
          truncateWithMarker(entry.toolContext, toolBudget, "MCP205 session tool budget")
        )
      }
    }
    return truncateWithMarker(
      header + body.toString(),
      sessionBudgetChars,
      "MCP205 session history budget",
    )
  }

  private fun allocateSessionChars(entryCount: Int, available: Int): IntArray {
    if (entryCount <= 1) return intArrayOf(available)
    val latestAllocation = (available * 0.55f).toInt().coerceAtLeast(MIN_SESSION_ENTRY_CHARS)
    val olderTotal = (available - latestAllocation).coerceAtLeast(MIN_SESSION_ENTRY_CHARS)
    val olderEach = (olderTotal / (entryCount - 1)).coerceAtLeast(MIN_SESSION_ENTRY_CHARS)
    val allocations = IntArray(entryCount) { olderEach }
    allocations[entryCount - 1] = latestAllocation
    val used = allocations.sum()
    if (used > available) {
      val scale = available.toDouble() / used.toDouble()
      for (i in allocations.indices) {
        allocations[i] = (allocations[i] * scale).toInt().coerceAtLeast(60)
      }
    }
    return allocations
  }

  private fun isCompatInitialInput(trimmedInput: String): Boolean {
    return trimmedInput.startsWith(COMPAT_RUNTIME_INSTRUCTIONS_MARKER) &&
      trimmedInput.contains("Qwen-compatible tool mode")
  }

  private fun parseToolResult(rawInput: String): CompatToolHistoryEntry {
    val toolName = lineValue(rawInput, "tool:").ifBlank { "unknown" }
    val status = lineValue(rawInput, "status:").ifBlank { "unknown" }
    val payloadStart = rawInput.indexOf("payload:")
    val instructionStart =
      if (payloadStart >= 0) {
        rawInput.indexOf("\n\nYou are in compatibility tool mode.", startIndex = payloadStart)
      } else {
        -1
      }
    val payload =
      when {
        payloadStart < 0 -> rawInput.take(4000).trim()
        instructionStart > payloadStart ->
          rawInput.substring(payloadStart + "payload:".length, instructionStart).trim()
        else -> rawInput.substring(payloadStart + "payload:".length).trim()
      }
    return CompatToolHistoryEntry(
      toolName = toolName,
      status = status,
      payload = prioritizePayloadForHistory(payload),
    )
  }

  private fun lineValue(text: String, prefix: String): String {
    return text.lineSequence()
      .firstOrNull { it.trimStart().startsWith(prefix) }
      ?.trim()
      ?.removePrefix(prefix)
      ?.trim()
      .orEmpty()
  }

  private fun prioritizePayloadForHistory(payload: String): String {
    if (!payload.contains("行事实")) return payload
    val lines = payload.lines().map { it.trim() }.filter { it.isNotBlank() }
    val factLines = lines.filter { it.contains("行事实") }
    if (factLines.isEmpty()) return payload
    val metadataLines =
      lines.filter { line ->
        !line.contains("行事实") &&
          (
            line.startsWith("Read ") ||
              line.contains("工作表") ||
              line.contains("表格") ||
              line.contains("列") ||
              line.contains("字段") ||
              line.contains("单位") ||
              line.contains("年份") ||
              line.contains("指标") ||
              line.contains("context_safety_note")
          )
      }
    return (factLines + metadataLines).distinct().joinToString("\n").ifBlank { payload }
  }

  private fun buildHistorySection(
    originalUserRequest: String,
    entries: List<CompatToolHistoryEntry>,
    historyBudgetChars: Int,
  ): String {
    val safeBudget = historyBudgetChars.coerceAtLeast(MIN_HISTORY_BUDGET_CHARS)
    val header =
      buildString {
        append("TOOL_HISTORY\n")
        append("original_user_request: ")
        append(originalUserRequest.take(1600))
        append("\nstep_count: ")
        append(entries.size)
        append('\n')
      }
    if (entries.isEmpty()) return header.trimEnd()

    val footerReserve = 160
    val available = (safeBudget - header.length - footerReserve).coerceAtLeast(MIN_HISTORY_ENTRY_CHARS)
    val allocations = allocateHistoryChars(entries.size, available)
    val body = StringBuilder()
    entries.forEachIndexed { index, entry ->
      val entryHeader =
        "STEP ${index + 1}\ntool: ${entry.toolName}\nstatus: ${entry.status}\npayload:\n"
      val payloadBudget = (allocations[index] - entryHeader.length).coerceAtLeast(80)
      val compactPayload =
        truncateWithMarker(entry.payload, payloadBudget, "MCP205 COMPAT tool history budget")
      if (body.isNotEmpty()) body.append("\n\n")
      body.append(entryHeader)
      body.append(compactPayload)
    }
    val combined = header + body.toString()
    return truncateWithMarker(combined, safeBudget, "MCP205 COMPAT tool history budget")
  }

  private fun allocateHistoryChars(entryCount: Int, available: Int): IntArray {
    if (entryCount <= 1) return intArrayOf(available)
    val latestAllocation = (available * 0.5).toInt().coerceAtLeast(MIN_HISTORY_ENTRY_CHARS)
    val olderTotal = (available - latestAllocation).coerceAtLeast(MIN_HISTORY_ENTRY_CHARS)
    val olderEach = (olderTotal / (entryCount - 1)).coerceAtLeast(MIN_HISTORY_ENTRY_CHARS)
    val allocations = IntArray(entryCount) { olderEach }
    allocations[entryCount - 1] = latestAllocation
    val used = allocations.sum()
    if (used > available) {
      val scale = available.toDouble() / used.toDouble()
      for (i in allocations.indices) {
        allocations[i] = (allocations[i] * scale).toInt().coerceAtLeast(80)
      }
    }
    return allocations
  }

  private fun truncateWithMarker(text: String, maxChars: Int, label: String): String {
    if (text.length <= maxChars) return text
    val marker = "\n...[truncated by $label]"
    if (maxChars <= marker.length + 16) return text.take(maxChars)
    return text.take(maxChars - marker.length) + marker
  }

  private fun extractToolCallFingerprint(text: String): String? {
    val open = text.indexOf(TOOL_CALL_OPEN_TAG_RUNTIME, ignoreCase = true)
    if (open < 0) return null
    val payloadStart = open + TOOL_CALL_OPEN_TAG_RUNTIME.length
    val close =
      text.indexOf(
        TOOL_CALL_CLOSE_TAG_RUNTIME,
        startIndex = payloadStart,
        ignoreCase = true,
      )
    val payload = text.substring(payloadStart, if (close >= 0) close else text.length).trim()
    if (payload.isBlank()) return null
    val jsonText = extractFirstJsonObject(payload)
    if (jsonText != null) {
      val json = runCatching { JSONObject(jsonText) }.getOrNull()
      if (json != null) {
        return canonicalizeJsonObject(json).take(MAX_FINGERPRINT_CHARS)
      }
    }
    return payload.replace(Regex("\\s+"), " ").trim().take(MAX_FINGERPRINT_CHARS)
  }

  private fun extractFirstJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaping = false
    for (index in start until text.length) {
      val char = text[index]
      if (escaping) {
        escaping = false
        continue
      }
      if (char == '\\' && inString) {
        escaping = true
        continue
      }
      if (char == '"') {
        inString = !inString
        continue
      }
      if (inString) continue
      when (char) {
        '{' -> depth += 1
        '}' -> {
          depth -= 1
          if (depth == 0) return text.substring(start, index + 1)
        }
      }
    }
    return null
  }

  private fun canonicalizeJsonObject(json: JSONObject): String {
    val keys = mutableListOf<String>()
    val iterator = json.keys()
    while (iterator.hasNext()) keys += iterator.next()
    keys.sort()
    return keys.joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
      JSONObject.quote(key) + ":" + canonicalizeJsonValue(json.opt(key))
    }
  }

  private fun canonicalizeJsonArray(array: JSONArray): String {
    return (0 until array.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
      index ->
      canonicalizeJsonValue(array.opt(index))
    }
  }

  private fun canonicalizeJsonValue(value: Any?): String {
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
