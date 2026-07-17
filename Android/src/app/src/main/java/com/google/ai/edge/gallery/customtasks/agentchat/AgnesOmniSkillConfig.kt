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

const val AGNES_OMNI_SKILL_NAME = "agnes-omni"

enum class AgnesImageModel(override val value: String, val label: String) : SearchConfigValue {
  IMAGE_21_FLASH("agnes-image-2.1-flash", "Image 2.1 Flash"),
  IMAGE_20_FLASH("agnes-image-2.0-flash", "Image 2.0 Flash"),
}

enum class AgnesImageSize(override val value: String, val label: String) : SearchConfigValue {
  SIZE_1K("1K", "1K"),
  SIZE_2K("2K", "2K"),
}

enum class AgnesImageRatio(override val value: String, val label: String) : SearchConfigValue {
  SQUARE("1:1", "1:1"),
  LANDSCAPE("16:9", "16:9"),
  PORTRAIT("9:16", "9:16"),
  CLASSIC("4:3", "4:3"),
  VERTICAL("3:4", "3:4"),
}

enum class AgnesVideoDuration(override val value: String, val label: String, val frames: Int) :
  SearchConfigValue {
  FIVE_SECONDS("5s", "5 秒", 121),
  TEN_SECONDS("10s", "10 秒", 241),
}

enum class AgnesVideoResolution(
  override val value: String,
  val label: String,
  val width: Int,
  val height: Int,
) : SearchConfigValue {
  LANDSCAPE("landscape", "横屏 1152x768", 1152, 768),
  PORTRAIT("portrait", "竖屏 768x1152", 768, 1152),
  SQUARE("square", "方形 1024x1024", 1024, 1024),
}

data class AgnesOmniConfig(
  val imageModel: AgnesImageModel = AgnesImageModel.IMAGE_21_FLASH,
  val imageSize: AgnesImageSize = AgnesImageSize.SIZE_1K,
  val imageRatio: AgnesImageRatio = AgnesImageRatio.SQUARE,
  val videoDuration: AgnesVideoDuration = AgnesVideoDuration.FIVE_SECONDS,
  val videoResolution: AgnesVideoResolution = AgnesVideoResolution.LANDSCAPE,
)

fun isAgnesSkill(skillName: String): Boolean = skillName == AGNES_OMNI_SKILL_NAME

fun readAgnesOmniConfig(dataStoreRepository: DataStoreRepository): AgnesOmniConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(AGNES_OMNI_SKILL_NAME)) ?: ""
  val obj = runCatching { JSONObject(json) }.getOrNull() ?: return AgnesOmniConfig()
  return AgnesOmniConfig(
    imageModel = agnesEnumByValue(obj.optString("image_model"), AgnesImageModel.entries, AgnesImageModel.IMAGE_21_FLASH),
    imageSize = agnesEnumByValue(obj.optString("image_size"), AgnesImageSize.entries, AgnesImageSize.SIZE_1K),
    imageRatio = agnesEnumByValue(obj.optString("image_ratio"), AgnesImageRatio.entries, AgnesImageRatio.SQUARE),
    videoDuration = agnesEnumByValue(obj.optString("video_duration"), AgnesVideoDuration.entries, AgnesVideoDuration.FIVE_SECONDS),
    videoResolution = agnesEnumByValue(obj.optString("video_resolution"), AgnesVideoResolution.entries, AgnesVideoResolution.LANDSCAPE),
  )
}

fun saveAgnesOmniConfig(dataStoreRepository: DataStoreRepository, config: AgnesOmniConfig) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(AGNES_OMNI_SKILL_NAME),
    JSONObject()
      .put("image_model", config.imageModel.value)
      .put("image_size", config.imageSize.value)
      .put("image_ratio", config.imageRatio.value)
      .put("video_duration", config.videoDuration.value)
      .put("video_resolution", config.videoResolution.value)
      .toString(),
  )
}

private fun <T> agnesEnumByValue(value: String, entries: Iterable<T>, default: T): T where T : Enum<T>, T : SearchConfigValue {
  return entries.firstOrNull { it.value == value } ?: default
}
