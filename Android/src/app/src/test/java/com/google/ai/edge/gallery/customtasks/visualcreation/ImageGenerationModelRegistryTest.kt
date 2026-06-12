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
  fun registryIncludesDownloadableZImageTurboModel() {
    val modelIds = ImageGenerationModelRegistry.recommendedModels.map { it.modelId }

    assertEquals(listOf("z-image-turbo-q2-gguf"), modelIds)
  }

  @Test
  fun zImageTurboModelDeclaresVerifiedPackageMetadata() {
    val model = ImageGenerationModelRegistry.requireModel("z-image-turbo-q2-gguf")

    assertEquals("Z-Image Turbo Q2_K GGUF", model.displayName)
    assertEquals(ImageGenerationBackend.STABLE_DIFFUSION_CPP, model.backend)
    assertEquals("GGUF", model.format)
    assertTrue(model.supportsTextToImage)
    assertTrue(model.supportsChineseText)
    assertTrue(model.lowMemoryRecommended)
    assertTrue(model.requiredFiles.any { it.role == ImageGenerationModelFileRole.DIFFUSION_MODEL })
    assertTrue(model.requiredFiles.any { it.role == ImageGenerationModelFileRole.VAE })
    assertTrue(model.requiredFiles.any { it.role == ImageGenerationModelFileRole.TEXT_ENCODER })
    assertEquals(6_407_162_885L, model.totalSizeInBytes)
    model.requiredFiles.forEach { file ->
      assertTrue(file.downloadUrl.startsWith("https://huggingface.co/"))
      assertTrue(file.downloadUrl.contains("/resolve/main/"))
      assertTrue(file.sizeInBytes > 0L)
    }
  }

  @Test
  fun visualCreationTaskUsesRealDownloadModelInsteadOfPlaceholder() {
    val models = createVisualCreationImageModels()

    assertEquals(listOf("z-image-turbo-q2-gguf"), models.map { it.name })
    val model = models.single()
    assertEquals("z_image_turbo-Q2_K.gguf", model.downloadFileName)
    assertTrue(model.url.endsWith("/z_image_turbo-Q2_K.gguf"))
    assertEquals(2, model.extraDataFiles.size)
    assertTrue(model.extraDataFiles.all { it.url.startsWith("https://huggingface.co/") })
    assertTrue(model.extraDataFiles.none { it.downloadFileName == "workbench.marker" })
    assertTrue(model.totalBytes > 0L)
    assertEquals(false, model.isLlm)
    assertEquals(false, model.showBenchmarkButton)
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
