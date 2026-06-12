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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationModelRegistryTest {
  @Test
  fun registryIncludesEngineeringAndChineseCandidateModels() {
    val modelIds = ImageGenerationModelRegistry.recommendedModels.map { it.modelId }

    assertTrue(modelIds.contains("sd15-q4-engineering"))
    assertTrue(modelIds.contains("z-image-turbo-q3-gguf"))
  }

  @Test
  fun zImageTurboCandidateDeclaresPackageMetadata() {
    val model = ImageGenerationModelRegistry.requireModel("z-image-turbo-q3-gguf")

    assertEquals("Z-Image Turbo Q3 GGUF", model.displayName)
    assertEquals(ImageGenerationBackend.STABLE_DIFFUSION_CPP, model.backend)
    assertEquals("GGUF", model.format)
    assertTrue(model.supportsTextToImage)
    assertTrue(model.supportsChineseText)
    assertTrue(model.lowMemoryRecommended)
    assertTrue(model.requiredFiles.any { it.role == ImageGenerationModelFileRole.DIFFUSION_MODEL })
    assertTrue(model.requiredFiles.any { it.role == ImageGenerationModelFileRole.VAE })
    assertTrue(model.requiredFiles.any { it.role == ImageGenerationModelFileRole.TEXT_ENCODER })
    assertTrue(model.totalSizeInBytes > 2_000_000_000L)
  }

  @Test
  fun missingModelReturnsNullAndRequireModelFailsClearly() {
    assertEquals(null, ImageGenerationModelRegistry.findModel("missing"))

    val error =
      runCatching { ImageGenerationModelRegistry.requireModel("missing") }.exceptionOrNull()

    assertNotNull(error)
    assertTrue(error!!.message!!.contains("missing"))
  }
}
