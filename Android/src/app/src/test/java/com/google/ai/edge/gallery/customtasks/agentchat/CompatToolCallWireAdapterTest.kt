package com.google.ai.edge.gallery.customtasks.agentchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatToolCallWireAdapterTest {
  private fun parsed(raw: String): ParsedCompatToolCall {
    val canonical = CompatToolCallWireAdapter.normalizeFirstToolCall(raw)
    assertNotNull("Expected recognized tool call: $raw", canonical)
    return requireNotNull(parseCompatToolCall(requireNotNull(canonical)))
  }

  @Test
  fun normalizesObservedGemma4ExaCall() {
    val call = parsed("<|tool_call>call:exa-search{\"query\":\"电影奥德赛 上映 最新新闻\"}<tool_call|>")
    assertEquals("exa-search", call.toolName)
    assertEquals("电影奥德赛 上映 最新新闻", call.arguments.getString("query"))
  }

  @Test
  fun normalizesOfficialGemmaAndFunctionGemmaArguments() {
    val gemma = parsed("<|tool_call>call:media_audio_concat{input_paths:[<|\"|>media/a.mp3<|\"|>,<|\"|>media/b.mp3<|\"|>],output_path:<|\"|>media/combined.mp3<|\"|>}<tool_call|>")
    assertEquals("media_audio_concat", gemma.toolName)
    assertEquals("media/a.mp3", gemma.arguments.getJSONArray("input_paths").getString(0))

    val functionGemma = parsed("<start_function_call>call:query_weather{location:<escape>昆明<escape>,mode:<escape>week<escape>}<end_function_call>")
    assertEquals("query_weather", functionGemma.toolName)
    assertEquals("昆明", functionGemma.arguments.getString("location"))
  }

  @Test
  fun normalizesQwen35AndGlmXmlFormats() {
    val qwen = parsed("<tool_call><function=write_workspace_file><parameter=path>file/test.txt</parameter><parameter=content>hello</parameter></function></tool_call>")
    assertEquals("write_workspace_file", qwen.toolName)
    assertEquals("hello", qwen.arguments.getString("content"))

    val glm = parsed("<tool_call>query_weather<arg_key>location</arg_key><arg_value>上海</arg_value><arg_key>mode</arg_key><arg_value>24h</arg_value></tool_call>")
    assertEquals("query_weather", glm.toolName)
    assertEquals("24h", glm.arguments.getString("mode"))
  }

  @Test
  fun normalizesMistralDeepSeekAndGptOssFormats() {
    val mistral = parsed("[TOOL_CALLS]search_web[ARGS]{\"query\":\"latest AI news\"}</s>")
    assertEquals("search_web", mistral.toolName)

    val deepSeek = parsed("<｜tool▁calls▁begin｜><｜tool▁call▁begin｜>function<｜tool▁sep｜>search_web\n```json\n{\"query\":\"DeepSeek test\"}\n```<｜tool▁call▁end｜><｜tool▁calls▁end｜>")
    assertEquals("DeepSeek test", deepSeek.arguments.getString("query"))

    val gptOss = parsed("<|start|>assistant to=functions.search_web<|channel|>commentary json<|message|>{\"query\":\"GPT OSS test\"}<|call|>")
    assertEquals("search_web", gptOss.toolName)
  }

  @Test
  fun normalizesPythonTagOpenAiAndLegacyWrappers() {
    val llama = parsed("<|python_tag|>{\"name\":\"delete_workspace_file\",\"arguments\":{\"path\":\"file/a.txt\"}}")
    assertEquals("file/a.txt", llama.arguments.getString("path"))

    val openAi = parsed("{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"minimax_search_web\",\"arguments\":\"{\\\"query\\\":\\\"OpenAI shape\\\"}\"}}]}")
    assertEquals("minimax_search_web", openAi.toolName)
    assertEquals("OpenAI shape", openAi.arguments.getString("query"))

    val invoke = parsed("<invoke><tool_name>edge_tts_synthesize</tool_name><parameters><input_path>file/story.txt</input_path><output_path>media/speech.mp3</output_path></parameters></invoke>")
    assertEquals("edge_tts_synthesize", invoke.toolName)
  }

  @Test
  fun refusesOrdinaryJsonAndProse() {
    assertNull(CompatToolCallWireAdapter.normalizeFirstToolCall("{\"query\":\"just data\",\"answer\":42}"))
    assertNull(CompatToolCallWireAdapter.normalizeFirstToolCall("这里是普通最终回答。"))
  }

  @Test
  fun streamGateSuppressesToolMarkupAndLeavesProseStreaming() {
    val toolGate = CompatToolCallStreamGate(enabled = true)
    assertEquals("", toolGate.accept("<|tool_call>call:search_web"))
    assertEquals("", toolGate.accept("{\"query\":\"news\"}<tool_call|>"))
    val finish = toolGate.finish("<|tool_call>call:search_web{\"query\":\"news\"}<tool_call|>")
    assertTrue(finish.uiTail.startsWith("<tool_call>"))
    assertEquals("search_web", requireNotNull(parseCompatToolCall(finish.runtimeText)).toolName)

    val proseGate = CompatToolCallStreamGate(enabled = true)
    assertEquals("这是一段普通回答。", proseGate.accept("这是一段普通回答。"))
    assertEquals("", proseGate.finish("这是一段普通回答。").uiTail)
  }

  @Test
  fun preservesRepresentativeCurrentToolNames() {
    val names = listOf(
      "search_web", "exa-search", "anysearch_extract", "extract_web_page", "query_weather",
      "list_workspace", "read_workspace_text_file", "write_workspace_file", "delete_workspace_file",
      "edge_tts_synthesize", "generate_agnes_image", "generate_agnes_video", "minimax_generate_text",
      "minimax_search_web", "media_image_resize", "media_audio_mix", "media_video_add_audio"
    )
    names.forEach { name -> assertEquals(name, parsed("{\"name\":\"$name\",\"arguments\":{}}").toolName) }
  }
}
