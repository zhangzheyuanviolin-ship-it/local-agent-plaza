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
  fun registryIncludesDownloadableLocalDreamZImageAndStableDiffusionModels() {
    val modelIds = ImageGenerationModelRegistry.recommendedModels.map { it.modelId }

    assertEquals(
      listOf(
        "absolute-reality-qnn-8gen2",
        "absolute-reality-mnn-cpu",
        "z-image-turbo-q2-gguf",
        "z-image-turbo-q3-gguf",
        "z-image-turbo-q4-0-gguf",
        "z-image-turbo-q4-k-gguf",
        "z-image-turbo-q5-0-gguf",
        "z-image-turbo-q6-k-gguf",
        "z-image-turbo-q8-0-gguf",
        "sd15-q4-0-gguf",
        "sd15-q5-0-gguf",
        "sd15-q8-0-gguf",
      ),
      modelIds,
    )
  }

  @Test
  fun localDreamQnnModelDeclaresArchiveAndBackendMetadata() {
    val model = ImageGenerationModelRegistry.requireModel("absolute-reality-qnn-8gen2")

    assertEquals("Absolute Reality QNN 8gen2", model.displayName)
    assertEquals(ImageGenerationBackend.LOCAL_DREAM_QNN_MNN, model.backend)
    assertEquals("QNN/MNN ZIP", model.format)
    assertEquals(false, model.supportsChineseText)
    assertTrue(model.supportsTextToImage)
    assertTrue(model.lowMemoryRecommended)
    assertEquals(1, model.requiredFiles.size)
    assertEquals(ImageGenerationModelFileRole.MODEL_ARCHIVE, model.requiredFiles.single().role)
    assertEquals("AbsoluteReality_qnn2.28_8gen2.zip", model.requiredFiles.single().fileName)
    assertEquals(1_128_267_776L, model.totalSizeInBytes)
    assertEquals("absolute-reality-qnn-8gen2-2026-06-14", model.localVersion)
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
    assertEquals("z-image-turbo-q2-2025-12-02", model.localVersion)
    model.requiredFiles.forEach { file ->
      assertTrue(file.downloadUrl.startsWith("https://huggingface.co/"))
      assertTrue(file.downloadUrl.contains("/resolve/main/"))
      assertTrue(file.sizeInBytes > 0L)
    }
  }

  @Test
  fun higherQualityZImageVariantsUseVerifiedSizesAndSharedFiles() {
    val q5 = ImageGenerationModelRegistry.requireModel("z-image-turbo-q5-0-gguf")

    assertEquals("Z-Image Turbo Q5_0 GGUF", q5.displayName)
    assertEquals(8_357_268_485L, q5.totalSizeInBytes)
    assertTrue(q5.supportsChineseText)
    assertTrue(q5.requiredFiles.any { it.fileName == "z_image_turbo-Q5_0.gguf" })
    assertTrue(q5.requiredFiles.any { it.fileName == "ae.safetensors" })
    assertTrue(q5.requiredFiles.any { it.fileName == "qwen_3_4b_fp4_mixed.safetensors" })
  }

  @Test
  fun stableDiffusionEnglishCandidatesDeclareSingleCheckpointFiles() {
    val sd15 = ImageGenerationModelRegistry.requireModel("sd15-q4-0-gguf")

    assertEquals("Stable Diffusion 1.5 Q4_0 GGUF", sd15.displayName)
    assertEquals("CreativeML Open RAIL-M", sd15.license)
    assertEquals(false, sd15.supportsChineseText)
    assertEquals(1, sd15.requiredFiles.size)
    assertEquals(ImageGenerationModelFileRole.CHECKPOINT, sd15.requiredFiles.single().role)
    assertEquals(1_566_768_416L, sd15.totalSizeInBytes)
  }

  @Test
  fun visualCreationTaskUsesRealDownloadModelInsteadOfPlaceholder() {
    val models = createVisualCreationImageModels()

    assertEquals("absolute-reality-qnn-8gen2", models.first().name)
    assertEquals(12, models.size)
    val model = models.first()
    assertEquals("AbsoluteReality_qnn2.28_8gen2.zip", model.downloadFileName)
    assertTrue(model.url.endsWith("/AbsoluteReality_qnn2.28_8gen2.zip"))
    assertEquals(0, model.extraDataFiles.size)
    assertTrue(model.isZip)
    assertEquals("model", model.unzipDir)
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
