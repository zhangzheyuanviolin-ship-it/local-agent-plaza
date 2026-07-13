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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiKeyboardScreen(bottomPadding: Dp) {
  val context = LocalContext.current
  val repository = remember(context) { AiKeyboardModelRepository(context.applicationContext) }
  val scope = rememberCoroutineScope()
  var refreshTick by remember { mutableStateOf(0) }
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

    AiKeyboardModelSection(
      title = stringResource(R.string.ai_keyboard_zh_model_title),
      language = AiKeyboardModelCatalog.LANG_ZH,
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

    AiKeyboardModelSection(
      title = stringResource(R.string.ai_keyboard_en_model_title),
      language = AiKeyboardModelCatalog.LANG_EN,
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
