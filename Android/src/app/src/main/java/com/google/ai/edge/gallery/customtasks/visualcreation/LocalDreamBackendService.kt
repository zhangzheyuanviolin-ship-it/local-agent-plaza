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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.TimeUnit

class LocalDreamBackendService : Service() {
  private val extractedRuntimeDir: File by lazy { File(filesDir, RUNTIME_DIR) }
  private val runtimeDir: File by lazy { resolveRuntimeDir() }
  private val diagnosticLogFile: File by lazy {
    File(getExternalFilesDir("visual_creation_diagnostics") ?: filesDir, "local_dream_backend.log")
  }
  private var backendProcess: Process? = null

  override fun onCreate() {
    super.onCreate()
    appendDiagnostic("LocalDreamBackendService created")
    createNotificationChannel()
    prepareRuntimeDir()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    appendDiagnostic("Service start: action=${intent?.action}")
    startForeground(NOTIFICATION_ID, createNotification("正在初始化图像生成后端"))
    when (intent?.action) {
      ACTION_START -> {
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        if (modelPath.isNullOrBlank()) {
          Log.e(TAG, "Missing model path")
          stopSelf()
        } else if (startBackend(File(modelPath), intent.getBooleanExtra(EXTRA_USE_GPU, false))) {
          updateNotification("本地图像生成后端运行中")
        } else {
          updateNotification("本地图像生成后端启动失败")
          stopSelf()
        }
      }
      ACTION_STOP -> stopBackend()
    }
    return START_NOT_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    appendDiagnostic("LocalDreamBackendService destroyed")
    stopBackend()
    super.onDestroy()
  }

