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

package com.google.ai.edge.gallery.customtasks.musicgeneration

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.RuntimeType

const val TASK_ID_LOCAL_MUSIC_GENERATION = "llm_local_music_generation"

private const val SOUNDGEN_BASE_URL = "https://huggingface.co/jegly/audio/resolve/main/"
private const val SOUNDGEN_HD_BASE_URL = "https://huggingface.co/jegly/noise/resolve/main/"
private const val SOUNDGEN_VERSION = "v1.0.0"

enum class MusicGenerationKind {
  SOUNDGEN,
  SOUNDGEN_HD,
  SOUNDGEN_HD_LONG,
}

data class MusicGenerationSpec(
  val kind: MusicGenerationKind,
  val maxOutputSamplesPerChannel: Int,
  val taskProgressLabel: String,
)

fun Model.musicGenerationSpec(): MusicGenerationSpec? {
  return when (name) {
    "soundgen" ->
      MusicGenerationSpec(
        kind = MusicGenerationKind.SOUNDGEN,
        maxOutputSamplesPerChannel = 524_288,
        taskProgressLabel = "SoundGen",
      )
    "soundgen_hd" ->
      MusicGenerationSpec(
        kind = MusicGenerationKind.SOUNDGEN_HD,
        maxOutputSamplesPerChannel = 256 * 4096,
        taskProgressLabel = "SoundGen HD",
      )
    "soundgen_hd_long" ->
      MusicGenerationSpec(
        kind = MusicGenerationKind.SOUNDGEN_HD_LONG,
        maxOutputSamplesPerChannel = 2048 * 4096,
        taskProgressLabel = "SoundGen HD Long",
      )
    else -> null
  }
}

fun createMusicGenerationModels(): List<Model> {
  return listOf(
    Model(
      name = "soundgen",
      displayName = "SoundGen",
      info =
        "Describe a sound or piece of music and generate it on-device -- fully offline. Choose a length, then play, export or share the result. Downloads about 1.1GB on first use.",
      url = SOUNDGEN_BASE_URL + "dit_model.tflite",
      sizeInBytes = 344_293_232L,
      downloadFileName = "sg_core.litert",
      version = SOUNDGEN_VERSION,
      extraDataFiles =
        listOf(
          ModelDataFile(
            name = "sg_text",
            url = SOUNDGEN_BASE_URL + "conditioners_float32.tflite",
            downloadFileName = "sg_text.litert",
            sizeInBytes = 440_190_572L,
          ),
          ModelDataFile(
            name = "sg_decode",
            url = SOUNDGEN_BASE_URL + "autoencoder_model.tflite",
            downloadFileName = "sg_decode.litert",
            sizeInBytes = 312_588_244L,
          ),
          ModelDataFile(
            name = "sg_vocab",
            url = SOUNDGEN_BASE_URL + "spiece.model",
            downloadFileName = "sg_vocab.spm",
            sizeInBytes = 791_656L,
          ),
        ),
      bestForTaskIds = listOf(TASK_ID_LOCAL_MUSIC_GENERATION),
      minDeviceMemoryInGb = 6,
      runtimeType = RuntimeType.UNKNOWN,
    ),
    Model(
      name = "soundgen_hd",
      displayName = "SoundGen HD",
      info =
        "Describe a sound or piece of music and generate it on-device in higher quality, fully offline. Choose a length, then play, export or share. Generation takes about a minute. Downloads about 2.1GB on first use.",
      url = SOUNDGEN_HD_BASE_URL + "dit_L256_int8.tflite",
      sizeInBytes = 1_468_553_968L,
      downloadFileName = "sghd_core.litert",
      version = SOUNDGEN_VERSION,
      extraDataFiles =
        listOf(
          ModelDataFile(
            name = "sghd_decode",
            url = SOUNDGEN_HD_BASE_URL + "ae_dec_L256_int8.tflite",
            downloadFileName = "sghd_decode.litert",
            sizeInBytes = 434_121_120L,
          ),
          ModelDataFile(
            name = "sghd_text",
            url = SOUNDGEN_HD_BASE_URL + "t5gemma_enc_int8.tflite",
            downloadFileName = "sghd_text.litert",
            sizeInBytes = 286_972_704L,
          ),
          ModelDataFile(
            name = "sghd_vocab",
            url = SOUNDGEN_HD_BASE_URL + "tokenizer.model",
            downloadFileName = "sghd_vocab.spm",
            sizeInBytes = 4_241_003L,
          ),
        ),
      bestForTaskIds = listOf(TASK_ID_LOCAL_MUSIC_GENERATION),
      minDeviceMemoryInGb = 8,
      runtimeType = RuntimeType.UNKNOWN,
    ),
    Model(
      name = "soundgen_hd_long",
      displayName = "SoundGen HD Long",
      info =
        "Same high quality as SoundGen HD, but for much longer clips -- up to about 3 minutes, fully offline. Generation is slow, around 10 to 15 minutes per clip. Downloads about 2.1GB on first use.",
      url = SOUNDGEN_HD_BASE_URL + "dit_L2048_int8.tflite",
      sizeInBytes = 1_469_012_720L,
      downloadFileName = "sghd_core.litert",
      version = SOUNDGEN_VERSION,
      extraDataFiles =
        listOf(
          ModelDataFile(
            name = "sghd_decode",
            url = SOUNDGEN_HD_BASE_URL + "ae_dec_L2048_int8.tflite",
            downloadFileName = "sghd_decode.litert",
            sizeInBytes = 447_063_056L,
          ),
          ModelDataFile(
            name = "sghd_text",
            url = SOUNDGEN_HD_BASE_URL + "t5gemma_enc_int8.tflite",
            downloadFileName = "sghd_text.litert",
            sizeInBytes = 286_972_704L,
          ),
          ModelDataFile(
            name = "sghd_vocab",
            url = SOUNDGEN_HD_BASE_URL + "tokenizer.model",
            downloadFileName = "sghd_vocab.spm",
            sizeInBytes = 4_241_003L,
          ),
        ),
      bestForTaskIds = listOf(TASK_ID_LOCAL_MUSIC_GENERATION),
      minDeviceMemoryInGb = 12,
      runtimeType = RuntimeType.UNKNOWN,
    ),
  )
}
