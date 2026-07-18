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

const val MINIMAX_OMNI_SKILL_NAME = "minimax-omni"

enum class MiniMaxApiHost(override val value: String, val label: String) : SearchConfigValue {
  TOKEN_PLAN_CN("https://api.minimax.chat", "中国区 Token Plan"),
  OFFICIAL_CN("https://api.minimaxi.com", "中国区官方 Host"),
}

enum class MiniMaxTextModel(override val value: String, val label: String) : SearchConfigValue {
  M27("MiniMax-M2.7", "MiniMax M2.7"),
  M27_HIGHSPEED("MiniMax-M2.7-highspeed", "MiniMax M2.7 高速"),
  M3("MiniMax-M3", "MiniMax M3"),
}

enum class MiniMaxTtsModel(override val value: String, val label: String) : SearchConfigValue {
  SPEECH_28_HD("speech-2.8-hd", "speech-2.8-hd"),
  SPEECH_26_HD("speech-2.6-hd", "speech-2.6-hd"),
  SPEECH_02_HD("speech-02-hd", "speech-02-hd"),
}

enum class MiniMaxVoice(override val value: String, val label: String) : SearchConfigValue {
  MALE_QINGSE("male-qn-qingse", "中文男声 青涩"),
  FEMALE_TIANMEI("female-tianmei", "中文女声 甜美"),
  MALE_QINGSONG("male-qn-qingsong", "中文男声 轻松"),
  FEMALE_SHAONV("female-shaonv", "中文少女"),
  FEMALE_CHENGSHU("female-chengshu", "中文成熟女声"),
  EN_NARRATOR("English_expressive_narrator", "英文旁白"),
  EN_ANNOUNCER("English_magnetic_voiced_man", "英文磁性男声"),
}

enum class MiniMaxImageRatio(override val value: String, val label: String) : SearchConfigValue {
  SQUARE("1:1", "1:1"),
  LANDSCAPE("16:9", "16:9"),
  PORTRAIT("9:16", "9:16"),
  CLASSIC("4:3", "4:3"),
  VERTICAL("3:4", "3:4"),
}

enum class MiniMaxMusicMode(override val value: String, val label: String) : SearchConfigValue {
  INSTRUMENTAL("instrumental", "纯音乐"),
  AUTO_LYRICS("auto_lyrics", "自动生成歌词"),
}

enum class MiniMaxMediaLimit(
  override val value: String,
  val label: String,
  val megabytes: Int,
) : SearchConfigValue {
  MB_20("20", "20 MB", 20),
  MB_50("50", "50 MB", 50),
  MB_100("100", "100 MB", 100),
}

data class MiniMaxOmniConfig(
  val apiHost: MiniMaxApiHost = MiniMaxApiHost.TOKEN_PLAN_CN,
  val textModel: MiniMaxTextModel = MiniMaxTextModel.M27,
  val textMaxTokens: Int = 1024,
  val ttsModel: MiniMaxTtsModel = MiniMaxTtsModel.SPEECH_28_HD,
  val voice: MiniMaxVoice = MiniMaxVoice.MALE_QINGSE,
  val imageRatio: MiniMaxImageRatio = MiniMaxImageRatio.SQUARE,
  val musicMode: MiniMaxMusicMode = MiniMaxMusicMode.INSTRUMENTAL,
  val visionModel: MiniMaxTextModel = MiniMaxTextModel.M3,
  val videoModel: MiniMaxTextModel = MiniMaxTextModel.M3,
  val mediaLimit: MiniMaxMediaLimit = MiniMaxMediaLimit.MB_50,
)

fun isMiniMaxSkill(skillName: String): Boolean = skillName == MINIMAX_OMNI_SKILL_NAME

fun readMiniMaxOmniConfig(dataStoreRepository: DataStoreRepository): MiniMaxOmniConfig {
  val json = dataStoreRepository.readSecret(getSkillConfigKey(MINIMAX_OMNI_SKILL_NAME)) ?: ""
  val obj = runCatching { JSONObject(json) }.getOrNull() ?: return MiniMaxOmniConfig()
  return MiniMaxOmniConfig(
    apiHost = miniMaxEnumByValue(obj.optString("api_host"), MiniMaxApiHost.entries, MiniMaxApiHost.TOKEN_PLAN_CN),
    textModel = miniMaxEnumByValue(obj.optString("text_model"), MiniMaxTextModel.entries, MiniMaxTextModel.M27),
    textMaxTokens = obj.optInt("text_max_tokens", 1024).coerceIn(128, 4096),
    ttsModel = miniMaxEnumByValue(obj.optString("tts_model"), MiniMaxTtsModel.entries, MiniMaxTtsModel.SPEECH_28_HD),
    voice = miniMaxEnumByValue(obj.optString("voice"), MiniMaxVoice.entries, MiniMaxVoice.MALE_QINGSE),
    imageRatio = miniMaxEnumByValue(obj.optString("image_ratio"), MiniMaxImageRatio.entries, MiniMaxImageRatio.SQUARE),
    musicMode = miniMaxEnumByValue(obj.optString("music_mode"), MiniMaxMusicMode.entries, MiniMaxMusicMode.INSTRUMENTAL),
    visionModel = miniMaxEnumByValue(obj.optString("vision_model"), MiniMaxTextModel.entries, MiniMaxTextModel.M3),
    videoModel = miniMaxEnumByValue(obj.optString("video_model"), MiniMaxTextModel.entries, MiniMaxTextModel.M3),
    mediaLimit = miniMaxEnumByValue(obj.optString("media_limit_mb"), MiniMaxMediaLimit.entries, MiniMaxMediaLimit.MB_50),
  )
}

fun saveMiniMaxOmniConfig(dataStoreRepository: DataStoreRepository, config: MiniMaxOmniConfig) {
  dataStoreRepository.saveSecret(
    getSkillConfigKey(MINIMAX_OMNI_SKILL_NAME),
    JSONObject()
      .put("api_host", config.apiHost.value)
      .put("text_model", config.textModel.value)
      .put("text_max_tokens", config.textMaxTokens)
      .put("tts_model", config.ttsModel.value)
      .put("voice", config.voice.value)
      .put("image_ratio", config.imageRatio.value)
      .put("music_mode", config.musicMode.value)
      .put("vision_model", config.visionModel.value)
      .put("video_model", config.videoModel.value)
      .put("media_limit_mb", config.mediaLimit.value)
      .toString(),
  )
}

private fun <T> miniMaxEnumByValue(value: String, entries: Iterable<T>, default: T): T where T : Enum<T>, T : SearchConfigValue {
  return entries.firstOrNull { it.value == value } ?: default
}
