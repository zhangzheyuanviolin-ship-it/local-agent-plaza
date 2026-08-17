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

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentCompatRuntimeCoordinatorXlsxTest {
  private val modelName = "Gemma-4-12B-it (experimental)"

  @Before
  fun setUp() {
    AgentCompatRuntimeCoordinator.clearAllForTest()
  }

  @After
  fun tearDown() {
    AgentCompatRuntimeCoordinator.clearAllForTest()
  }

  @Test
  fun boundedHistoryPrioritizesAuthoritativeXlsxRowFacts() {
    val initial =
      """
      COMPAT_AGENT_INSTRUCTIONS
      You are running in Qwen-compatible tool mode.

      USER_REQUEST
      汇报表格关键指标
      """.trimIndent()
    AgentCompatRuntimeCoordinator.prepareInput(modelName, initial, 1600)
    AgentCompatRuntimeCoordinator.onGenerationCompleted(
      modelName,
      "<tool_call>{\"tool\":\"read_workspace_text_file\",\"arguments\":{\"path\":\"file/report.xlsx\"}}</tool_call>",
    )

    val marker = "行事实|执行摘要!R99|核心指标 是 市场规模；2026 年数值 是 40 亿美元；CAGR 是 5.8%。"
    val rawResult =
      """
      TOOL_RESULT
      original_user_request: 汇报表格关键指标
      tool: read_workspace_text_file
      status: succeeded
      payload:
      status: succeeded
      result:
      ${"普通说明行\n".repeat(400)}
      $marker
      context_safety_note: Tool output was truncated before being sent to the model.

      You are in compatibility tool mode.
      Use this tool result to answer the original user request directly.
      """.trimIndent()

    val prepared = AgentCompatRuntimeCoordinator.prepareInput(modelName, rawResult, 1600)

    assertTrue(prepared.requiresFreshConversation)
    assertTrue(prepared.input.contains(marker))
    assertTrue(prepared.input.contains("context_safety_note"))
    assertTrue(prepared.historyChars <= 1600)
  }
}
