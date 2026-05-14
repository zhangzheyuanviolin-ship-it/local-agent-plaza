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

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "AGDiagnostics"
private const val LOG_DIR_NAME = "agent_diagnostics"
private const val LOG_FILE_NAME = "latest_agent_chat.log"
private const val MAX_DETAIL_CHARS = 4000

object AgentDiagnosticsLogger {
  private val timestampFormatter =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }

  @Synchronized
  fun log(
    context: Context,
    category: String,
    message: String,
    detail: String = "",
  ) {
    val line =
      buildString {
        append(timestampFormatter.format(Date()))
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
    appendLine(resolveInternalLogFile(context), line)
    context.getExternalFilesDir(LOG_DIR_NAME)?.let { externalDir ->
      appendLine(File(externalDir, LOG_FILE_NAME), line)
    }
  }

  fun logJson(context: Context, category: String, message: String, rawJson: String) {
    log(context = context, category = category, message = message, detail = rawJson)
  }

  fun getInternalLogPath(context: Context): String {
    return resolveInternalLogFile(context).absolutePath
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
      file.appendText(line, Charsets.UTF_8)
    }.onFailure { error ->
      Log.e(TAG, "Failed to append diagnostics log to ${file.absolutePath}", error)
    }
  }

  private fun sanitize(input: String): String {
    return input.replace("\r", "\\r").replace("\n", "\\n").take(MAX_DETAIL_CHARS)
  }
}
