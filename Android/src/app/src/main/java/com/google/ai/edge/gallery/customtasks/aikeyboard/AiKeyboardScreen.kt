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

package com.google.ai.edge.gallery.customtasks.aikeyboard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelCatalog
import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelDescriptor
import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelDownloadProgress
import com.google.ai.edge.gallery.customtasks.aikeyboard.model.AiKeyboardModelRepository
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardPipelineLogEntry
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardPipelinePreset
import com.google.ai.edge.gallery.customtasks.aikeyboard.pipeline.AiKeyboardTextModelRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiKeyboardScreen(bottomPadding: Dp) {
  val context = LocalContext.current
  val repository = remember(context) { AiKeyboardModelRepository(context.applicationContext) }
  val pipelineRepository = remember(context) { AiKeyboardTextModelRepository(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var refreshTick by remember { mutableStateOf(0) }
  var pipelineRefreshTick by remember { mutableStateOf(0) }
  var showPipelineSettings by remember { mutableStateOf(false) }
  val progressByModel = remember { mutableStateMapOf<String, AiKeyboardModelDownloadProgress>() }
  val downloadingIds = remember { mutableStateMapOf<String, Boolean>() }
  val hasMicPermission =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  var micPermissionGranted by remember { mutableStateOf(hasMicPermission) }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      micPermissionGranted = granted
      if (granted) {
        Toast.makeText(context, R.string.ai_keyboard_permission_granted, Toast.LENGTH_SHORT).show()
      }
    }

  LaunchedEffect(repository) {
    withContext(Dispatchers.IO) { repository.ensureBundledModelsInstalled() }
    refreshTick++
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .padding(bottom = bottomPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = stringResource(R.string.ai_keyboard_task_title),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      if (!micPermissionGranted) {
        Button(
          modifier = Modifier.weight(1f),
          onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        ) {
          Icon(Icons.Outlined.Mic, contentDescription = null)
          Text(stringResource(R.string.ai_keyboard_grant_mic), modifier = Modifier.padding(start = 6.dp))
        }
      }
      OutlinedButton(
        modifier = Modifier.weight(1f),
        onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
      ) {
        Icon(Icons.Outlined.Settings, contentDescription = null)
        Text(stringResource(R.string.ai_keyboard_open_ime_settings), modifier = Modifier.padding(start = 6.dp))
      }
    }

    Button(
      modifier = Modifier.fillMaxWidth(),
      onClick = { showInputMethodPicker(context) },
    ) {
      Icon(Icons.Outlined.Keyboard, contentDescription = null)
      Text(stringResource(R.string.ai_keyboard_show_ime_picker), modifier = Modifier.padding(start = 6.dp))
    }

    OutlinedButton(
      modifier = Modifier.fillMaxWidth(),
      onClick = { showPipelineSettings = !showPipelineSettings },
    ) {
      Icon(Icons.Outlined.Settings, contentDescription = null)
      Text("流水线设置", modifier = Modifier.padding(start = 6.dp))
    }

    if (showPipelineSettings) {
      AiKeyboardPipelineSettingsSection(
        repository = pipelineRepository,
        refreshTick = pipelineRefreshTick,
        onRefresh = { pipelineRefreshTick++ },
      )
    }

    AiKeyboardModelCatalog.supportedLanguages().forEach { language ->
      AiKeyboardModelSection(
        title = AiKeyboardModelCatalog.languageDisplayName(language) + "语音模型",
        language = language,
        repository = repository,
        progressByModel = progressByModel,
        downloadingIds = downloadingIds,
        refreshTick = refreshTick,
        onRefresh = { refreshTick++ },
        onDownload = { model ->
          scope.launch {
            downloadingIds[model.id] = true
            try {
              withContext(Dispatchers.IO) {
                repository.downloadAndInstall(model.id) { progressByModel[model.id] = it }
                repository.setSelectedModelId(model.language, model.id)
              }
              Toast.makeText(context, R.string.ai_keyboard_download_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
              Toast.makeText(
                  context,
                  context.getString(R.string.ai_keyboard_download_failed) + ": " + e.message,
                  Toast.LENGTH_LONG,
                )
                .show()
            } finally {
              downloadingIds.remove(model.id)
              progressByModel.remove(model.id)
              refreshTick++
            }
          }
        },
        onSelect = { model ->
          repository.setSelectedModelId(model.language, model.id)
          Toast.makeText(context, R.string.ai_keyboard_model_switched, Toast.LENGTH_SHORT).show()
          refreshTick++
        },
        onDelete = { model ->
          scope.launch {
            withContext(Dispatchers.IO) { repository.deleteModel(model.id) }
            Toast.makeText(context, R.string.ai_keyboard_delete_done, Toast.LENGTH_SHORT).show()
            refreshTick++
          }
        },
      )
    }
  }
}

@Composable
private fun AiKeyboardPipelineSettingsSection(
  repository: AiKeyboardTextModelRepository,
  refreshTick: Int,
  onRefresh: () -> Unit,
) {
  var translationTarget by remember(refreshTick) { mutableStateOf(repository.getTranslationTargetLanguage()) }
  var newName by remember { mutableStateOf("") }
  var newLabel by remember { mutableStateOf("") }
  var newInstruction by remember { mutableStateOf("") }
  var showLogs by remember { mutableStateOf(false) }
  var showClearLogsDialog by remember { mutableStateOf(false) }
  val selectedModel = remember(refreshTick) { repository.getSelectedModel() }
  val availableModels = remember(refreshTick) { repository.listAvailableModels() }
  val selectedPipelineId = remember(refreshTick) { repository.getSelectedPipelineId() }
  val presets = remember(refreshTick) { repository.listPipelinePresets() }
  val logs = remember(refreshTick) { repository.listPipelineLogs() }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(
      onClick = { showLogs = !showLogs },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(if (showLogs) "收起流水线日志" else "查看流水线日志")
    }

    if (showLogs) {
      AiKeyboardPipelineLogSection(
        logs = logs,
        onDelete = { id ->
          repository.deletePipelineLog(id)
          onRefresh()
        },
        onClear = { showClearLogsDialog = true },
      )
    }

    Text("文本模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (availableModels.isEmpty()) {
      Text("未找到已下载或已导入的 LiteRT 文本模型。", style = MaterialTheme.typography.bodyMedium)
    } else {
      availableModels.forEach { candidate ->
        val selected = candidate.path == selectedModel?.path
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(candidate.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
              if (selected) "当前文本模型" else "可用文本模型",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
              onClick = {
                repository.setSelectedModelPath(candidate.path)
                onRefresh()
              },
              enabled = !selected,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(if (selected) "已选择" else "设为当前文本模型")
            }
          }
        }
      }
    }

    Text("翻译目标语言", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
      value = translationTarget,
      onValueChange = { translationTarget = it },
      label = { Text("目标语言") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    Button(
      onClick = {
        repository.setTranslationTargetLanguage(translationTarget)
        onRefresh()
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text("保存翻译目标语言")
    }

    Text("流水线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    presets.forEach { preset ->
      AiKeyboardPipelinePresetCard(
        preset = preset,
        selected = preset.id == selectedPipelineId,
        onSelect = {
          repository.setSelectedPipelineId(preset.id)
          onRefresh()
        },
        onSaveInstruction = { instruction ->
          repository.savePipelineInstruction(preset.id, instruction)
          onRefresh()
        },
        onResetInstruction = {
          repository.resetPipelineInstruction(preset.id)
          onRefresh()
        },
        onDelete = {
          repository.deleteCustomPipeline(preset.id)
          onRefresh()
        },
      )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("新增自定义流水线", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          label = { Text("名称") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        OutlinedTextField(
          value = newLabel,
          onValueChange = { newLabel = it.take(4) },
          label = { Text("键盘按钮标签") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true,
        )
        OutlinedTextField(
          value = newInstruction,
          onValueChange = { newInstruction = it },
          label = { Text("任务说明提示词") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 3,
        )
        Button(
          onClick = {
            val preset = repository.addCustomPipeline(newName, newLabel, newInstruction)
            repository.setSelectedPipelineId(preset.id)
            newName = ""
            newLabel = ""
            newInstruction = ""
            onRefresh()
          },
          modifier = Modifier.fillMaxWidth(),
          enabled = newInstruction.isNotBlank(),
        ) {
          Text("新增并选中")
        }
      }
    }
  }

  if (showClearLogsDialog) {
    AlertDialog(
      onDismissRequest = { showClearLogsDialog = false },
      title = { Text("清空流水线日志") },
      text = { Text("清空后，所有流水线处理记录都会从本机删除。") },
      confirmButton = {
        TextButton(
          onClick = {
            showClearLogsDialog = false
            repository.clearPipelineLogs()
            onRefresh()
          }
        ) {
          Text("清空")
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearLogsDialog = false }) {
          Text("取消")
        }
      },
    )
  }
}

@Composable
private fun AiKeyboardPipelineLogSection(
  logs: List<AiKeyboardPipelineLogEntry>,
  onDelete: (String) -> Unit,
  onClear: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
      Text(
        "流水线日志 ${logs.size} 条",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      OutlinedButton(onClick = onClear, enabled = logs.isNotEmpty()) {
        Text("清空")
      }
    }
    if (logs.isEmpty()) {
      Text("暂无流水线处理记录。", style = MaterialTheme.typography.bodyMedium)
    } else {
      logs.forEach { entry ->
        AiKeyboardPipelineLogCard(entry = entry, onDelete = { onDelete(entry.id) })
      }
    }
  }
}

@Composable
private fun AiKeyboardPipelineLogCard(
  entry: AiKeyboardPipelineLogEntry,
  onDelete: () -> Unit,
) {
  var expanded by remember(entry.id) { mutableStateOf(false) }
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        formatPipelineLogTime(entry.createdAtMillis),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "${entry.presetName}，${entry.modelName.ifBlank { "未知模型" }}，状态 ${entry.status}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        "输入 ${entry.inputLength}，原始输出 ${entry.rawOutputLength}，清洗输出 ${entry.outputLength}，提交后 ${entry.committedLength}，maxTokens ${entry.maxTokens}，上下文 ${entry.contextWindow}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text("原文：" + entry.inputText.previewForLog(expanded), style = MaterialTheme.typography.bodyMedium)
      Text("输出：" + entry.outputText.previewForLog(expanded), style = MaterialTheme.typography.bodyMedium)
      if (entry.committedText.isNotBlank() && entry.committedText != entry.outputText) {
        Text("提交后：" + entry.committedText.previewForLog(expanded), style = MaterialTheme.typography.bodyMedium)
      }
      if (entry.errorText.isNotBlank()) {
        Text("错误：" + entry.errorText.previewForLog(expanded), style = MaterialTheme.typography.bodyMedium)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.weight(1f)) {
          Text(if (expanded) "收起" else "展开")
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
          Icon(Icons.Outlined.Delete, contentDescription = null)
          Text("删除", modifier = Modifier.padding(start = 6.dp))
        }
      }
    }
  }
}