  private fun prepareRuntimeDir() {
    if (runtimeDir == File(applicationInfo.nativeLibraryDir)) {
      appendDiagnostic("Using installed native library directory for QNN runtime: ${runtimeDir.absolutePath}")
      return
    }
    extractedRuntimeDir.mkdirs()
    val qnnLibs = assets.list(QNN_ASSET_DIR) ?: emptyArray()
    appendDiagnostic("Found ${qnnLibs.size} QNN runtime assets")
    qnnLibs.forEach { fileName ->
      val targetFile = File(extractedRuntimeDir, fileName)
      val assetPath = "$QNN_ASSET_DIR/$fileName"
      val needsCopy =
        !targetFile.exists() ||
          assets.open(assetPath).use { asset -> targetFile.length() != asset.available().toLong() }
      if (needsCopy) {
        assets.open(assetPath).use { input ->
          targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        targetFile.setReadable(true, true)
        targetFile.setExecutable(true, true)
      }
    }
    extractedRuntimeDir.setReadable(true, true)
    extractedRuntimeDir.setExecutable(true, true)
  }

  private fun resolveRuntimeDir(): File {
    val nativeDir = File(applicationInfo.nativeLibraryDir)
    val nativeQnn = File(nativeDir, "libQnnHtp.so")
    val nativeSystem = File(nativeDir, "libQnnSystem.so")
    return if (nativeQnn.exists() && nativeSystem.exists()) {
      nativeDir
    } else {
      extractedRuntimeDir
    }
  }

  private fun startBackend(modelDir: File, useGpu: Boolean): Boolean {
    return try {
      stopBackendProcessOnly()
      killOrphanBackendProcesses()
      if (!modelDir.exists()) {
        appendDiagnostic("Model directory not found: ${modelDir.absolutePath}", isError = true)
        return false
      }
      val actualDir = findActualModelDir(modelDir)
      val modelType = detectModelType(actualDir)
      if (modelType == LocalDreamModelType.UNKNOWN) {
        appendDiagnostic("No Local Dream model files found under ${modelDir.absolutePath}", isError = true)
        return false
      }
      val executable = File(applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)
      if (!executable.exists()) {
        appendDiagnostic("Executable not found: ${executable.absolutePath}", isError = true)
        return false
      }
      val command = buildCommand(executable, actualDir, modelType, useGpu)
      val env = buildEnvironment()
      appendDiagnostic("Starting Local Dream backend: ${command.joinToString(" ")}")
      appendDiagnostic("LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}")
      appendDiagnostic("DSP_LIBRARY_PATH=${env["DSP_LIBRARY_PATH"]}")
      backendProcess =
        ProcessBuilder(command)
          .directory(File(applicationInfo.nativeLibraryDir))
          .redirectErrorStream(true)
          .apply { environment().putAll(env) }
          .start()
      startMonitorThread()
      true
    } catch (e: Throwable) {
      appendDiagnostic("Failed to start Local Dream backend: ${e.message}", e, isError = true)
      false
    }
  }

  private fun buildCommand(
    executable: File,
    modelDir: File,
    modelType: LocalDreamModelType,
    useGpu: Boolean,
  ): List<String> {
    val clipFile =
      when {
        File(modelDir, "clip.bin").exists() -> File(modelDir, "clip.bin")
        File(modelDir, "clip_v2.mnn").exists() -> File(modelDir, "clip.mnn")
        File(modelDir, "clip.mnn").exists() -> File(modelDir, "clip.mnn")
        else -> File(modelDir, "clip.bin")
      }
    val tokenizerFile = firstFileRecursive(modelDir) { it.name.equals("tokenizer.json", true) }
    val vaeDecoderFile =
      firstFileRecursive(modelDir) {
        it.name.startsWith("vae_decoder", true) &&
          it.name.endsWith(if (modelType == LocalDreamModelType.QNN) ".bin" else ".mnn", true)
      }
    val unetFile =
      firstFileRecursive(modelDir) {
        it.name.startsWith("unet", true) &&
          it.name.endsWith(if (modelType == LocalDreamModelType.QNN) ".bin" else ".mnn", true)
      }
    require(tokenizerFile != null) { "未找到 tokenizer.json" }
    require(vaeDecoderFile != null) { "未找到 VAE decoder 文件" }
    require(unetFile != null) { "未找到 UNet 文件" }

    val command =
      mutableListOf(
        executable.absolutePath,
        "--clip",
        clipFile.absolutePath,
        "--unet",
        unetFile.absolutePath,
        "--vae_decoder",
        vaeDecoderFile.absolutePath,
        "--tokenizer",
        tokenizerFile.absolutePath,
        "--port",
        LOCAL_DREAM_PORT.toString(),
        "--text_embedding_size",
        "768",
      )

    val vaeEncoderFile =
      firstFileRecursive(modelDir) {
        it.name.startsWith("vae_encoder", true) &&
          it.name.endsWith(if (modelType == LocalDreamModelType.QNN) ".bin" else ".mnn", true)
      }
    if (vaeEncoderFile != null) {
      command += listOf("--vae_encoder", vaeEncoderFile.absolutePath)
    }

    if (modelType == LocalDreamModelType.QNN) {
      command +=
        listOf(
          "--backend",
          File(runtimeDir, "libQnnHtp.so").absolutePath,
          "--system_library",
          File(runtimeDir, "libQnnSystem.so").absolutePath,
        )
      if (clipFile.name.endsWith(".mnn", true)) {
        command += "--use_cpu_clip"
      }
    } else {
      command += "--cpu"
      if (useGpu) {
        appendDiagnostic("MNN backend will receive use_opencl=true in generate requests")
      }
    }
    return command
  }

  private fun detectModelType(dir: File): LocalDreamModelType {
    val hasQnn = firstFileRecursive(dir) { it.name.startsWith("unet", true) && it.name.endsWith(".bin", true) }
    val hasMnn = firstFileRecursive(dir) { it.name.startsWith("unet", true) && it.name.endsWith(".mnn", true) }
    return when {
      hasQnn != null -> LocalDreamModelType.QNN
      hasMnn != null -> LocalDreamModelType.MNN
      else -> LocalDreamModelType.UNKNOWN
    }
  }

  private fun findActualModelDir(baseDir: File): File {
    val unetDir =
      firstFileRecursive(baseDir) {
        it.name.startsWith("unet", true) && (it.name.endsWith(".bin", true) || it.name.endsWith(".mnn", true))
      }?.parentFile
    return unetDir ?: baseDir
  }

  private fun firstFileRecursive(dir: File, predicate: (File) -> Boolean): File? {
    val files = dir.listFiles() ?: return null
    files.firstOrNull { it.isFile && predicate(it) }?.let { return it }
    files.filter { it.isDirectory }.forEach { child ->
      firstFileRecursive(child, predicate)?.let { return it }
    }
    return null
  }

  private fun buildEnvironment(): Map<String, String> {
    val libraryPath =
      listOf(
          runtimeDir.absolutePath,
          applicationInfo.nativeLibraryDir,
          "/system/lib64",
          "/vendor/lib64",
          "/vendor/lib64/egl",
        )
        .distinct()
        .joinToString(":")
    return mapOf("LD_LIBRARY_PATH" to libraryPath, "DSP_LIBRARY_PATH" to runtimeDir.absolutePath)
  }

  private fun startMonitorThread() {
    Thread {
        try {
          backendProcess?.let { process ->
            process.inputStream.bufferedReader().useLines { lines ->
              lines.forEach { line -> appendDiagnostic("Backend: $line") }
            }
            appendDiagnostic("Backend process exited with code ${process.waitFor()}", isError = true)
          }
        } catch (e: Throwable) {
          appendDiagnostic("Backend monitor stopped: ${e.message}", e, isError = true)
        }
      }
      .apply {
        isDaemon = true
        name = "LocalDreamBackendMonitor"
        start()
      }
  }

  private fun stopBackend() {
    stopBackendProcessOnly()
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun stopBackendProcessOnly() {
    backendProcess?.let { process ->
      try {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
          process.destroyForcibly()
        }
      } catch (e: Throwable) {
        appendDiagnostic("Failed to stop backend process: ${e.message}", e, isError = true)
      }
    }
    backendProcess = null
  }

  private fun killOrphanBackendProcesses() {
    try {
      val killer =
        ProcessBuilder(
            "sh",
            "-c",
            "for pid in $(pidof libstable_diffusion_core.so 2>/dev/null); do kill ${'$'}pid 2>/dev/null || true; done",
          )
          .redirectErrorStream(true)
          .start()
      killer.waitFor(3, TimeUnit.SECONDS)
      appendDiagnostic("Checked for orphan libstable_diffusion_core.so processes before startup")
    } catch (e: Throwable) {
      appendDiagnostic("Unable to clean orphan backend processes: ${e.message}", e, isError = true)
    }
  }

  private fun appendDiagnostic(message: String, throwable: Throwable? = null, isError: Boolean = false) {
    if (isError) {
      Log.w(TAG, message, throwable)
    } else {
      Log.i(TAG, message)
    }
    runCatching {
      diagnosticLogFile.parentFile?.mkdirs()
      diagnosticLogFile.appendText("${System.currentTimeMillis()} $message\n")
      throwable?.stackTraceToString()?.let { diagnosticLogFile.appendText(it + "\n") }
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "本地视觉创作后端",
          NotificationManager.IMPORTANCE_LOW,
        )
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  private fun createNotification(contentText: String): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("本地视觉创作")
      .setContentText(contentText)
      .setSmallIcon(android.R.drawable.stat_sys_download_done)
      .setOngoing(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()
  }

  private fun updateNotification(contentText: String) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(NOTIFICATION_ID, createNotification(contentText))
  }

  private enum class LocalDreamModelType {
    QNN,
    MNN,
    UNKNOWN,
  }

  companion object {
    private const val TAG = "LocalDreamBackendService"
    private const val CHANNEL_ID = "local_visual_creation_backend"
    private const val NOTIFICATION_ID = 2601
    private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
    private const val RUNTIME_DIR = "local_dream_runtime_libs"
    private const val QNN_ASSET_DIR = "qnnlibs"
    const val LOCAL_DREAM_PORT = 8081
    private const val ACTION_START = "com.localagent.plaza.visualcreation.LOCAL_DREAM_START"
    private const val ACTION_STOP = "com.localagent.plaza.visualcreation.LOCAL_DREAM_STOP"
    private const val EXTRA_MODEL_PATH = "model_path"
    private const val EXTRA_USE_GPU = "use_gpu"

    fun start(context: Context, modelPath: String, useGpu: Boolean = false) {
      val intent =
        Intent(context, LocalDreamBackendService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_MODEL_PATH, modelPath)
          putExtra(EXTRA_USE_GPU, useGpu)
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.startService(
        Intent(context, LocalDreamBackendService::class.java).apply { action = ACTION_STOP }
      )
    }
  }
}
