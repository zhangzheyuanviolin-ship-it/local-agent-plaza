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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicGenerationModelsTest {
  @Test
  fun createMusicGenerationModels_registersThreeBoxModels() {
    val models = createMusicGenerationModels()
    assertEquals(listOf("soundgen", "soundgen_hd", "soundgen_hd_long"), models.map { it.name })
    assertEquals("sg_core.litert", models[0].downloadFileName)
    assertEquals("sghd_core.litert", models[1].downloadFileName)
    assertEquals("sghd_core.litert", models[2].downloadFileName)
  }

  @Test
  fun createMusicGenerationModels_keepsRequiredExtraFiles() {
    val models = createMusicGenerationModels()
    assertTrue(models[0].extraDataFiles.map { it.downloadFileName }.containsAll(listOf("sg_text.litert", "sg_decode.litert", "sg_vocab.spm")))
    assertTrue(models[1].extraDataFiles.map { it.downloadFileName }.containsAll(listOf("sghd_text.litert", "sghd_decode.litert", "sghd_vocab.spm")))
    assertTrue(models[2].extraDataFiles.map { it.downloadFileName }.containsAll(listOf("sghd_text.litert", "sghd_decode.litert", "sghd_vocab.spm")))
  }
}
