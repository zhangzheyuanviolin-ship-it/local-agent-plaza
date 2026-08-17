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

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

internal const val COMPAT_RUNTIME_INSTRUCTIONS_MARKER = "COMPAT_AGENT_INSTRUCTIONS"
internal const val COMPAT_RUNTIME_USER_REQUEST_SEPARATOR = "\n\nUSER_REQUEST\n"
internal const val COMPAT_RUNTIME_TOOL_RESULT_MARKER = "TOOL_RESULT"

private const val TOOL_CALL_OPEN_TAG_RUNTIME = "<tool_call>"
private const val TOOL_CALL_CLOSE_TAG_RUNTIME = "</tool_call>"
private const val MIN_HISTORY_BUDGET_CHARS = 1200
private const val MIN_HISTORY_ENTRY_CHARS = 220
private const val MAX_FINGERPRINT_CHARS = 4000

internal data class CompatPreparedInput(
  val input: String,
  val requiresFreshConversation: Boolean,
  val rawInputChars: Int,
  val effectiveInputChars: Int,
  val historyStepCount: Int,
  val historyChars: Int,
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
)

private data class CompatToolHistoryEntry(
  val toolName: String,
  val status: String,
  val payload: String,
)

private data class CompatRuntimeState(
  var instructionPrefix: String,
  var originalUserRequest: String,
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
)

/**
 * MCP204 process-local runtime state for COMPAT Agent requests.
 *
 * The coordinator deliberately keeps only model-visible compact tool results. Complete tool output
 * remains in the existing tool-audit files. A continuation is accepted only after the previous
 * model pass actually emitted a tool call, so ordinary chat text can no longer trigger a fresh
 * Conversation merely by starting with TOOL_RESULT.
 */
object AgentCompatRuntimeCoordinator {
  private val states = ConcurrentHashMap<String, CompatRuntimeState>()
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
      val state =
        CompatRuntimeState(
          instructionPrefix = prefix,
          originalUserRequest = request,
          preSubmitWaitMsTotal = pendingPreSubmitWaitMs.remove(modelName) ?: 0.0,
        )
      states[modelName] = state
      return CompatPreparedInput(
        input = rawInput,
        requiresFreshConversation = false,
        rawInputChars = rawInput.length,
        effectiveInputChars = rawInput.length,
        historyStepCount = 0,
        historyChars = 0,
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
        rawInputChars = rawInput.length,
        effectiveInputChars = rawInput.length,
        historyStepCount = state?.history?.size ?: 0,
        historyChars = state?.historyChars ?: 0,
      )
    }

    // One-shot consumption: only the result that follows an observed model tool call is allowed to
    // enter the fresh-Conversation continuation path.
    state.awaitingToolResult = false
    val parsed = parseToolResult(rawInput)
    state.history += parsed
    val historySection =
      buildHistorySection(
        originalUserRequest = state.originalUserRequest,
        entries = state.history,
        historyBudgetChars = historyBudgetChars,
      )
    state.historyChars = historySection.length
    val effectiveInput =
      buildString {
        append(state.instructionPrefix)
        append("\n\n")
        append(historySection)
        append("\n\nNEXT_ACTION\n")
        append(
          "Continue the original task silently. If the task is complete, answer the user directly in the user's language and stop. "
        )
        append(
          "If the task is still incomplete and another enabled compatibility tool is genuinely required, output exactly one <tool_call> JSON block and no prose. "
        )
        append(
          "Do not repeat an identical tool call unless new information makes the repeat necessary. Do not output hidden reasoning, analysis, scratchpad text, or raw JSON outside the tool-call block."
        )
      }

    state.continuationRawInputChars = rawInput.length
    state.continuationEffectiveInputChars = effectiveInput.length
    return CompatPreparedInput(
      input = effectiveInput,
      requiresFreshConversation = true,
      rawInputChars = rawInput.length,
      effectiveInputChars = effectiveInput.length,
      historyStepCount = state.history.size,
      historyChars = historySection.length,
    )
  }

  @Synchronized
  internal fun onGenerationCompleted(modelName: String, generatedText: String): CompatGenerationDecision {
    val state = states[modelName]
      ?: return CompatGenerationDecision(blockedRepeatedToolCall = false, repeatedToolCallCount = 0)
    val fingerprint = extractToolCallFingerprint(generatedText)
    if (fingerprint == null) {
      state.awaitingToolResult = false
      state.consecutiveRepeatedToolCalls = 0
      state.lastToolFingerprint = null
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

    // Allow one identical retry. Block the third consecutive identical call before AgentChatScreen
    // receives onDone, which prevents a third execution of a potentially side-effecting tool.
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
    pendingPreSubmitWaitMs.merge(modelName, safeMs) { current, added -> current + added }
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
    val safePrepareMs = prepareMs.coerceAtLeast(0.0)
    state.continuationPrepareMsTotal += safePrepareMs
    state.lastContinuationPrepareMs = safePrepareMs
    state.lastContinuationResetMs = resetMs.coerceAtLeast(0.0)
    state.continuationRawInputChars = rawInputChars.coerceAtLeast(0)
    state.continuationEffectiveInputChars = effectiveInputChars.coerceAtLeast(0)
    state.historyChars = historyChars.coerceAtLeast(0)
    if (historyStepCount != state.history.size) {
      state.historyChars = historyChars.coerceAtLeast(0)
    }
  }

  @Synchronized
  internal fun snapshot(modelName: String): CompatRuntimeMetricsSnapshot? {
    val state = states[modelName] ?: return null
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
    )
  }

  @Synchronized
  fun clear(modelName: String) {
    states.remove(modelName)
    pendingPreSubmitWaitMs.remove(modelName)
  }

  @Synchronized
  internal fun clearAllForTest() {
    states.clear()
    pendingPreSubmitWaitMs.clear()
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
    return CompatToolHistoryEntry(toolName = toolName, status = status, payload = payload)
  }

  private fun lineValue(text: String, prefix: String): String {
    return text.lineSequence()
      .firstOrNull { it.trimStart().startsWith(prefix) }
      ?.trim()
      ?.removePrefix(prefix)
      ?.trim()
      .orEmpty()
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
      val compactPayload = truncateWithMarker(entry.payload, payloadBudget)
      if (body.isNotEmpty()) body.append("\n\n")
      body.append(entryHeader)
      body.append(compactPayload)
    }
    val combined = header + body.toString()
    return truncateWithMarker(combined, safeBudget)
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

  private fun truncateWithMarker(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val marker = "\n...[truncated by MCP204 COMPAT history budget]"
    if (maxChars <= marker.length + 16) return text.take(maxChars)
    return text.take(maxChars - marker.length) + marker
  }

  private fun extractToolCallFingerprint(text: String): String? {
    val open = text.indexOf(TOOL_CALL_OPEN_TAG_RUNTIME, ignoreCase = true)
    if (open < 0) return null
    val payloadStart = open + TOOL_CALL_OPEN_TAG_RUNTIME.length
    val close = text.indexOf(TOOL_CALL_CLOSE_TAG_RUNTIME, startIndex = payloadStart, ignoreCase = true)
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
    return (0 until array.length()).joinToString(prefix = "[", postfix = "]", separator = ",") { index ->
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
