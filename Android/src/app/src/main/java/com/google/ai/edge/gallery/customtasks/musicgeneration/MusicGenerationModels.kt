/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.customtasks.musicgeneration

import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.RuntimeType

const val TASK_ID_LOCAL_MUSIC_GENERATION = "llm_local_music_generation"

private const val SOUNDGEN_BASE_URL = "https://huggingface.co/jegly/audio/resolve/main/"
private const val SOUNDGEN_HD_BASE_URL = "https://huggingface.co/jegly/noise/resolve/main/"
// Deliberately invalidate the MCP189/191 music cache. The new runtime verifies every file against
// the golden Box 0.4.9 ModelSpec before model initialization and before every generation.
private const val SOUNDGEN_VERSION = "box-0.4.9-golden-runtime-r1"

enum class MusicGenerationKind {
  SOUNDGEN,
  SOUNDGEN_HD,
  SOUNDGEN_HD_LONG,
}

data class MusicGenerationSpec(
  val kind: MusicGenerationKind,
  val maxOutputSamplesPerChannel: Int,
  val taskProgressLabel: String,
  val minDurationSeconds: Float,
  val maxDurationSeconds: Float,
  val defaultDurationSeconds: Float,
)

fun Model.musicGenerationSpec(): MusicGenerationSpec? {
  return when (name) {
    "soundgen" ->
      MusicGenerationSpec(
        kind = MusicGenerationKind.SOUNDGEN,
        maxOutputSamplesPerChannel = 524_288,
        taskProgressLabel = "SoundGen",
        minDurationSeconds = 1f,
        maxDurationSeconds = 12f,
        defaultDurationSeconds = 8f,
      )
    "soundgen_hd" ->
      MusicGenerationSpec(
        kind = MusicGenerationKind.SOUNDGEN_HD,
        maxOutputSamplesPerChannel = 256 * 4096,
        taskProgressLabel = "SoundGen HD",
        minDurationSeconds = 1f,
        maxDurationSeconds = 24f,
        defaultDurationSeconds = 12f,
      )
    "soundgen_hd_long" ->
      MusicGenerationSpec(
        kind = MusicGenerationKind.SOUNDGEN_HD_LONG,
        maxOutputSamplesPerChannel = 2048 * 4096,
        taskProgressLabel = "SoundGen HD Long",
        minDurationSeconds = 1f,
        maxDurationSeconds = 180f,
        defaultDurationSeconds = 60f,
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
        "Box 官方 SoundGen 本地音乐生成模型。模型下载完成后可完全离线使用，首次下载约 1.1GB。",
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
        "Box 官方 SoundGen HD 高质量本地音乐生成模型，最长约 24 秒，首次下载约 2.1GB。",
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
        "Box 官方 SoundGen HD Long 长音频高质量音乐生成模型，最长约 3 分钟。",
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