private fun String.previewForLog(expanded: Boolean): String {
  val text = trim()
  if (expanded || text.length <= 260) return text
  return text.take(260) + "..."
}

private fun formatPipelineLogTime(timeMillis: Long): String {
  return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timeMillis))
}

@Composable
private fun AiKeyboardPipelinePresetCard(
  preset: AiKeyboardPipelinePreset,
  selected: Boolean,
  onSelect: () -> Unit,
  onSaveInstruction: (String) -> Unit,
  onResetInstruction: () -> Unit,
  onDelete: () -> Unit,
) {
  var instruction by remember(preset.id, preset.instruction) { mutableStateOf(preset.instruction) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(preset.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        (if (preset.builtIn) "内置预设" else "自定义流水线") + if (selected) "，当前使用中" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = instruction,
        onValueChange = { instruction = it },
        label = { Text("任务说明提示词") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onSelect, enabled = !selected, modifier = Modifier.weight(1f)) {
          Text(if (selected) "已选中" else "选中")
        }
        Button(
          onClick = { onSaveInstruction(instruction) },
          enabled = instruction.isNotBlank(),
          modifier = Modifier.weight(1f),
        ) {
          Text("保存")
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (preset.builtIn) {
          OutlinedButton(onClick = onResetInstruction, modifier = Modifier.weight(1f)) {
            Text("重置提示词")
          }
        } else {
          OutlinedButton(onClick = { showDeleteDialog = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Text("删除", modifier = Modifier.padding(start = 6.dp))
          }
        }
      }
    }
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text("删除自定义流水线") },
      text = { Text("删除后不能在键盘按钮中继续切换到这个流水线。") },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteDialog = false
            onDelete()
          }
        ) {
          Text("删除")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = false }) {
          Text("取消")
        }
      },
    )
  }
}

