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
    assertEquals(
      ResolvedAgentToolMode.COMPAT,
      resolveAgentToolMode(Model(name = "Gemma-4-12B-it (experimental)", imported = false)),
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
  fun parserAcceptsQwenQueryOnlySearchCall() {
    val parsed =
      parseCompatToolCall(
        """<tool_call>{"query":"美国和伊朗谈判的最新相关新闻"}</tool_call>"""
      )

    assertEquals("search_web", parsed?.toolName)
    assertEquals("美国和伊朗谈判的最新相关新闻", parsed?.arguments?.getString("query"))
  }

  @Test
  fun parserAcceptsQwenToolNameAndUnclosedTag() {
    val parsed =
      parseCompatToolCall(
        """
        To search, I will call the tool.
        <tool_call> {"tool_name":"exa-search","arguments":{"query":"latest news on US and Iran negotiations"}}
        """.trimIndent()
      )

    assertEquals("exa-search", parsed?.toolName)
    assertEquals("latest news on US and Iran negotiations", parsed?.arguments?.getString("query"))
  }

  @Test
  fun parserAcceptsToolNameAsRootObjectKey() {
    val parsed =
      parseCompatToolCall(
        """
        <tool_call>
        {"search_web":{"query":"multiple countries criticize China missile tests news"}}
        </tool_call>
        """.trimIndent()
      )

    assertEquals("search_web", parsed?.toolName)
    assertEquals(
      "multiple countries criticize China missile tests news",
      parsed?.arguments?.getString("query"),
    )
  }

  @Test
  fun parserUnwrapsGenericToolCallWrapper() {
    val parsed =
      parseCompatToolCall(
        """
        <tool_call>
        {"tool_call":{"name":"list_workspace","arguments":{"path":"."}}}
        </tool_call>
        """.trimIndent()
      )

    assertEquals("list_workspace", parsed?.toolName)
    assertEquals(".", parsed?.arguments?.getString("path"))
  }

  @Test
  fun parserUnwrapsStringifiedNestedArguments() {
    val parsed =
      parseCompatToolCall(
        """
        <tool_call>
        {"name":"tool_call","arguments":{"tool_name":"write_workspace_file","arguments":"{\"path\":\"notes/me.txt\",\"content\":\"hello\"}"}}
        </tool_call>
        """.trimIndent()
      )

    assertEquals("write_workspace_file", parsed?.toolName)
    assertEquals("notes/me.txt", parsed?.arguments?.getString("path"))
    assertEquals("hello", parsed?.arguments?.getString("content"))
  }

  @Test
  fun compatPromptAdvertisesOnlyEnabledGenericSearchAndNoLoadSkillRequirement() {
    val prompt =
      buildCompatAgentInstructionPayloadForTest(
        baseSystemPrompt =
          """
          You MUST use load_skill before every task.
          {"name":"exa-search","arguments":{"query":"2026 World Cup news"}}
          """.trimIndent(),
        selectedSkillSummaries = listOf("exa-search: search the web"),
      )

    assertTrue(prompt.contains("search_web"))
    assertFalse(prompt.contains("write_workspace_file"))
    assertFalse(prompt.contains("- langsearch-search arguments"))
    assertFalse(prompt.contains("- tavily-search arguments"))
    assertFalse(prompt.contains("You MUST use load_skill before every task"))
    assertFalse(prompt.contains("2026 World Cup news"))
    assertFalse(prompt.contains("用户要搜索的关键词"))
    assertFalse(prompt.contains("{\"name\""))
  }

  @Test
  fun compatPromptAdvertisesDirectWorkspaceWriteAndDeleteWhenWorkspaceEnabled() {
    val prompt =
      buildCompatAgentInstructionPayloadForTest(
        baseSystemPrompt = "You are helpful.",
        selectedSkillSummaries = listOf("file-workspace: mounted workspace"),
      )

    assertTrue(prompt.contains("list_workspace"))
    assertTrue(prompt.contains("read_workspace_text_file"))
    assertTrue(prompt.contains("write_workspace_file"))
    assertTrue(prompt.contains("delete_workspace_file"))
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
