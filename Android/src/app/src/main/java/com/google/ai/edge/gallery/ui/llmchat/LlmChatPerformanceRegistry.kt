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

package com.google.ai.edge.gallery.ui.llmchat

import android.os.SystemClock

data class LlmRuntimePerformanceSnapshot(
  val engineInitMs: Double? = null,
  val conversationInitMs: Double? = null,
  val lastConversationResetMs: Double? = null,
  val conversationResetCount: Int = 0,
  val configuredContextWindow: Int? = null,
  val engineMaxNumTokens: Int? = null,
  val maxOutputTokens: Int? = null,
  val accelerator: String? = null,
  val speculativeDecodingEnabled: Boolean? = null,
  val recordedAtElapsedMs: Long? = null,
)

/**
 * Low-overhead process-local timing registry used by MCP202 diagnostics.
 *
 * This registry never stores prompts, tool results, file contents, paths, credentials, or model
 * output. It only retains numeric runtime metadata keyed by model name.
 */
object LlmChatPerformanceRegistry {
  private val snapshots = mutableMapOf<String, LlmRuntimePerformanceSnapshot>()

  @Synchronized
  fun recordInitialization(
    modelName: String,
    engineInitMs: Double,
    conversationInitMs: Double,
    configuredContextWindow: Int,
    engineMaxNumTokens: Int,
    maxOutputTokens: Int,
    accelerator: String,
    speculativeDecodingEnabled: Boolean,
  ) {
    val previous = snapshots[modelName]
    snapshots[modelName] =
      LlmRuntimePerformanceSnapshot(
        engineInitMs = engineInitMs,
        conversationInitMs = conversationInitMs,
        lastConversationResetMs = previous?.lastConversationResetMs,
        conversationResetCount = previous?.conversationResetCount ?: 0,
        configuredContextWindow = configuredContextWindow,
        engineMaxNumTokens = engineMaxNumTokens,
        maxOutputTokens = maxOutputTokens,
        accelerator = accelerator,
        speculativeDecodingEnabled = speculativeDecodingEnabled,
        recordedAtElapsedMs = SystemClock.elapsedRealtime(),
      )
  }

  @Synchronized
  fun recordConversationReset(modelName: String, elapsedMs: Double) {
    val previous = snapshots[modelName] ?: LlmRuntimePerformanceSnapshot()
    snapshots[modelName] =
      previous.copy(
        lastConversationResetMs = elapsedMs,
        conversationResetCount = previous.conversationResetCount + 1,
        recordedAtElapsedMs = SystemClock.elapsedRealtime(),
      )
  }

  @Synchronized fun snapshot(modelName: String): LlmRuntimePerformanceSnapshot? = snapshots[modelName]

  @Synchronized
  fun clear(modelName: String) {
    snapshots.remove(modelName)
  }
}

internal fun elapsedMsSince(startNanos: Long): Double {
  return (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
}
