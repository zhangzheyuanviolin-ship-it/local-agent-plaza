/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.google.ai.edge.gallery.customtasks.musicgeneration

import android.content.Context
import com.google.ai.edge.gallery.customtasks.musicgeneration.box049.Box049Bridge
import com.google.ai.edge.gallery.data.Model
import java.io.File

private val BOX049_BAD_FILE_PATTERN = Regex("integrity failure: ([^ ]+) actual=")

/**
 * Thin adapter around the exact effective Box Local Music 0.4.9 Java runtime.
 *
 * The Java runtime is generated at Gradle configuration time from the pinned golden Box commit
 * after replaying patch_041 through patch_049 in their original order.
 */
class GoldenBox049RuntimeEngine(
  private val context: Context,
  private val model: Model,
) : MusicGenerationEngine {
  @Volatile private var lastAccelerationReport: String = ""

  private val modelDir: File =
    File(model.getPath(context, model.downloadFileName)).parentFile
      ?: error("Cannot resolve Box 0.4.9 model directory for ${model.name}")

  init {
    require(model.musicGenerationSpec() != null) { "Unsupported Box music model: ${model.name}" }
    verifyModelFilesOrInvalidateBrokenFile()
  }

  override suspend fun generate(
    context: Context,
    prompt: String,
    durationSeconds: Float,
    accelerationMode: MusicAccelerationMode,
    onProgress: (Float) -> Unit,
  ): GeneratedMusicFile {
    verifyModelFilesOrInvalidateBrokenFile()
    val outputPath =
      Box049Bridge.generate(
        context,
        model.name,
        modelDir,
        prompt,
        durationSeconds,
        accelerationMode.name,
      ) { progress -> onProgress(progress) }

    lastAccelerationReport = Box049Bridge.accelerationReport()
    val output = File(outputPath)
    verifyGeneratedWav(output)
    return GeneratedMusicFile(file = output, durationSeconds = durationSeconds)
  }

  override fun accelerationReport(): String = lastAccelerationReport

  override fun close() {
    // The golden runtime opens/closes LiteRT models per generation exactly like standalone Box 0.4.9.
  }

  /**
   * The generic Plaza downloader historically considered a completed HTTP stream successful without
   * validating the final byte count. The golden Box runtime has the authoritative file sizes, so
   * reject any mismatch before LiteRT sees it and remove the bad final file immediately. On the next
   * model-manager pass the missing file is treated as not downloaded and can be fetched cleanly.
   */
  private fun verifyModelFilesOrInvalidateBrokenFile() {
    try {
      Box049Bridge.verifyFiles(model.name, modelDir)
    } catch (error: IllegalStateException) {
      val badFileName =
        BOX049_BAD_FILE_PATTERN.find(error.message.orEmpty())?.groupValues?.getOrNull(1)
      if (!badFileName.isNullOrBlank()) {
        File(modelDir, badFileName).delete()
        File(modelDir, "$badFileName.gallerytmp").delete()
      }
      throw error
    }
  }

  private fun verifyGeneratedWav(file: File) {
    require(file.isFile && file.length() > 44L) {
      "Box 0.4.9 returned an invalid WAV file: ${file.absolutePath}"
    }
    val header = ByteArray(12)
    file.inputStream().use { input ->
      require(input.read(header) == header.size) { "Generated WAV header is truncated" }
    }
    require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") {
      "Generated audio is missing RIFF header"
    }
    require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
      "Generated audio is missing WAVE header"
    }
  }
}
