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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoxSoundGenCoreTest {
  @Test
  fun constants_matchBoxOlI01II0AndOlI0o1() {
    assertEquals(44_100, BoxSoundGenCore.SAMPLE_RATE)
    assertEquals(128, BoxSoundGenCore.BASIC_TEXT_TOKEN_COUNT)
    assertEquals(256, BoxSoundGenCore.HD_TEXT_TOKEN_COUNT)
    assertEquals(16_384, BoxSoundGenCore.BASIC_LATENT_SIZE)
    assertEquals(256, BoxSoundGenCore.HD_LATENT_SCALE)
    assertEquals(257, BoxSoundGenCore.HD_DECODER_SCALE)
    assertEquals(4096, BoxSoundGenCore.HD_AUDIO_SCALE)
    assertEquals(8, BoxSoundGenCore.DIFFUSION_STEPS)
  }

  @Test
  fun basicSigmas_matchBoxOlI01II0Schedule() {
    assertArrayEquals(
      floatArrayOf(
        1f,
        0.9933072f,
        0.98201376f,
        0.95257413f,
        0.8807971f,
        0.7310586f,
        0.5f,
        0.26894143f,
        0f,
      ),
      BoxSoundGenCore.basicSigmas(),
      0.000001f,
    )
  }

  @Test
  fun hdDerivedSizes_matchBoxConstructor() {
    val short = BoxSoundGenCore.hdShape(blockCount = 256)
    assertEquals(65_536, short.latentSize)
    assertEquals(65_792, short.decoderConditionSize)
    assertEquals(1_048_576, short.maxOutputSamplesPerChannel)
    assertEquals(23, short.maxDurationSecondsFloor)

    val long = BoxSoundGenCore.hdShape(blockCount = 2048)
    assertEquals(524_288, long.latentSize)
    assertEquals(526_336, long.decoderConditionSize)
    assertEquals(8_388_608, long.maxOutputSamplesPerChannel)
    assertEquals(190, long.maxDurationSecondsFloor)
  }

  @Test
  fun requestedSamples_matchBoxRoundingAndClamp() {
    assertEquals(1, BoxSoundGenCore.requestedSamples(0.0001f, 524_288))
    assertEquals(44_100, BoxSoundGenCore.requestedSamples(1f, 524_288))
    assertEquals(524_288, BoxSoundGenCore.requestedSamples(60f, 524_288))
  }
}
