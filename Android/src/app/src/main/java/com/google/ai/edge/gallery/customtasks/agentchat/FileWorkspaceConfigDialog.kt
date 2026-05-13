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

import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepository

private const val TAG = "FileWorkspaceConfig"

@Composable
fun FileWorkspaceConfigDialog(
  dataStoreRepository: DataStoreRepository,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val initialConfig = remember { readFileWorkspaceConfig(dataStoreRepository) }
  var treeUri by remember { mutableStateOf(initialConfig.treeUri) }
  var displayName by remember { mutableStateOf(initialConfig.displayName) }

  val directoryPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri == null) {
        return@rememberLauncherForActivityResult
      }
      runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        .onFailure { Log.w(TAG, "Failed to persist uri permission for $uri", it) }
      treeUri = uri.toString()
      displayName = getDisplayName(context, uri)
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.file_workspace_config_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
          text = stringResource(R.string.file_workspace_config_subtitle),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = stringResource(R.string.file_workspace_selected_folder_label),
            style = MaterialTheme.typography.labelLarge,
          )
          Text(
            text =
              if (displayName.isNotBlank()) displayName
              else stringResource(R.string.file_workspace_no_folder_selected),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilledTonalButton(
            onClick = { directoryPickerLauncher.launch(null) },
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.file_workspace_choose_folder))
          }
          OutlinedButton(
            onClick = {
              treeUri = ""
              displayName = ""
            },
            modifier = Modifier.weight(1f),
          ) {
            Text(stringResource(R.string.file_workspace_clear_folder))
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          saveFileWorkspaceConfig(
            dataStoreRepository,
            FileWorkspaceConfig(treeUri = treeUri, displayName = displayName),
          )
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.save))
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
  )
}
