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

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object AgentAnySearchSupport {
  private const val ENDPOINT = "https://api.anysearch.com/mcp"
  private const val CLIENT_HEADER = "local-agent-plaza/1.0"
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(30, TimeUnit.SECONDS)
      .build()

  fun search(
    apiKey: String,
    config: AnySearchConfig,
    query: String,
    domain: String,
    subDomain: String,
    subDomainParams: JSONObject?,
    maxResults: Int?,
  ): JSONObject {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) throw IOException("AnySearch query is required.")
    val arguments =
      JSONObject()
        .put("query", cleanQuery)
        .put("max_results", maxResults?.coerceIn(1, 10) ?: config.resultCount.coerceIn(1, 10))
    if (config.domainMode == AnySearchDomainMode.VERTICAL_ALLOWED && domain.isNotBlank()) {
      arguments.put("domain", domain.trim().lowercase(Locale.US))
      if (subDomain.isNotBlank()) arguments.put("sub_domain", subDomain.trim())
      if (subDomainParams != null && subDomainParams.length() > 0) {
        arguments.put("sub_domain_params", subDomainParams)
      }
    }
    val text = callTool(apiKey, "search", arguments)
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "anysearch_search")
      .put("query", cleanQuery)
      .put("domain", arguments.optString("domain"))
      .put("sub_domain", arguments.optString("sub_domain"))
      .put("content", compactText(text, 12000))
      .put("truncated", text.length > 12000)
  }

  fun extract(apiKey: String, url: String): JSONObject {
    val cleanUrl = requireHttpUrl(url)
    val text = callTool(apiKey, "extract", JSONObject().put("url", cleanUrl))
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "anysearch_extract")
      .put("url", cleanUrl)
      .put("content", compactText(text, 16000))
      .put("truncated", text.length > 16000)
  }

  fun getSubDomains(apiKey: String, domains: List<String>, domain: String): JSONObject {
    val arguments = JSONObject()
    val cleanDomains = domains.map { it.trim().lowercase(Locale.US) }.filter { it.isNotBlank() }.take(5)
    if (cleanDomains.isNotEmpty()) {
      arguments.put("domains", JSONArray(cleanDomains))
    } else {
      val cleanDomain = domain.trim().lowercase(Locale.US)
      if (cleanDomain.isBlank()) throw IOException("AnySearch domain is required.")
      arguments.put("domain", cleanDomain)
    }
    val text = callTool(apiKey, "get_sub_domains", arguments)
    return JSONObject()
      .put("status", "succeeded")
      .put("operation", "anysearch_get_sub_domains")
      .put("content", compactText(text, 10000))
      .put("truncated", text.length > 10000)
  }

  private fun callTool(apiKey: String, toolName: String, arguments: JSONObject): String {
    val body =
      JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", "tools/call")
        .put("params", JSONObject().put("name", toolName).put("arguments", arguments))
    val requestBuilder =
      Request.Builder()
        .url(ENDPOINT)
        .header("Content-Type", "application/json")
        .header("X-Anysearch-Client", CLIENT_HEADER)
        .post(body.toString().toRequestBody(jsonMediaType))
    if (apiKey.trim().isNotBlank()) {
      requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
    }
    client.newCall(requestBuilder.build()).execute().use { response ->
      val responseBody = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw IOException("AnySearch HTTP ${response.code}: ${responseBody.take(1000)}")
      }
      val json = runCatching { JSONObject(responseBody) }.getOrNull()
        ?: throw IOException("AnySearch returned non-JSON response: ${responseBody.take(1000)}")
      json.optJSONObject("error")?.let { error ->
        throw IOException(error.optString("message").ifBlank { error.toString() })
      }
      val content = json.optJSONObject("result")?.optJSONArray("content") ?: JSONArray()
      for (i in 0 until content.length()) {
        val item = content.optJSONObject(i) ?: continue
        if (item.optString("type") == "text") return item.optString("text")
      }
      return json.optJSONObject("result")?.toString(2).orEmpty()
    }
  }

  private fun requireHttpUrl(url: String): String {
    val clean = url.trim()
    if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
      throw IOException("Only http(s) URLs are supported.")
    }
    return clean
  }

  private fun compactText(value: String, maxChars: Int): String {
    val normalized = value.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "\n...[TRUNCATED]"
  }
}
