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

import org.json.JSONArray
import org.json.JSONObject

/** Converts common model-family tool-call text into the existing COMPAT JSON envelope. */
internal object CompatToolCallWireAdapter {
  internal fun normalizeFirstToolCall(rawText: String): String? {
    val raw = rawText.trim()
    if (raw.isBlank()) return null
    parseCompatToolCall(raw)?.let { return canonical(it) }
    return listOfNotNull(
        parseLooseToolBlock(raw),
        parseQwen35(raw),
        parseGlm(raw),
        parseGemma(raw),
        parseMistral(raw),
        parseDeepSeek(raw),
        parseGptOss(raw),
        parsePythonTag(raw),
        parseMarkedJson(raw),
        parseInvokeXml(raw),
        parseToolUseXml(raw),
        parseBareJson(raw),
      )
      .firstOrNull()
      ?.let(::canonical)
  }

  internal fun hasStrongToolSignal(text: String): Boolean {
    val s = text.trimStart()
    if (s.isBlank()) return false
    val l = s.lowercase()
    return l.contains("<tool_call>") ||
      l.contains("<|tool_call>") ||
      l.contains("<start_function_call>") ||
      l.contains("<tool_use>") ||
      l.contains("<invoke>") ||
      l.contains("<|python_tag|>") ||
      l.contains("[tool_calls]") ||
      l.contains("[tool_call]") ||
      l.contains("<｜tool▁calls▁begin｜>") ||
      l.contains("<｜tool▁call▁begin｜>") ||
      l.contains("<|start|>assistant to=") ||
      l.contains("to=functions.") ||
      l.contains("<|action_start|><|plugin|>") ||
      l.contains("<|start_of_tool_calls_token|>") ||
      looksLikeToolJson(s)
  }

  private fun canonical(call: ParsedCompatToolCall): String {
    val name = normalizeName(call.toolName)
    if (name.isBlank()) return ""
    return "<tool_call>" + JSONObject().put("tool", name).put("arguments", call.arguments) + "</tool_call>"
  }

  private fun normalizeName(value: String): String {
    var name = value.trim().trim('`', '"', '\'', ' ')
    for (prefix in listOf("functions.", "function.", "tools.", "tool.")) {
      if (name.startsWith(prefix, ignoreCase = true)) {
        name = name.substring(prefix.length)
        break
      }
    }
    return name.removePrefix("call:").trim()
  }

  // Generic <tool_call> variants with relaxed JSON, unquoted keys, or call:NAME{...}.
  private fun parseLooseToolBlock(text: String): ParsedCompatToolCall? {
    val open = text.indexOf("<tool_call>", ignoreCase = true)
    if (open >= 0) {
      val tail = text.substring(open + "<tool_call>".length).trimStart()
      Regex("^(?:call:)?([^\\s<{]+)\\s*\\{").find(tail)?.let { named ->
        firstBalanced(tail.substring(named.range.last))?.let { body ->
          flexibleObject(body)?.let { return ParsedCompatToolCall(named.groupValues[1], it) }
        }
      }
      firstBalanced(tail)?.let { body ->
        flexibleObject(body)?.let(::jsonObjectShape)?.let { return it }
      }
    }
    val bareCall = Regex("^\\s*call:([^\\s{]+)", RegexOption.IGNORE_CASE).find(text) ?: return null
    val body = firstBalanced(text.substring(bareCall.range.last + 1)) ?: return null
    return flexibleObject(body)?.let { ParsedCompatToolCall(bareCall.groupValues[1], it) }
  }

