/*
 * Copyright 2025 Google LLC
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

plugins {
  alias(libs.plugins.android.application)
  // Note: set apply to true to enable google-services (requires google-services.json).
  alias(libs.plugins.google.services) apply false
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.oss.licenses)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

// Build the music runtime from the exact device-validated Box Local Music 0.4.9 golden source.
// The preparation script pins the commit, replays the complete patch chain, audits the effective
// engine, rewrites only the Java package, and emits it into this app's normal Java source set.
providers.exec {
  commandLine("python3", rootProject.file("scripts/prepare_box049_runtime.py").absolutePath)
}.result.get().assertNormalExitValue()

// MCP215: keep the MCP210 engine/runtime baseline while hardening the textual COMPAT boundary.
// The patch is fail-fast and idempotent: if its exact anchors ever drift, the build stops rather
// than silently shipping without multi-family tool-call normalization.
providers.exec {
  commandLine("python3", rootProject.file("scripts/patch_tool_call_wire_compat.py").absolutePath)
}.result.get().assertNormalExitValue()

// MCP224: preserve the validated runtime while adding copyable model lifecycle diagnostics,
// true manual Native selection, and user-controlled COMPAT tool-loop termination.
providers.exec {
  commandLine("python3", rootProject.file("scripts/patch_mcp224_model_diagnostics.py").absolutePath)
}.result.get().assertNormalExitValue()

// MCP224 CI follow-up: run after the main MCP224 patch so inference callbacks can append the
// diagnostic exception without requiring an Android Context that is unavailable in runInference.
providers.exec {
  commandLine(
    "python3",
    rootProject.file("scripts/patch_mcp224_inference_diag_context_fix.py").absolutePath,
  )
}.result.get().assertNormalExitValue()

// MCP251: add five opt-in Office skills through the existing workspace + skill protocol only.
// This patch is deliberately after the established MCP224 compatibility patches and is fail-closed.
providers.exec {
  commandLine("python3", rootProject.file("scripts/patch_mcp251_office_skills.py").absolutePath)
}.result.get().assertNormalExitValue()

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35

  // 允许通过环境变量 APPLICATION_ID_SUFFIX 为非主线渠道（如 mcp、experimental）追加包名后缀，
  // 使新构件可与已安装的稳定版（com.localagent.plaza）在同一台设备并行安装而互不覆盖。
  val appApplicationIdSuffix =
    providers.environmentVariable("APPLICATION_ID_SUFFIX").orNull ?: ""
  val appApplicationId = "com.localagent.plaza${appApplicationIdSuffix}"
  val localVersionCode =
    providers.environmentVariable("LOCAL_VERSION_CODE").orNull?.toIntOrNull() ?: 230
  val localVersionName =
    providers.environmentVariable("LOCAL_VERSION_NAME").orNull ?: "1.0.14-plaza.4"
  val releaseKeystorePath = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PATH").orNull

  defaultConfig {
    applicationId = appApplicationId
    minSdk = 31
    targetSdk = 35
    versionCode = localVersionCode
    versionName = localVersionName
    ndk { abiFilters += listOf("arm64-v8a") }

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "${appApplicationId}.oauthredirect"
    manifestPlaceholders["appDeepLinkScheme"] = appApplicationId
    manifestPlaceholders["applicationName"] = "com.google.ai.edge.gallery.GalleryApplication"
    manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (!releaseKeystorePath.isNullOrBlank()) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PASSWORD").orNull
        keyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
        keyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig =
        if (!releaseKeystorePath.isNullOrBlank()) {
          signingConfigs.getByName("release")
        } else {
          signingConfigs.getByName("debug")
        }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  sourceSets.getByName("main").java.srcDir(rootProject.file("build/generated/box049/java"))
  packagingOptions.pickFirst("lib/**/libLiteRt*.so")
  packagingOptions.doNotStrip("**/libLiteRt*.so")
  externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.litert)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.play.services.oss.licenses)
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.analytics)
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  testImplementation("com.google.truth:truth:1.4.4")
  // MCP252: Android's local-unit-test android.jar exposes org.json stubs that throw at runtime.
  // Use the real JVM implementation so the evidence-derived request-normalization regressions execute.
  testImplementation("org.json:json:20240303")
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.core)
  implementation("com.tom-roush:pdfbox-android:2.0.27.0")
  implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")
  implementation("com.arthenica:smart-exception-java:0.2.1")
  implementation("org.jsoup:jsoup:1.22.2")
  implementation("net.java.dev.jna:jna:5.18.1@aar")
  implementation("com.alphacephei:vosk-android:0.3.75@aar")
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