@Composable
private fun AiKeyboardModelSection(
  title: String,
  language: String,
  repository: AiKeyboardModelRepository,
  progressByModel: Map<String, AiKeyboardModelDownloadProgress>,
  downloadingIds: Map<String, Boolean>,
  refreshTick: Int,
  onRefresh: () -> Unit,
  onDownload: (AiKeyboardModelDescriptor) -> Unit,
  onSelect: (AiKeyboardModelDescriptor) -> Unit,
  onDelete: (AiKeyboardModelDescriptor) -> Unit,
) {
  val selectedId = remember(refreshTick, language) { repository.getSelectedModelId(language) }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    AiKeyboardModelCatalog.modelsForLanguage(language).forEach { model ->
      val installed = remember(refreshTick, model.id) { repository.hasModel(model.id) }
      AiKeyboardModelCard(
        model = model,
        installed = installed,
        selected = selectedId == model.id,
        progress = progressByModel[model.id],
        downloading = downloadingIds[model.id] == true,
        onDownload = { onDownload(model) },
        onSelect = { onSelect(model) },
        onDelete = {
          onDelete(model)
          onRefresh()
        },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiKeyboardModelCard(
  model: AiKeyboardModelDescriptor,
  installed: Boolean,
  selected: Boolean,
  progress: AiKeyboardModelDownloadProgress?,
  downloading: Boolean,
  onDownload: () -> Unit,
  onSelect: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth(), onClick = { if (installed) onSelect() }) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(model.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        text =
          (if (installed) {
              stringResource(R.string.ai_keyboard_ready)
            } else {
              stringResource(R.string.ai_keyboard_missing)
            }) + "，" + AiKeyboardModelCatalog.formatSizeLabel(model.fileSizeBytes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (selected && installed) {
        Text(stringResource(R.string.ai_keyboard_current_model), style = MaterialTheme.typography.labelLarge)
      }
      progress?.let {
        Text(
          text =
            stringResource(R.string.ai_keyboard_downloading) +
              " ${it.percent}% " +
              AiKeyboardModelCatalog.formatSpeedLabel(it.speedBytesPerSec) +
              " ${it.sourceDisplayName}",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (downloading) {
          CircularProgressIndicator()
        } else if (!installed) {
          Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Text(stringResource(R.string.ai_keyboard_download_and_use), modifier = Modifier.padding(start = 6.dp))
          }
        } else {
          OutlinedButton(onClick = onSelect, enabled = !selected, modifier = Modifier.weight(1f)) {
            Text(
              if (selected) {
                stringResource(R.string.ai_keyboard_current_model)
              } else {
                stringResource(R.string.ai_keyboard_use_model)
              }
            )
          }
          OutlinedButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Text(stringResource(R.string.ai_keyboard_delete_model), modifier = Modifier.padding(start = 6.dp))
          }
        }
      }
      Spacer(Modifier.height(2.dp))
    }
  }
}

private fun showInputMethodPicker(context: Context) {
  val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
  if (imm == null) {
    Toast.makeText(context, R.string.ai_keyboard_no_ime_picker, Toast.LENGTH_LONG).show()
    return
  }
  if (context !is Activity) {
    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    return
  }
  imm.showInputMethodPicker()
}