  // Qwen3.5: <tool_call><function=name><parameter=key>value</parameter>...</function></tool_call>
  private fun parseQwen35(text: String): ParsedCompatToolCall? {
    val m = Regex("<tool_call>\\s*<function=([^>\\s]+)>\\s*(.*?)</function>\\s*</tool_call>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text) ?: return null
    val args = JSONObject()
    Regex("<parameter=([^>]+)>\\s*(.*?)\\s*</parameter>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(m.groupValues[2]).forEach {
      val key = it.groupValues[1].trim()
      if (key.isNotBlank()) args.put(key, scalar(it.groupValues[2]))
    }
    return ParsedCompatToolCall(m.groupValues[1].trim(), args)
  }

  // GLM: <tool_call>name<arg_key>k</arg_key><arg_value>v</arg_value>...</tool_call>
  private fun parseGlm(text: String): ParsedCompatToolCall? {
    val m = Regex("<tool_call>\\s*([^<\\s{}]+)\\s*(.*?)</tool_call>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text) ?: return null
    if (!m.groupValues[2].contains("<arg_key>", ignoreCase = true)) return null
    val args = JSONObject()
    Regex("<arg_key>\\s*(.*?)\\s*</arg_key>\\s*<arg_value>\\s*(.*?)\\s*</arg_value>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(m.groupValues[2]).forEach {
      val key = it.groupValues[1].trim()
      if (key.isNotBlank()) args.put(key, scalar(it.groupValues[2]))
    }
    return ParsedCompatToolCall(m.groupValues[1].trim(), args)
  }

  // Gemma 4 / FunctionGemma native envelopes. Handles JSON and Gemma's unquoted-key syntax.
  private fun parseGemma(text: String): ParsedCompatToolCall? {
    val patterns = listOf(
      Regex("<\\|tool_call>\\s*call:([^\\s{]+)", RegexOption.IGNORE_CASE),
      Regex("<start_function_call>\\s*call:([^\\s{]+)", RegexOption.IGNORE_CASE),
    )
    for (re in patterns) {
      val m = re.find(text) ?: continue
      val body = firstBalanced(text.substring(m.range.last + 1)) ?: continue
      flexibleObject(body)?.let { return ParsedCompatToolCall(m.groupValues[1], it) }
    }
    return null
  }

  // Mistral current: [TOOL_CALLS]name[ARGS]{...}; legacy variants use JSON list/object.
  private fun parseMistral(text: String): ParsedCompatToolCall? {
    val current = Regex("\\[TOOL_CALLS]\\s*([^\\[\\s]+)\\s*\\[ARGS]", RegexOption.IGNORE_CASE).find(text)
    if (current != null) {
      val body = firstBalanced(text.substring(current.range.last + 1))
      if (body != null) flexibleObject(body)?.let { return ParsedCompatToolCall(current.groupValues[1], it) }
    }
    val i = text.indexOf("[TOOL_CALLS]", ignoreCase = true)
    if (i < 0) return null
    return firstBalanced(text.substring(i + 12))?.let(::jsonShape)
  }

  // DeepSeek-V3 special-token format.
  private fun parseDeepSeek(text: String): ParsedCompatToolCall? {
    val m = Regex("<｜tool▁call▁begin｜>\\s*(?:function)?\\s*<｜tool▁sep｜>\\s*([^\\n\\r]+)",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text) ?: return null
    val body = firstBalanced(text.substring(m.range.last + 1)) ?: return null
    return flexibleObject(body)?.let { ParsedCompatToolCall(m.groupValues[1].trim(), it) }
  }

  // GPT-OSS Harmony: assistant to=functions.NAME ... <|message|>{...}<|call|>.
  private fun parseGptOss(text: String): ParsedCompatToolCall? {
    val m = Regex("to=(?:functions\\.)?([^<\\s]+)<\\|channel\\|>commentary.*?<\\|message\\|>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text) ?: return null
    val body = firstBalanced(text.substring(m.range.last + 1)) ?: return null
    return flexibleObject(body)?.let { ParsedCompatToolCall(m.groupValues[1], it) }
  }

  private fun parsePythonTag(text: String): ParsedCompatToolCall? {
    val i = text.indexOf("<|python_tag|>", ignoreCase = true)
    if (i < 0) return null
    return firstBalanced(text.substring(i + 14))?.let(::jsonShape)
  }

  private fun parseMarkedJson(text: String): ParsedCompatToolCall? {
    for (marker in listOf("<|action_start|><|plugin|>", "<|START_OF_TOOL_CALLS_TOKEN|>", "[TOOL_CALL]")) {
      val i = text.indexOf(marker, ignoreCase = true)
      if (i >= 0) firstBalanced(text.substring(i + marker.length))?.let(::jsonShape)?.let { return it }
    }
    return null
  }

  private fun parseInvokeXml(text: String): ParsedCompatToolCall? {
    val m = Regex("<invoke>\\s*<tool_name>\\s*(.*?)\\s*</tool_name>\\s*<parameters>\\s*(.*?)\\s*</parameters>\\s*</invoke>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text) ?: return null
    val body = m.groupValues[2].trim()
    flexibleObject(body)?.let { return ParsedCompatToolCall(m.groupValues[1].trim(), it) }
    val args = JSONObject()
    Regex("<([A-Za-z_][A-Za-z0-9_.-]*)>\\s*(.*?)\\s*</\\1>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach {
      args.put(it.groupValues[1], scalar(it.groupValues[2]))
    }
    return ParsedCompatToolCall(m.groupValues[1].trim(), args)
  }

  private fun parseToolUseXml(text: String): ParsedCompatToolCall? {
    val m = Regex("<tool_use>\\s*(\\{.*?\\})\\s*</tool_use>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text) ?: return null
    return jsonShape(m.groupValues[1])
  }

  private fun parseBareJson(text: String): ParsedCompatToolCall? {
    if (!looksLikeToolJson(text)) return null
    return firstBalanced(text)?.let(::jsonShape)
  }

  private fun jsonShape(raw: String): ParsedCompatToolCall? {
    val s = raw.trim()
    if (s.startsWith("[")) {
      val a = runCatching { JSONArray(s) }.getOrNull() ?: return null
      return a.optJSONObject(0)?.let(::jsonObjectShape)
    }
    return runCatching { JSONObject(s) }.getOrNull()?.let(::jsonObjectShape)
  }

  private fun jsonObjectShape(root: JSONObject): ParsedCompatToolCall? {
    root.optJSONArray("tool_calls")?.optJSONObject(0)?.let(::jsonObjectShape)?.let { return it }
    root.optJSONObject("tool_call")?.let(::jsonObjectShape)?.let { return it }
    root.optJSONObject("function_call")?.let(::jsonObjectShape)?.let { return it }
    root.optJSONObject("function")?.let { fn ->
      val name = fn.optString("name").ifBlank { fn.optString("tool_name") }
      if (name.isNotBlank()) return ParsedCompatToolCall(name, arguments(fn))
    }
    val name = listOf("tool", "name", "tool_name", "function_name", "function").asSequence()
      .map { root.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    if (name.isNotBlank() && listOf("arguments", "parameters", "args", "input").any(root::has)) {
      return ParsedCompatToolCall(name, arguments(root))
    }
    val keys = mutableListOf<String>()
    root.keys().forEachRemaining(keys::add)
    if (keys.size == 1) (root.opt(keys[0]) as? JSONObject)?.let { return ParsedCompatToolCall(keys[0], it) }
    return null
  }

  private fun arguments(obj: JSONObject): JSONObject {
    for (key in listOf("arguments", "parameters", "args", "input")) {
      when (val v = obj.opt(key)) {
        is JSONObject -> return v
        is String -> flexibleObject(v)?.let { return it }
      }
    }
    return JSONObject()
  }

  private fun flexibleObject(raw: String): JSONObject? {
    runCatching { JSONObject(raw.trim()) }.getOrNull()?.let { return it }
    return runCatching { LooseObjectParser(raw.trim()).parse() }.getOrNull()
  }

  private fun scalar(raw: String): Any {
    val s = raw.trim()
    runCatching { JSONObject(s) }.getOrNull()?.let { return it }
    runCatching { JSONArray(s) }.getOrNull()?.let { return it }
    if (s.equals("true", true)) return true
    if (s.equals("false", true)) return false
    if (s.equals("null", true)) return JSONObject.NULL
    s.toLongOrNull()?.let { return it }
    s.toDoubleOrNull()?.let { return it }
    return if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) s.substring(1, s.length - 1) else s
  }

  private fun looksLikeToolJson(text: String): Boolean {
    val s = text.trimStart()
    if (!s.startsWith("{") && !s.startsWith("[")) return false
    val p = s.take(500).lowercase()
    return p.contains("\"tool_calls\"") || ((p.contains("\"arguments\"") || p.contains("\"parameters\"") || p.contains("\"input\"")) &&
      (p.contains("\"name\"") || p.contains("\"tool\"") || p.contains("\"tool_name\"")))
  }

  private fun firstBalanced(text: String): String? {
    val oi = text.indexOf('{').takeIf { it >= 0 }
    val ai = text.indexOf('[').takeIf { it >= 0 }
    val start = listOfNotNull(oi, ai).minOrNull() ?: return null
    val open = text[start]
    val close = if (open == '{') '}' else ']'
    var depth = 0
    var quoted = false
    var escape = false
    for (i in start until text.length) {
      val c = text[i]
      if (escape) { escape = false; continue }
      if (c == '\\' && quoted) { escape = true; continue }
      if (c == '"') { quoted = !quoted; continue }
      if (quoted) continue
      if (c == open) depth++
      if (c == close && --depth == 0) return text.substring(start, i + 1)
    }
    return null
  }

  /** Minimal recursive parser for Gemma's {key:<|"|>value<|"|>} / <escape> syntax. */
  private class LooseObjectParser(private val s: String) {
    private var i = 0
    fun parse(): JSONObject {
      ws(); need('{'); val o = JSONObject(); ws(); if (peek() == '}') { i++; return o }
      while (i < s.length) {
        val k = key(); ws(); need(':'); ws(); o.put(k, value()); ws()
        when (peek()) { ',' -> { i++; ws() }; '}' -> { i++; return o }; else -> error("object separator") }
      }
      error("unclosed object")
    }
    private fun array(): JSONArray {
      need('['); val a = JSONArray(); ws(); if (peek() == ']') { i++; return a }
      while (i < s.length) {
        a.put(value()); ws(); when (peek()) { ',' -> { i++; ws() }; ']' -> { i++; return a }; else -> error("array separator") }
      }
      error("unclosed array")
    }
    private fun value(): Any {
      ws()
      if (s.startsWith("<|\"|>", i)) return delimited("<|\"|>")
      if (s.startsWith("<escape>", i)) return delimited("<escape>")
      return when (peek()) { '{' -> parse(); '[' -> array(); '"' -> quoted('"'); '\'' -> quoted('\''); else -> bare() }
    }
    private fun delimited(d: String): String { i += d.length; val e = s.indexOf(d, i); if (e < 0) error("delimiter"); val v = s.substring(i, e); i = e + d.length; return v }
    private fun quoted(q: Char): String {
      need(q); val out = StringBuilder(); var esc = false
      while (i < s.length) { val c = s[i++]; if (esc) { out.append(when (c) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> c }); esc = false } else if (c == '\\') esc = true else if (c == q) return out.toString() else out.append(c) }
      error("quote")
    }
    private fun bare(): Any {
      val st = i; var od = 0; var ad = 0
      while (i < s.length) { val c = s[i]; if (c == '{') od++; if (c == '}' && od == 0 && ad == 0) break else if (c == '}') od--; if (c == '[') ad++; if (c == ']' && od == 0 && ad == 0) break else if (c == ']') ad--; if (c == ',' && od == 0 && ad == 0) break; i++ }
      val v = s.substring(st, i).trim(); if (v.equals("true", true)) return true; if (v.equals("false", true)) return false; if (v.equals("null", true)) return JSONObject.NULL; v.toLongOrNull()?.let { return it }; v.toDoubleOrNull()?.let { return it }; return v
    }
    private fun key(): String { ws(); return if (peek() == '"') quoted('"') else if (peek() == '\'') quoted('\'') else { val st = i; while (i < s.length && s[i] != ':') i++; s.substring(st, i).trim().ifBlank { error("key") } } }
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun need(c: Char) { if (peek() != c) error("expected $c"); i++ }
    private fun peek(): Char? = s.getOrNull(i)
  }
}

/** Buffers tool-looking COMPAT output so raw model-specific markup never lands in the chat UI. */
internal class CompatToolCallStreamGate(private val enabled: Boolean) {
  internal data class FinishResult(val runtimeText: String, val uiTail: String)
  private enum class Mode { START, PASS, TOOL }
  private var mode = if (enabled) Mode.START else Mode.PASS
  private val buffered = StringBuilder()

  fun accept(text: String): String {
    if (!enabled) return text
    when (mode) {
      Mode.TOOL -> { buffered.append(text); return "" }
      Mode.PASS -> {
        if (CompatToolCallWireAdapter.hasStrongToolSignal(text)) { mode = Mode.TOOL; buffered.append(text); return "" }
        return text
      }
      Mode.START -> {
        buffered.append(text)
        val t = buffered.toString().trimStart()
        if (t.isBlank()) return ""
        if (CompatToolCallWireAdapter.hasStrongToolSignal(t)) { mode = Mode.TOOL; return "" }
        if (t.startsWith("<think>", ignoreCase = true)) { mode = Mode.PASS; return buffered.toString().also { buffered.clear() } }
        if (t.firstOrNull() !in setOf('<', '[', '{') || buffered.length >= 320) { mode = Mode.PASS; return buffered.toString().also { buffered.clear() } }
        return ""
      }
    }
  }

  fun finish(rawText: String): FinishResult {
    if (!enabled) return FinishResult(rawText, "")
    val canonical = CompatToolCallWireAdapter.normalizeFirstToolCall(rawText)
    if (!canonical.isNullOrBlank()) return FinishResult(canonical, if (mode == Mode.PASS) "\n$canonical" else canonical)
    return FinishResult(rawText, if (mode == Mode.START || mode == Mode.TOOL) buffered.toString() else "")
  }
}
