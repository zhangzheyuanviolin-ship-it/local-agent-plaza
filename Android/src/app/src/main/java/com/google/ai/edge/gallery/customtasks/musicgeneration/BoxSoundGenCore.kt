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

import kotlin.math.exp
import kotlin.math.roundToInt

internal object BoxSoundGenCore {
  const val SAMPLE_RATE = 44_100
  const val BASIC_TEXT_TOKEN_COUNT = 128
  const val HD_TEXT_TOKEN_COUNT = 256
  const val BASIC_LATENT_SIZE = 16_384
  const val HD_LATENT_SCALE = 256
  const val HD_DECODER_SCALE = 257
  const val HD_AUDIO_SCALE = 4096
  const val DIFFUSION_STEPS = 8

  data class HdShape(
    val latentSize: Int,
    val decoderConditionSize: Int,
    val maxOutputSamplesPerChannel: Int,
    val maxDurationSecondsFloor: Int,
  )

  fun hdShape(blockCount: Int): HdShape {
    val maxOutputSamplesPerChannel = blockCount * HD_AUDIO_SCALE
    return HdShape(
      latentSize = blockCount * HD_LATENT_SCALE,
      decoderConditionSize = blockCount * HD_DECODER_SCALE,
      maxOutputSamplesPerChannel = maxOutputSamplesPerChannel,
      maxDurationSecondsFloor = maxOutputSamplesPerChannel / SAMPLE_RATE,
    )
  }

  fun basicSigmas(): FloatArray {
    val values = FloatArray(DIFFUSION_STEPS + 1)
    for (index in values.indices) {
      values[index] = if (index == 0) -6f else values[index - 1] + 1f
    }
    values[DIFFUSION_STEPS] = 2f
    for (index in values.indices) {
      values[index] = 1f / (exp(values[index].toDouble()).toFloat() + 1f)
    }
    values[0] = 1f
    values[DIFFUSION_STEPS] = 0f
    return values
  }

  fun hdSigmas(blockCount: Int): FloatArray {
    val expFactor =
      exp(
          -((((blockCount.coerceIn(HD_TEXT_TOKEN_COUNT, 4096) - HD_TEXT_TOKEN_COUNT) * 0.65f) /
              3840f) + 0.5f)
            .toDouble()
        )
        .toFloat()
    return FloatArray(DIFFUSION_STEPS + 1) { index ->
        val t = 1f - (index / DIFFUSION_STEPS.toFloat())
        if (t >= 1f) {
          1f
        } else if (t <= 0f) {
          0f
        } else {
          1f - (expFactor / (((1f / (1f - t)) - 1f) + expFactor))
        }
      }
      .also { it[0] = 1f }
  }

  fun requestedSamples(durationSeconds: Float, maxSamples: Int): Int {
    return (durationSeconds * SAMPLE_RATE).roundToInt().coerceAtLeast(1).coerceAtMost(maxSamples)
  }
}
