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

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Debug
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MODEL_DIAGNOSTICS_SCHEMA = "mcp224.model_lifecycle.v1"
private const val MODEL_DIAGNOSTICS_MAX_CHARS = 120_000
private const val MODEL_DIAGNOSTICS_EVENT_DETAIL_MAX_CHARS = 24_000
private const val MODEL_DIAGNOSTICS_LITERT_LM_VERSION = "0.15.0"
private const val MODEL_DIAGNOSTICS_LITERT_VERSION = "2.1.6"

/**
 * Copyable model lifecycle diagnostics for real-device compatibility work.
 *
 * The report intentionally avoids user prompts, tool arguments/results, workspace content, API
 * keys, access tokens, and secrets. It records model/runtime identity, lifecycle stages, timing/
 * memory snapshots, and full local exception chains needed to diagnose LiteRT-LM load failures.
 */
object ModelLifecycleDiagnostics {
  private val _reports = MutableStateFlow<Map<String, String>>(emptyMap())
  val reports = _reports.asStateFlow()

  @Synchronized
  fun startSession(context: Context, model: Model, taskId: String) {
    _reports.value =
      _reports.value.toMutableMap().apply {
        put(model.name, buildHeader(context.applicationContext, model, taskId))
      }
  }

  @Synchronized
  fun recordModel(
    context: Context,
    model: Model,
    stage: String,
    message: String,
    detail: String = "",
  ) {
    ensureHeader(context.applicationContext, model)
    appendEvent(
      modelName = model.name,
      stage = stage,
      message = message,
      detail = detail,
    )
  }

  @Synchronized
  fun record(
    context: Context,
    modelName: String,
    stage: String,
    message: String,
    detail: String = "",
  ) {
    // Context is accepted by call sites that already have one, while the event itself contains no
    // user data and can also be appended safely from callbacks that have no Android Context.
    record(
      modelName = modelName,
      stage = stage,
      message = message,
      detail = detail,
    )
  }

  @Synchronized
  fun record(
    modelName: String,
    stage: String,
    message: String,
    detail: String = "",
  ) {
    ensureMinimalHeader(modelName)
    appendEvent(modelName = modelName, stage = stage, message = message, detail = detail)
  }

  fun recordThrowable(
    context: Context,
    model: Model,
    stage: String,
    throwable: Throwable,
    detail: String = "",
  ) {
    ensureHeader(context.applicationContext, model)
    recordThrowable(
      modelName = model.name,
      stage = stage,
      throwable = throwable,
      detail = detail,
    )
  }

  fun recordThrowable(
    modelName: String,
    stage: String,
    throwable: Throwable,
    detail: String = "",
  ) {
    val combined =
      buildString {
        if (detail.isNotBlank()) {
          appendLine(detail)
        }
        appendLine("exception_class=${throwable.javaClass.name}")
        appendLine("exception_message=${throwable.message.orEmpty()}")
        append(throwable.stackTraceToString())
      }
    record(
      modelName = modelName,
      stage = stage,
      message = "FAILED",
      detail = combined,
    )
  }

  @Synchronized
  private fun ensureMinimalHeader(modelName: String) {
    if (!_reports.value[modelName].isNullOrBlank()) return
    val minimal =
      buildString {
        appendLine("=== MCP224 模型生命周期诊断 ===")
        appendLine("schema=$MODEL_DIAGNOSTICS_SCHEMA")
        appendLine("app_version=${BuildConfig.VERSION_NAME}")
        appendLine("app_version_code=${BuildConfig.VERSION_CODE}")
        appendLine("litert_lm=$MODEL_DIAGNOSTICS_LITERT_LM_VERSION")
        appendLine("litert=$MODEL_DIAGNOSTICS_LITERT_VERSION")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android_api=${Build.VERSION.SDK_INT}")
        appendLine("model_name=$modelName")
        appendLine(
          "privacy=no_user_prompt;no_tool_arguments;no_tool_result_content;no_workspace_content;no_secrets"
        )
      }
    _reports.value = _reports.value.toMutableMap().apply { put(modelName, minimal) }
  }

  @Synchronized
  private fun ensureHeader(context: Context, model: Model) {
    if (!_reports.value[model.name].isNullOrBlank()) return
    _reports.value =
      _reports.value.toMutableMap().apply {
        put(model.name, buildHeader(context, model, taskId = "unavailable"))
      }
  }

  @Synchronized
  private fun appendEvent(
    modelName: String,
    stage: String,
    message: String,
    detail: String,
  ) {
    val existing = _reports.value[modelName].orEmpty()
    val event =
      buildString {
        appendLine()
        append('[')
        append(formatWallTime(System.currentTimeMillis()))
        append("] ")
        append(stage.take(160))
        append(" | ")
        appendLine(message.replace('\n', ' ').take(1000))
        appendLine(memoryLine())
        if (detail.isNotBlank()) {
          appendLine("detail:")
          appendLine(detail.take(MODEL_DIAGNOSTICS_EVENT_DETAIL_MAX_CHARS))
        }
      }
    val merged = (existing + event).takeLastPreservingHeader(MODEL_DIAGNOSTICS_MAX_CHARS)
    _reports.value = _reports.value.toMutableMap().apply { put(modelName, merged) }
  }

