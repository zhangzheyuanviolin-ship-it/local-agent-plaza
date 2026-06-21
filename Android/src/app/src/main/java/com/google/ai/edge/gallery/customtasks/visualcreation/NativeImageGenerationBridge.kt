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

package com.google.ai.edge.gallery.customtasks.visualcreation

data class NativeImageGenerationResult(
  val width: Int,
  val height: Int,
  val channels: Int,
  val bytes: ByteArray,
)

object NativeImageGenerationBridge {
  init {
    System.loadLibrary("visual_creation_jni")
  }

  fun interface ProgressListener {
    fun onProgress(step: Int, steps: Int, secondsPerStep: Float)
  }

  external fun generateImage(
    modelPath: String,
    diffusionModelPath: String,
    vaePath: String,
    llmPath: String,
    prompt: String,
    negativePrompt: String,
    width: Int,
    height: Int,
    steps: Int,
    cfgScale: Float,
    seed: Long,
    threadCount: Int,
    progressListener: ProgressListener?,
  ): NativeImageGenerationResult
}
