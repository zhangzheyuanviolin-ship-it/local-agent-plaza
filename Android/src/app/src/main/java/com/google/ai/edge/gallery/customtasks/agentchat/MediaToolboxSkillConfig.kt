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

import com.google.ai.edge.gallery.data.DataStoreRepository
import org.json.JSONObject

const val MEDIA_TOOLBOX_SKILL_NAME = "media-toolbox"

data class MediaToolboxConfig(
  val imageModeEnabled: Boolean = true,
  val audioModeEnabled: Boolean = true,
  val videoModeEnabled: Boolean = true,
  val overwriteOutputs: Boolean = false,
)

fun isMediaToolboxSkill(skillName: String): Boolean = skillName == MEDIA_TOOLBOX_SKILL_NAME

fun readMediaToolboxConfig(dataStoreRepository: DataStoreRepository): MediaToolboxConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(MEDIA_TOOLBOX_SKILL_NAME)) ?: ""
  val obj = runCatching { JSONObject(json) }.getOrNull() ?: return MediaToolboxConfig()
  return MediaToolboxConfig(
    imageModeEnabled = obj.optBoolean("image_mode_enabled", true),
    audioModeEnabled = obj.optBoolean("audio_mode_enabled", true),
    videoModeEnabled = obj.optBoolean("video_mode_enabled", true),
    overwriteOutputs = obj.optBoolean("overwrite_outputs", false),
  )
}

fun saveMediaToolboxConfig(dataStoreRepository: DataStoreRepository, config: MediaToolboxConfig) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(MEDIA_TOOLBOX_SKILL_NAME),
    JSONObject()
      .put("image_mode_enabled", config.imageModeEnabled)
      .put("audio_mode_enabled", config.audioModeEnabled)
      .put("video_mode_enabled", config.videoModeEnabled)
      .put("overwrite_outputs", config.overwriteOutputs)
      .toString(),
  )
}
