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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

private const val TAG = "AGDiagnostics"
private const val LOG_DIR_NAME = "agent_diagnostics"
private const val LOG_FILE_NAME = "latest_agent_chat.log"
private const val MAX_DETAIL_CHARS = 16000
private const val MAX_COPY_CHARS = 120000
private const val MAX_LOG_FILE_BYTES = 512 * 1024L
private const val TRIMMED_LOG_FILE_BYTES = 256 * 1024

object AgentDiagnosticsLogger {
  private val timestampFormatter =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }
  private val logWriter =
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "AgentDiagnosticsIO").apply { isDaemon = true }
    }

  fun log(
    context: Context,
    category: String,
    message: String,
    detail: String = "",
  ) {
    val performanceCategory =
      if (category == "tool.run_configured_intent.flattened") {
        "tool.run_configured_intent.done"
      } else {
        category
      }
    AgentPerformanceCoordinator.observeDiagnosticEvent(
      category = performanceCategory,
      detailChars = detail.length,
    )

    val line =
      buildString {
        append(formatTimestamp(Date()))
        append(" [")
        append(category)
        append("] ")
        append(message)
        val sanitizedDetail = sanitize(detail)
        if (sanitizedDetail.isNotBlank()) {
          append(" | ")
          append(sanitizedDetail)
        }
        append('\n')
      }

    Log.d(TAG, line.trimEnd())
    val appContext = context.applicationContext
    logWriter.execute {
      appendLine(resolveInternalLogFile(appContext), line)
      appContext.getExternalFilesDir(LOG_DIR_NAME)?.let { externalDir ->
        appendLine(File(externalDir, LOG_FILE_NAME), line)
      }
    }
  }

  fun logJson(context: Context, category: String, message: String, rawJson: String) {
    log(context = context, category = category, message = message, detail = rawJson)
  }

  fun logThrowable(
    context: Context,
    category: String,
    message: String,
    throwable: Throwable,
    extra: String = "",
  ) {
    val detail =
      buildString {
        if (extra.isNotBlank()) {
          append(extra)
          append('\n')
        }
        append("exception_class=")
        append(throwable.javaClass.name)
        append('\n')
        append("exception_message=")
        append(throwable.message.orEmpty())
        append('\n')
        append(throwable.stackTraceToString())
      }
    log(context = context, category = category, message = message, detail = detail)
  }

  fun getInternalLogPath(context: Context): String {
    return resolveInternalLogFile(context.applicationContext).absolutePath
  }

  fun readLatestText(context: Context): String {
    val file = resolveInternalLogFile(context.applicationContext)
    if (!file.exists()) return "暂无诊断信息。"
    return runCatching {
        val text = file.readText(Charsets.UTF_8)
        if (text.length <= MAX_COPY_CHARS) text else text.takeLast(MAX_COPY_CHARS)
      }
      .getOrElse { error -> "读取诊断信息失败：${error.message ?: error.javaClass.simpleName}" }
  }

  fun copyLatestToClipboard(context: Context): Int {
    val text = readLatestText(context)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("本地智能体广场诊断信息", text))
    return text.length
  }

  private fun formatTimestamp(date: Date): String {
    return synchronized(timestampFormatter) { timestampFormatter.format(date) }
  }

  private fun resolveInternalLogFile(context: Context): File {
    val dir = context.filesDir.resolve(LOG_DIR_NAME)
    if (!dir.exists()) {
      dir.mkdirs()
    }
    return dir.resolve(LOG_FILE_NAME)
  }

  private fun appendLine(file: File, line: String) {
    runCatching {
      file.parentFile?.mkdirs()
      if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
        val existing = file.readText(Charsets.UTF_8)
        file.writeText(existing.takeLast(TRIMMED_LOG_FILE_BYTES), Charsets.UTF_8)
      }
      file.appendText(line, Charsets.UTF_8)
    }.onFailure { error ->
      Log.e(TAG, "Failed to append diagnostics log to ${file.absolutePath}", error)
    }
  }

  private fun sanitize(input: String): String {
    return input.replace("\r", "\\r").replace("\n", "\\n").take(MAX_DETAIL_CHARS)
  }
}
