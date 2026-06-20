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
  fun registryIncludesOnlyDownloadableLocalDreamModels() {
    val modelIds = ImageGenerationModelRegistry.recommendedModels.map { it.modelId }

    assertEquals(
      listOf(
        "absolute-reality-qnn-8gen2",
        "dreamshaper-v8-qnn-8gen2",
        "realistic-vision-hyper-qnn-8gen2",
        "majicmix-realistic-v7-qnn-8gen2",
        "anything-v5-qnn-8gen2",
        "meina-mix-v12-qnn-8gen2",
        "abyss-orange-mix3-qnn-8gen2",
        "absolute-reality-mnn-cpu",
        "anything-v5-mnn-cpu",
        "chillout-mix-mnn-cpu",
        "cute-yuki-mix-mnn-cpu",
        "qtea-mix-mnn-cpu",
        "sdxl-base-qnn-8gen3",
        "illustrious-v16-qnn-8gen3",
        "illustrious-v16-dmd2-qnn-8gen3",
        "realvis-xl-v5-qnn-8gen3",
        "juggernaut-xl-qnn-8gen3",
        "cyber-realistic-v10-qnn-8gen3",
        "wai-illustrious-v170-dmd2-qnn-8gen3",
        "intorealism-ultra-v11-qnn-8gen3",
      ),
      modelIds,
    )
  }

  @Test
  fun localDreamQnnModelDeclaresArchiveAndBackendMetadata() {
    val model = ImageGenerationModelRegistry.requireModel("absolute-reality-qnn-8gen2")

    assertEquals("Absolute Reality QNN 8gen2", model.displayName)
    assertEquals(ImageGenerationBackend.LOCAL_DREAM_QNN_MNN, model.backend)
    assertEquals("QNN ZIP", model.format)
    assertEquals(false, model.supportsChineseText)
    assertTrue(model.supportsTextToImage)
    assertEquals(false, model.lowMemoryRecommended)
    assertEquals(1, model.requiredFiles.size)
    assertEquals(ImageGenerationModelFileRole.MODEL_ARCHIVE, model.requiredFiles.single().role)
    assertEquals("AbsoluteReality_qnn2.28_8gen2.zip", model.requiredFiles.single().fileName)
    assertEquals(1_128_267_776L, model.totalSizeInBytes)
    assertEquals("absolute-reality-qnn-8gen2-2026-06-14", model.localVersion)
  }

  @Test
  fun qnnCommunityModelDeclaresLocalDreamArchiveMetadata() {
    val model = ImageGenerationModelRegistry.requireModel("dreamshaper-v8-qnn-8gen2")

    assertEquals("DreamShaper V8 QNN 8gen2", model.displayName)
    assertEquals(ImageGenerationBackend.LOCAL_DREAM_QNN_MNN, model.backend)
    assertEquals("QNN ZIP", model.format)
    assertTrue(model.supportsTextToImage)
    assertEquals(false, model.supportsChineseText)
    assertEquals(false, model.lowMemoryRecommended)
    assertEquals(1, model.requiredFiles.size)
    assertEquals("DreamShaperV8_qnn2.28_8gen2.zip", model.requiredFiles.single().fileName)
    assertTrue(model.requiredFiles.single().downloadUrl.startsWith("https://huggingface.co/xororz/sd-qnn/"))
    assertEquals("dreamshaper-v8-qnn-8gen2-2026-06-20", model.localVersion)
  }

  @Test
  fun mnnCommunityModelDeclaresLocalDreamCpuFallbackMetadata() {
    val model = ImageGenerationModelRegistry.requireModel("anything-v5-mnn-cpu")

    assertEquals("Anything V5 MNN CPU", model.displayName)
    assertEquals(ImageGenerationBackend.LOCAL_DREAM_QNN_MNN, model.backend)
    assertEquals("MNN ZIP", model.format)
    assertTrue(model.lowMemoryRecommended)
    assertEquals("AnythingV5.zip", model.requiredFiles.single().fileName)
    assertTrue(model.requiredFiles.single().downloadUrl.startsWith("https://huggingface.co/xororz/sd-mnn/"))
  }

  @Test
  fun sdxlModelDeclaresLocalDreamQnn8Gen3Metadata() {
    val model = ImageGenerationModelRegistry.requireModel("sdxl-base-qnn-8gen3")

    assertEquals("SDXL Base 1.0 QNN 8gen3", model.displayName)
    assertEquals(ImageGenerationBackend.LOCAL_DREAM_QNN_MNN, model.backend)
    assertEquals("QNN SDXL ZIP", model.format)
    assertEquals("Local Dream SDXL QNN", model.family)
    assertEquals(1024, model.recommendedWidth)
    assertEquals(1024, model.recommendedHeight)
    assertEquals(1280, model.localDreamTextEmbeddingSize)
    assertEquals(16, model.minMemoryGb)
    assertEquals("sdxl_base_qnn2.28_8gen3.zip", model.requiredFiles.single().fileName)
    assertEquals(3_753_226_114L, model.totalSizeInBytes)
    assertTrue(model.requiredFiles.single().downloadUrl.startsWith("https://huggingface.co/xororz/sdxl-qnn/"))
    assertEquals("sdxl-base-qnn-8gen3-2026-06-20", model.localVersion)
  }

  @Test
  fun oldStableDiffusionCppModelsAreNotInDefaultDownloadList() {
    val modelIds = ImageGenerationModelRegistry.recommendedModels.map { it.modelId }

    assertTrue(modelIds.none { it.startsWith("z-image-turbo") })
    assertTrue(modelIds.none { it.startsWith("sd15-") })
    assertTrue(ImageGenerationModelRegistry.recommendedModels.all { it.backend == ImageGenerationBackend.LOCAL_DREAM_QNN_MNN })
  }

  @Test
  fun visualCreationTaskUsesRealDownloadModelInsteadOfPlaceholder() {
    val models = createVisualCreationImageModels()

    assertEquals("absolute-reality-qnn-8gen2", models.first().name)
    assertEquals(20, models.size)
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
