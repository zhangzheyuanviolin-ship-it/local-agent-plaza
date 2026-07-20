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
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

object AgentWebPageExtractSupport {
  private val client =
    OkHttpClient.Builder()
      .followRedirects(true)
      .followSslRedirects(true)
      .connectTimeout(30, TimeUnit.SECONDS)
      .readTimeout(45, TimeUnit.SECONDS)
      .build()

  fun extract(url: String, maxChars: Int): org.json.JSONObject {
    val cleanUrl = requireHttpUrl(url)
    val request =
      Request.Builder()
        .url(cleanUrl)
        .header(
          "User-Agent",
          "Mozilla/5.0 (Android; LocalAgentPlaza) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36",
        )
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()
    client.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw IOException("Web page HTTP ${response.code}: ${body.take(500)}")
      }
      val contentType = response.header("Content-Type").orEmpty()
      if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true) && !contentType.contains("xml", ignoreCase = true) && !contentType.contains("text", ignoreCase = true)) {
        throw IOException("Unsupported page content type: $contentType")
      }
      val doc = Jsoup.parse(body, response.request.url.toString())
      cleanup(doc)
      val main = selectMainContent(doc)
      val title = doc.title().trim()
      val description = doc.selectFirst("meta[name=description],meta[property=og:description]")?.attr("content").orEmpty().trim()
      val markdown = compact(markdownFrom(main, title, description), maxChars.coerceIn(1000, 30000))
      return org.json.JSONObject()
        .put("status", "succeeded")
        .put("operation", "extract_web_page")
        .put("url", cleanUrl)
        .put("final_url", response.request.url.toString())
        .put("title", title)
        .put("description", description)
        .put("content", markdown)
        .put("text_chars", markdown.length)
        .put("truncated", markdown.endsWith("\n...[TRUNCATED]"))
    }
  }

  private fun cleanup(doc: Document) {
    doc.select("script,style,noscript,svg,canvas,iframe,nav,header,footer,aside,form,button,input,select,textarea").remove()
    doc.select("[aria-hidden=true],.advertisement,.ads,.ad,.cookie,.popup,.modal,.breadcrumb,.share,.social").remove()
  }

  private fun selectMainContent(doc: Document): Element {
    val candidates = doc.select("article,main,[role=main],#content,#main,.content,.main,.article,.post,.post-content,.entry-content")
    return candidates.maxByOrNull { it.text().length } ?: doc.body()
  }

  private fun markdownFrom(root: Element, title: String, description: String): String {
    val lines = mutableListOf<String>()
    if (title.isNotBlank()) lines += "# $title"
    if (description.isNotBlank() && description != title) lines += description
    appendMarkdown(root, lines, depth = 0)
    return lines.joinToString("\n").replace(Regex("\\n{3,}"), "\n\n").trim()
  }

  private fun appendMarkdown(element: Element, lines: MutableList<String>, depth: Int) {
    when (element.tagName().lowercase()) {
      "h1", "h2", "h3", "h4" -> {
        val level = element.tagName().drop(1).toIntOrNull()?.coerceIn(1, 4) ?: 2
        addLine(lines, "${"#".repeat(level)} ${element.ownText().ifBlank { element.text() }}")
      }
      "p" -> addLine(lines, element.text())
      "li" -> addLine(lines, "- ${element.text()}")
      "blockquote" -> addLine(lines, "> ${element.text()}")
      "pre", "code" -> addLine(lines, element.text())
      "table" -> addLine(lines, tableText(element))
      else -> {
        if (element.childNodeSize() == 1 && element.childNode(0) is TextNode) {
          addLine(lines, element.text())
        }
      }
    }
    if (depth > 80) return
    element.children().forEach { appendMarkdown(it, lines, depth + 1) }
  }

  private fun tableText(table: Element): String {
    return table.select("tr").joinToString("\n") { row ->
      row.select("th,td").joinToString(" | ") { it.text().trim() }
    }.trim()
  }

  private fun addLine(lines: MutableList<String>, value: String) {
    val clean = value.replace(Regex("\\s+"), " ").trim()
    if (clean.length < 2) return
    if (lines.lastOrNull() == clean) return
    lines += clean
  }

  private fun requireHttpUrl(url: String): String {
    val clean = url.trim()
    if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
      throw IOException("Only http(s) URLs are supported.")
    }
    return clean
  }

  private fun compact(value: String, maxChars: Int): String {
    val normalized = value.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    return if (normalized.length <= maxChars) normalized else normalized.take(maxChars) + "\n...[TRUNCATED]"
  }
}
