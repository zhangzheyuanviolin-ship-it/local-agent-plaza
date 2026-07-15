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

package com.google.ai.edge.gallery.ui.modelmanager

import com.google.ai.edge.gallery.data.huggingFaceModelFileUrl
import com.google.ai.edge.gallery.data.huggingFaceModelFileUrls
import com.google.ai.edge.gallery.data.modelAllowlistUrls
import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAllowlistSourceTest {
  @Test
  fun downstreamBuildUsesPlazaAllowlistBeforeGoogleUpstream() {
    val urls = modelAllowlistUrls(version = "1_0_14")

    assertEquals(
      "https://raw.githubusercontent.com/zhangzheyuanviolin-ship-it/local-agent-plaza/" +
        "refs/heads/main/model_allowlists/1_0_14.json",
      urls.first(),
    )
    assertFalse(urls.first().contains("feature/"))
    assertEquals(
      "https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists/1_0_14.json",
      urls.last(),
    )
  }

  @Test
  fun bundledPlazaAllowlistContainsExpandedModelCatalog() {
    val file = File("src/main/assets/model_allowlists/1_0_14.json")
    assertTrue("Bundled model allowlist must exist in APK assets", file.exists())

    val root = JsonParser.parseString(file.readText()).asJsonObject
    assertEquals(48, root.getAsJsonArray("models").size())
  }

  @Test
  fun huggingFaceModelUrlFallsBackToMainWhenRevisionIsBlank() {
    val url =
      huggingFaceModelFileUrl(
        modelId = "litert-community/Qwen3-4B",
        revision = "",
        modelFile = "qwen3_4b_mixed_int4.litertlm",
      )

    assertEquals(
      "https://huggingface.co/litert-community/Qwen3-4B/resolve/main/" +
        "qwen3_4b_mixed_int4.litertlm?download=true",
      url,
    )
    assertFalse(url.contains("resolve//"))
  }

  @Test
  fun huggingFaceModelUrlsPreferChinaMirrorThenOfficialSource() {
    val urls =
      huggingFaceModelFileUrls(
        modelId = "litert-community/Qwen3-4B",
        revision = "abc123",
        modelFile = "qwen3_4b_mixed_int4.litertlm",
      )

    assertEquals(
      "https://hf-mirror.com/litert-community/Qwen3-4B/resolve/abc123/" +
        "qwen3_4b_mixed_int4.litertlm?download=true",
      urls[0],
    )
    assertEquals(
      "https://huggingface.co/litert-community/Qwen3-4B/resolve/abc123/" +
        "qwen3_4b_mixed_int4.litertlm?download=true",
      urls[1],
    )
  }

  @Test
  fun huggingFaceModelUrlUsesPinnedRevisionWhenPresent() {
    val url =
      huggingFaceModelFileUrl(
        modelId = "litert-community/Qwen3-8B",
        revision = "71ff705588319d52d374977eff3da4eee0c0d26e",
        modelFile = "qwen3_8b_mixed_int4.litertlm",
      )

    assertTrue(url.contains("/resolve/71ff705588319d52d374977eff3da4eee0c0d26e/"))
  }
}
