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

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * Keeps explicit user web-search requests grounded in the live search tool with minimal prompt cost.
 *
 * The policy is deliberately narrow: it activates only when a web-search tool is actually exposed in
 * the current COMPAT instruction and the user explicitly asks for online / web / internet search.
 */
internal object CompatSearchRequiredPolicy {
  private const val CONTROL_MARKER = "SEARCH_REQUIRED=true"
  private val webSearchToolNames =
    setOf(
      "search_web",
      "web_search",
      "exa-search",
      "tavily-search",
      "langsearch-search",
      "anysearch-search",
      "anysearch_search",
      "minimax_search_web",
    )

  private val explicitSearchPatterns =
    listOf(
      Regex("(联网|网络|互联网|网上|在线).{0,10}(搜索|搜一下|查询|查一下|查找|检索|搜)", RegexOption.IGNORE_CASE),
      Regex("(搜索|搜一下|查询|查一下|查找|检索|搜).{0,10}(联网|网络|互联网|网上|在线)", RegexOption.IGNORE_CASE),
      Regex("\\b(web search|search the web|browse the web|internet search|search online|look up online)\\b", RegexOption.IGNORE_CASE),
    )

  internal fun injectIntoCompatInput(input: String): String {
    val separatorIndex = input.indexOf(COMPAT_RUNTIME_USER_REQUEST_SEPARATOR)
    if (separatorIndex < 0) return input
    val prefix = input.substring(0, separatorIndex)
    if (!prefix.contains("search_web", ignoreCase = true)) return input
    val request =
      input.substring(separatorIndex + COMPAT_RUNTIME_USER_REQUEST_SEPARATOR.length).trim()
    if (!requiresExplicitWebSearch(request)) return input
    if (prefix.contains(CONTROL_MARKER)) return input

    val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val control =
      "\n$CONTROL_MARKER; CURRENT_DATE=$currentDate\n" +
        "The user explicitly required live web search. Call search_web before any factual answer. " +
        "Do not answer from memory, knowledge cutoff, or assumptions first."
    return prefix + control + COMPAT_RUNTIME_USER_REQUEST_SEPARATOR + request
  }

  internal fun isSearchRequiredInput(input: String): Boolean {
    val separatorIndex = input.indexOf(COMPAT_RUNTIME_USER_REQUEST_SEPARATOR)
    val prefix = if (separatorIndex >= 0) input.substring(0, separatorIndex) else input
    return prefix.contains(CONTROL_MARKER)
  }

  internal fun requiresExplicitWebSearch(request: String): Boolean {
    if (request.isBlank()) return false
    return explicitSearchPatterns.any { it.containsMatchIn(request) }
  }

  internal fun hasWebSearchToolCall(rawText: String): Boolean {
    val call = CompatToolCallWireAdapter.parseFirstToolCall(rawText) ?: return false
    return call.toolName.trim().lowercase() in webSearchToolNames
  }

  /**
   * Host-level final guard. If the model ignores SEARCH_REQUIRED, route the untouched user request
   * through search_web directly instead of accepting a stale knowledge-cutoff answer.
   */
  internal fun buildFallbackToolCall(input: String): String? {
    if (!isSearchRequiredInput(input)) return null
    val separatorIndex = input.indexOf(COMPAT_RUNTIME_USER_REQUEST_SEPARATOR)
    if (separatorIndex < 0) return null
    val request =
      input.substring(separatorIndex + COMPAT_RUNTIME_USER_REQUEST_SEPARATOR.length).trim()
    if (request.isBlank()) return null
    return "<tool_call>" +
      JSONObject()
        .put("tool", "search_web")
        .put("arguments", JSONObject().put("query", request))
        .toString() +
      "</tool_call>"
  }
}