  private fun buildHeader(context: Context, model: Model, taskId: String): String {
    val modelPath = runCatching { model.getPath(context) }.getOrNull().orEmpty()
    val modelFile = modelPath.takeIf { it.isNotBlank() }?.let(::File)
    val contextWindow = runCatching { model.getConfiguredContextWindow() }.getOrNull()
    val maxOutput =
      runCatching {
          model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
        }
        .getOrNull()
    val accelerator =
      runCatching {
          model.getStringConfigValue(
            key = ConfigKeys.ACCELERATOR,
            defaultValue = Accelerator.GPU.label,
          )
        }
        .getOrNull()
    val memoryClassMb =
      (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass
    return buildString {
      appendLine("=== MCP224 模型生命周期诊断 ===")
      appendLine("schema=$MODEL_DIAGNOSTICS_SCHEMA")
      appendLine("app_version=${BuildConfig.VERSION_NAME}")
      appendLine("app_version_code=${BuildConfig.VERSION_CODE}")
      appendLine("litert_lm=$MODEL_DIAGNOSTICS_LITERT_LM_VERSION")
      appendLine("litert=$MODEL_DIAGNOSTICS_LITERT_VERSION")
      appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
      appendLine("device_code=${Build.DEVICE}")
      appendLine("android_api=${Build.VERSION.SDK_INT}")
      appendLine("memory_class_mb=${memoryClassMb ?: "unavailable"}")
      appendLine("task_id=$taskId")
      appendLine("model_name=${model.name}")
      appendLine("model_display_name=${model.displayName}")
      appendLine("model_imported=${model.imported}")
      appendLine("model_file_name=${model.downloadFileName}")
      appendLine("model_file_expected_bytes=${model.totalBytes}")
      appendLine("model_path=${modelPath.ifBlank { "unavailable" }}")
      appendLine("model_file_exists=${modelFile?.isFile ?: false}")
      appendLine("model_file_actual_bytes=${modelFile?.takeIf { it.isFile }?.length() ?: -1L}")
      appendLine("configured_context_window=${contextWindow ?: "unavailable"}")
      appendLine("max_output_tokens=${maxOutput ?: "unavailable"}")
      appendLine("accelerator=${accelerator ?: "unavailable"}")
      appendLine("supports_image=${model.llmSupportImage}")
      appendLine("supports_audio=${model.llmSupportAudio}")
      appendLine(memoryLine())
      appendLine(
        "privacy=no_user_prompt;no_tool_arguments;no_tool_result_content;no_workspace_content;no_secrets"
      )
    }
  }

  private fun memoryLine(): String {
    val runtime = Runtime.getRuntime()
    val javaUsed = runtime.totalMemory() - runtime.freeMemory()
    return "memory_java_used_bytes=$javaUsed | memory_native_allocated_bytes=${Debug.getNativeHeapAllocatedSize()}"
  }

  private fun formatWallTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(epochMillis))

  private fun String.takeLastPreservingHeader(maxChars: Int): String {
    if (length <= maxChars) return this
    val headerEnd = indexOf("\n\n").takeIf { it >= 0 } ?: 0
    val header = if (headerEnd > 0) substring(0, headerEnd + 2) else take(3000)
    val tailBudget = (maxChars - header.length - 80).coerceAtLeast(1000)
    return header +
      "\n[diagnostics_note] older lifecycle events were trimmed to keep the report copyable.\n" +
      takeLast(tailBudget)
  }
}

@Composable
fun ModelLifecycleDiagnosticsPanel(
  modelName: String,
  fallbackError: String = "",
  modifier: Modifier = Modifier,
) {
  val reports by ModelLifecycleDiagnostics.reports.collectAsState()
  val liveReport = reports[modelName].orEmpty()
  val reportText =
    if (liveReport.isNotBlank()) {
      liveReport
    } else if (fallbackError.isNotBlank()) {
      buildString {
        appendLine("=== MCP224 模型生命周期诊断 ===")
        appendLine("schema=$MODEL_DIAGNOSTICS_SCHEMA")
        appendLine("app_version=${BuildConfig.VERSION_NAME}")
        appendLine("model_name=$modelName")
        appendLine("stage=ui_fallback_error")
        appendLine(fallbackError)
      }
    } else {
      ""
    }
  if (reportText.isBlank()) return

  val context = androidx.compose.ui.platform.LocalContext.current
  var expanded by remember(reportText) { mutableStateOf(false) }
  var copied by remember(reportText) { mutableStateOf(false) }

  Card(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      val expandLabel = if (expanded) "收起模型生命周期诊断" else "展开模型生命周期诊断"
      FilledTonalButton(
        onClick = { expanded = !expanded },
        modifier =
          Modifier.fillMaxWidth().semantics {
            role = Role.Button
            contentDescription = expandLabel
            stateDescription = if (expanded) "已展开" else "已收起"
          },
      ) {
        Text(if (expanded) "模型诊断：点击收起" else "模型诊断：点击展开")
      }

      val copyLabel = if (copied) "模型诊断已复制" else "复制模型诊断"
      Button(
        onClick = {
          val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          clipboard.setPrimaryClip(
            ClipData.newPlainText("MCP224 Model Lifecycle Diagnostics", reportText)
          )
          copied = true
        },
        modifier =
          Modifier.fillMaxWidth().semantics {
            role = Role.Button
            contentDescription = copyLabel
          },
      ) {
        Text(copyLabel)
      }

      AnimatedVisibility(visible = expanded) {
        SelectionContainer {
          Text(
            text = reportText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    }
  }
}
