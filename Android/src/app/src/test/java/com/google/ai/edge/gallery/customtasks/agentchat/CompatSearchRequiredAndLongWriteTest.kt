package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatSearchRequiredAndLongWriteTest {
  @Test
  fun explicitChineseWebSearchGetsTinySearchControlAndFallback() {
    val input =
      """
      COMPAT_AGENT_INSTRUCTIONS
      Qwen-compatible tool mode. Reply in the user's language. Thinking is off.
      Available compatibility tools:
      - search_web {"query":"..."}

      USER_REQUEST
      联网搜索2026年世界杯决赛的相关新闻，然后详细向我汇报。
      """.trimIndent()

    val injected = CompatSearchRequiredPolicy.injectIntoCompatInput(input)
    assertTrue(injected.contains("SEARCH_REQUIRED=true"))
    assertTrue(injected.contains("CURRENT_DATE="))
    assertTrue(injected.contains("Call search_web before any factual answer"))

    val fallback = CompatSearchRequiredPolicy.buildFallbackToolCall(injected)
    assertNotNull(fallback)
    val parsed = parseCompatToolCall(requireNotNull(fallback))
    assertNotNull(parsed)
    assertEquals("search_web", parsed?.toolName)
    assertEquals(
      "联网搜索2026年世界杯决赛的相关新闻，然后详细向我汇报。",
      parsed?.arguments?.getString("query"),
    )
  }

  @Test
  fun ordinaryNonWebRequestDoesNotPaySearchControlCost() {
    val input =
      """
      COMPAT_AGENT_INSTRUCTIONS
      Qwen-compatible tool mode. Reply in the user's language. Thinking is off.
      Available compatibility tools:
      - search_web {"query":"..."}

      USER_REQUEST
      帮我总结一下刚才这段文字。
      """.trimIndent()

    val injected = CompatSearchRequiredPolicy.injectIntoCompatInput(input)
    assertFalse(injected.contains("SEARCH_REQUIRED=true"))
    assertEquals(input, injected)
  }

  @Test
  fun longWorkspaceWriteRecoversRawNewlinesAndUnescapedAsciiQuotes() {
    val article =
      """# 深度评论

第一段含有原始换行。
第二段故意包含 ASCII "quoted words"，还包含 JSON 示例 {"x":1}。
### 小标题
结尾继续保留 Markdown。"""
    val raw =
      "<tool_call>{\"tool\":\"write_workspace_file\",\"arguments\":{\"path\":\"analysis_article.txt\",\"content\":\"" +
        article +
        "\"}}</tool_call>"

    val parsed = parseCompatToolCall(raw)
    assertNotNull(parsed)
    assertEquals("write_workspace_file", parsed?.toolName)
    assertEquals("analysis_article.txt", parsed?.arguments?.getString("path"))
    assertEquals(article, parsed?.arguments?.getString("content"))
  }

  @Test
  fun searchRequirementAcceptsARealProviderSearchCall() {
    assertTrue(
      CompatSearchRequiredPolicy.hasWebSearchToolCall(
        "<|tool_call>call:exa-search{\"query\":\"世界杯决赛\"}<tool_call|>"
      )
    )
    assertFalse(
      CompatSearchRequiredPolicy.hasWebSearchToolCall(
        "<tool_call>{\"tool\":\"write_workspace_file\",\"arguments\":{\"path\":\"a.txt\",\"content\":\"x\"}}</tool_call>"
      )
    )
  }
}
