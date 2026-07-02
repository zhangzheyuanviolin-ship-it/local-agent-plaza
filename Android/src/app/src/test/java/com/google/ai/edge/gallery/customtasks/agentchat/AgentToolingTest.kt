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

import com.google.ai.edge.gallery.data.Model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolingTest {
  @Test
  fun toolModeOptionsExposeOnlyNativeAndCompat() {
    assertEquals(listOf(AgentToolModeValues.NATIVE, AgentToolModeValues.COMPAT), AgentToolModeValues.options)
    assertFalse(AgentToolModeValues.options.contains("自动"))
  }

  @Test
  fun qwenModelsDefaultToCompatToolsWhileGemmaFourUsesNativeTools() {
    assertEquals(
      ResolvedAgentToolMode.COMPAT,
      resolveAgentToolMode(Model(name = "Qwen3-8B", imported = false)),
    )
    assertEquals(
      ResolvedAgentToolMode.COMPAT,
      resolveAgentToolMode(Model(name = "Qwen3-4B-Instruct-2507", imported = false)),
    )
    assertEquals(
      ResolvedAgentToolMode.NATIVE,
      resolveAgentToolMode(Model(name = "Gemma-4-E4B-it", imported = false)),
    )
  }

  @Test
  fun parserAcceptsQwenToolCallWithThinkingAndTrailingBrace() {
    val parsed =
      parseCompatToolCall(
        """
        <think>Need search.</think>
        <tool_call>
        {"name": "exa-search", "arguments": {"query": "2026 World Cup news"}}}
        </tool_call>
        """.trimIndent()
      )

    assertEquals("exa-search", parsed?.toolName)
    assertEquals("2026 World Cup news", parsed?.arguments?.getString("query"))
  }

  @Test
  fun parserAcceptsParametersAsArguments() {
    val parsed =
      parseCompatToolCall(
        """
        <tool_call>
        {"tool":"write_workspace_text_file","parameters":{"path":"notes/a.md","content":"hello"}}
        </tool_call>
        """.trimIndent()
      )

    assertEquals("write_workspace_text_file", parsed?.toolName)
    assertEquals("notes/a.md", parsed?.arguments?.getString("path"))
  }

  @Test
  fun compatPromptAdvertisesDirectSearchToolsAndNoLoadSkillRequirement() {
    val prompt =
      buildCompatAgentInstructionPayloadForTest(
        baseSystemPrompt =
          """
          You MUST use load_skill before every task.
          {"name":"exa-search","arguments":{"query":"2026 World Cup news"}}
          """.trimIndent(),
        selectedSkillSummaries = listOf("exa-search: search the web"),
      )

    assertTrue(prompt.contains("exa-search"))
    assertTrue(prompt.contains("search_web"))
    assertFalse(prompt.contains("You MUST use load_skill before every task"))
    assertFalse(prompt.contains("2026 World Cup news"))
  }

  @Test
  fun compatToolResultPromptCompactsRawSearchPayloadForModel() {
    val prompt =
      buildCompatToolResultPrompt(
        toolName = "langsearch-search",
        originalUserRequest = "搜索美国和伊朗谈判",
        result =
          mapOf(
            "status" to "succeeded",
            "result" to
              """
              Search query: 美国和伊朗谈判的最新相关新闻\nSources: [1] 美伊谈判,最新消息进展
              URL: https:\/\/example.com\/a


              ${"Published: 2026-06-03  ".repeat(600)}
              """.trimIndent(),
          ),
      )

    assertTrue(prompt.contains("status: succeeded"))
    assertTrue(prompt.contains("美伊谈判"))
    assertFalse(prompt.contains("\\/"))
    assertTrue(prompt.length < 8000)
  }
}
