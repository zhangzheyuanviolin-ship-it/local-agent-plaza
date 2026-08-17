/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentCompatRuntimeCoordinatorTest {
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
  fun everyTopLevelTurnUsesFreshConversationAndCarriesBoundedSessionHistory() {
    val first =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput = initialInput("列出工作区全部文件"),
        historyBudgetChars = 3200,
      )

    assertTrue(first.requiresFreshConversation)
    assertEquals(COMPAT_FRESH_REASON_TOP_LEVEL, first.freshConversationReason)
    assertEquals(1, first.userTurnIndex)
    assertEquals(0, first.sessionHistoryTurnCount)
    assertFalse(first.input.contains("SESSION_HISTORY"))

    AgentCompatRuntimeCoordinator.onGenerationCompleted(
      modelName = modelName,
      generatedText = "FIRST_TURN_FINAL_SENTINEL：工作区文件已经列出。",
    )

    val second =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput = initialInput("写一个自我介绍到 intro.txt"),
        historyBudgetChars = 3200,
      )

    assertTrue(second.requiresFreshConversation)
    assertEquals(COMPAT_FRESH_REASON_TOP_LEVEL, second.freshConversationReason)
    assertEquals(2, second.userTurnIndex)
    assertEquals(1, second.sessionHistoryTurnCount)
    assertTrue(second.input.contains("SESSION_HISTORY"))
    assertTrue(second.input.contains("FIRST_TURN_FINAL_SENTINEL"))
    assertTrue(second.input.contains("列出工作区全部文件"))
    assertTrue(second.input.contains("写一个自我介绍到 intro.txt"))
    assertTrue(second.sessionHistoryChars <= 2600)
  }

  @Test
  fun toolResultDoesNotResetWithoutObservedToolCall() {
    AgentCompatRuntimeCoordinator.prepareInput(
      modelName = modelName,
      rawInput = initialInput("读取目录"),
      historyBudgetChars = 3200,
    )

    val prepared =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput = toolResult("list_workspace", "A.txt\nB.txt"),
        historyBudgetChars = 3200,
      )

    assertFalse(prepared.requiresFreshConversation)
  }

  @Test
  fun freshContinuationKeepsEarlierToolResultsAcrossMultipleSteps() {
    AgentCompatRuntimeCoordinator.prepareInput(
      modelName = modelName,
      rawInput = initialInput("读取 A 和 B，然后比较"),
      historyBudgetChars = 3200,
    )
    AgentCompatRuntimeCoordinator.onGenerationCompleted(
      modelName,
      "<tool_call>{\"tool\":\"read_workspace_text_file\",\"arguments\":{\"path\":\"file/A.txt\"}}</tool_call>",
    )
    val first =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput = toolResult("read_workspace_text_file", "ALPHA_UNIQUE_FACT"),
        historyBudgetChars = 3200,
      )
    assertTrue(first.requiresFreshConversation)
    assertTrue(first.input.contains("ALPHA_UNIQUE_FACT"))

    AgentCompatRuntimeCoordinator.onGenerationCompleted(
      modelName,
      "<tool_call>{\"tool\":\"read_workspace_text_file\",\"arguments\":{\"path\":\"file/B.txt\"}}</tool_call>",
    )
    val second =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput = toolResult("read_workspace_text_file", "BETA_UNIQUE_FACT"),
        historyBudgetChars = 3200,
      )

    assertTrue(second.requiresFreshConversation)
    assertTrue(second.input.contains("ALPHA_UNIQUE_FACT"))
    assertTrue(second.input.contains("BETA_UNIQUE_FACT"))
    assertTrue(second.input.contains("step_count: 2"))
    assertTrue(second.input.contains("If the task is still incomplete"))
  }

  @Test
  fun historyPackingStaysInsideConfiguredBudgetAndKeepsNewestResult() {
    AgentCompatRuntimeCoordinator.prepareInput(
      modelName = modelName,
      rawInput = initialInput("多步读取"),
      historyBudgetChars = 1800,
    )

    repeat(5) { index ->
      AgentCompatRuntimeCoordinator.onGenerationCompleted(
        modelName,
        "<tool_call>{\"tool\":\"read_workspace_text_file\",\"arguments\":{\"path\":\"file/$index.txt\"}}</tool_call>",
      )
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput =
          toolResult("read_workspace_text_file", "STEP_${index}_MARKER_" + "x".repeat(1600)),
        historyBudgetChars = 1800,
      )
    }

    val snapshot = AgentCompatRuntimeCoordinator.snapshot(modelName)!!
    assertTrue(snapshot.historyChars <= 1800)
    assertTrue(snapshot.historyStepCount == 5)

    AgentCompatRuntimeCoordinator.onGenerationCompleted(
      modelName,
      "<tool_call>{\"tool\":\"read_workspace_text_file\",\"arguments\":{\"path\":\"file/final.txt\"}}</tool_call>",
    )
    val prepared =
      AgentCompatRuntimeCoordinator.prepareInput(
        modelName = modelName,
        rawInput = toolResult("read_workspace_text_file", "NEWEST_RESULT_SENTINEL"),
        historyBudgetChars = 1800,
      )
    assertTrue(prepared.input.contains("NEWEST_RESULT_SENTINEL"))
  }

  @Test
  fun thirdConsecutiveIdenticalToolCallIsBlockedBeforeExecution() {
    AgentCompatRuntimeCoordinator.prepareInput(
      modelName = modelName,
      rawInput = initialInput("搜索一次"),
      historyBudgetChars = 3200,
    )
    val call =
      "<tool_call>{\"tool\":\"search_web\",\"arguments\":{\"query\":\"same query\"}}</tool_call>"

    val first = AgentCompatRuntimeCoordinator.onGenerationCompleted(modelName, call)
    assertFalse(first.blockedRepeatedToolCall)
    AgentCompatRuntimeCoordinator.prepareInput(
      modelName,
      toolResult("search_web", "first result"),
      3200,
    )

    val second = AgentCompatRuntimeCoordinator.onGenerationCompleted(modelName, call)
    assertFalse(second.blockedRepeatedToolCall)
    AgentCompatRuntimeCoordinator.prepareInput(
      modelName,
      toolResult("search_web", "second result"),
      3200,
    )

    val third = AgentCompatRuntimeCoordinator.onGenerationCompleted(modelName, call)
    assertTrue(third.blockedRepeatedToolCall)
    assertTrue(third.repeatedToolCallCount >= 2)
  }

  private fun initialInput(request: String): String {
    return """
      COMPAT_AGENT_INSTRUCTIONS
      You are running in Qwen-compatible tool mode.
      Compatibility mode rules:
      - one tool per turn

      USER_REQUEST
      $request
    """.trimIndent()
  }

  private fun toolResult(tool: String, payload: String): String {
    return """
      TOOL_RESULT
      original_user_request: test
      tool: $tool
      status: succeeded
      payload:
      status: succeeded
      result:
      $payload

      You are in compatibility tool mode.
      Use this tool result to answer the original user request directly.
    """.trimIndent()
  }
}
