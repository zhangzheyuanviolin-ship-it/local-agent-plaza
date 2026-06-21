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

internal enum class LocalDreamModelType(val cliType: String, val usesQnn: Boolean) {
  SD15_QNN("sd15npu", true),
  SD15_MNN("sd15cpu", false),
  SDXL_QNN("sdxl", true),
  UNKNOWN("", false),
}

internal fun buildLocalDreamCommand(
  executable: File,
  modelDir: File,
  runtimeDir: File,
  modelType: LocalDreamModelType,
  width: Int,
  height: Int,
): List<String> {
  require(modelType != LocalDreamModelType.UNKNOWN) { "未知 Local Dream 模型类型" }
  val command =
    mutableListOf(
      executable.absolutePath,
      "--type",
      modelType.cliType,
      "--model_dir",
      modelDir.absolutePath,
      "--port",
      LocalDreamBackendService.LOCAL_DREAM_PORT.toString(),
    )
  if (modelType.usesQnn) {
    command += listOf("--lib_dir", runtimeDir.absolutePath)
  }
  if (modelType == LocalDreamModelType.SD15_QNN && (width != 512 || height != 512)) {
    selectSd15PatchFile(modelDir = modelDir, width = width, height = height)?.let { patch ->
      command += listOf("--patch", patch.absolutePath)
    }
  }
  if (File(modelDir, "V_PRED").exists()) {
    command += "--use_v_pred"
  }
  if (modelType == LocalDreamModelType.SDXL_QNN) {
    command += "--lowram"
  }
  return command
}

internal fun detectLocalDreamModelType(dir: File): LocalDreamModelType {
  val hasSdxlMarker = File(dir, "SDXL").exists()
  val hasQnn = firstLocalDreamFileRecursive(dir) { it.name.startsWith("unet", true) && it.name.endsWith(".bin", true) }
  val hasMnn = firstLocalDreamFileRecursive(dir) { it.name.startsWith("unet", true) && it.name.endsWith(".mnn", true) }
  return when {
    hasSdxlMarker && hasQnn != null -> LocalDreamModelType.SDXL_QNN
    hasQnn != null -> LocalDreamModelType.SD15_QNN
    hasMnn != null -> LocalDreamModelType.SD15_MNN
    else -> LocalDreamModelType.UNKNOWN
  }
}

private fun selectSd15PatchFile(modelDir: File, width: Int, height: Int): File? {
  val preferred =
    if (width == height) {
      listOf(File(modelDir, "$width.patch"), File(modelDir, "${width}x$height.patch"))
    } else {
      listOf(File(modelDir, "${width}x$height.patch"))
    }
  return preferred.firstOrNull { it.exists() }
}

private fun firstLocalDreamFileRecursive(dir: File, predicate: (File) -> Boolean): File? {
  val files = dir.listFiles() ?: return null
  files.firstOrNull { it.isFile && predicate(it) }?.let { return it }
  files.filter { it.isDirectory }.forEach { child ->
    firstLocalDreamFileRecursive(child, predicate)?.let { return it }
  }
  return null
}

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
        } else if (
          startBackend(
            modelDir = File(modelPath),
            useGpu = intent.getBooleanExtra(EXTRA_USE_GPU, false),
            textEmbeddingSize = intent.getIntExtra(EXTRA_TEXT_EMBEDDING_SIZE, DEFAULT_TEXT_EMBEDDING_SIZE),
            width = intent.getIntExtra(EXTRA_WIDTH, DEFAULT_WIDTH),
            height = intent.getIntExtra(EXTRA_HEIGHT, DEFAULT_HEIGHT),
          )
        ) {
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

  private fun startBackend(
    modelDir: File,
    useGpu: Boolean,
    textEmbeddingSize: Int,
    width: Int,
    height: Int,
  ): Boolean {
    return try {
      stopBackendProcessOnly()
      killOrphanBackendProcesses()
      if (!modelDir.exists()) {
        appendDiagnostic("Model directory not found: ${modelDir.absolutePath}", isError = true)
        return false
      }
      val actualDir = findActualModelDir(modelDir)
      val modelType = detectLocalDreamModelType(actualDir)
      if (modelType == LocalDreamModelType.UNKNOWN) {
        appendDiagnostic("No Local Dream model files found under ${modelDir.absolutePath}", isError = true)
        return false
      }
      val executable = File(applicationInfo.nativeLibraryDir, EXECUTABLE_NAME)
      if (!executable.exists()) {
        appendDiagnostic("Executable not found: ${executable.absolutePath}", isError = true)
        return false
      }
      val command =
        buildLocalDreamCommand(
          executable = executable,
          modelDir = actualDir,
          runtimeDir = runtimeDir,
          modelType = modelType,
          width = width,
          height = height,
        )
      if (modelType == LocalDreamModelType.SD15_MNN && useGpu) {
        appendDiagnostic("MNN backend will receive use_opencl=true in generate requests")
      }
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
      setActiveBackendState(this, modelDir.absolutePath, textEmbeddingSize, width, height)
      startMonitorThread()
      true
    } catch (e: Throwable) {
      appendDiagnostic("Failed to start Local Dream backend: ${e.message}", e, isError = true)
      false
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
    clearActiveModelPath(this)
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
    private const val EXTRA_TEXT_EMBEDDING_SIZE = "text_embedding_size"
    private const val EXTRA_WIDTH = "width"
    private const val EXTRA_HEIGHT = "height"
    private const val PREFS_NAME = "local_dream_backend"
    private const val KEY_ACTIVE_MODEL_PATH = "active_model_path"
    private const val KEY_ACTIVE_TEXT_EMBEDDING_SIZE = "active_text_embedding_size"
    private const val KEY_ACTIVE_WIDTH = "active_width"
    private const val KEY_ACTIVE_HEIGHT = "active_height"
    private const val DEFAULT_TEXT_EMBEDDING_SIZE = 768
    private const val DEFAULT_WIDTH = 512
    private const val DEFAULT_HEIGHT = 512

    fun start(
      context: Context,
      modelPath: String,
      useGpu: Boolean = false,
      textEmbeddingSize: Int = 768,
      width: Int = 512,
      height: Int = 512,
    ) {
      val intent =
        Intent(context, LocalDreamBackendService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_MODEL_PATH, modelPath)
          putExtra(EXTRA_USE_GPU, useGpu)
          putExtra(EXTRA_TEXT_EMBEDDING_SIZE, textEmbeddingSize)
          putExtra(EXTRA_WIDTH, width)
          putExtra(EXTRA_HEIGHT, height)
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

    fun getActiveModelPath(context: Context): String? {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ACTIVE_MODEL_PATH, null)
    }

    fun getActiveTextEmbeddingSize(context: Context): Int {
      return context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_ACTIVE_TEXT_EMBEDDING_SIZE, DEFAULT_TEXT_EMBEDDING_SIZE)
    }

    fun getActiveWidth(context: Context): Int {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ACTIVE_WIDTH, DEFAULT_WIDTH)
    }

    fun getActiveHeight(context: Context): Int {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_ACTIVE_HEIGHT, DEFAULT_HEIGHT)
    }

    private fun setActiveBackendState(
      context: Context,
      modelPath: String,
      textEmbeddingSize: Int,
      width: Int,
      height: Int,
    ) {
      context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ACTIVE_MODEL_PATH, File(modelPath).absolutePath)
        .putInt(KEY_ACTIVE_TEXT_EMBEDDING_SIZE, textEmbeddingSize)
        .putInt(KEY_ACTIVE_WIDTH, width)
        .putInt(KEY_ACTIVE_HEIGHT, height)
        .apply()
    }

    private fun clearActiveModelPath(context: Context) {
      context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_ACTIVE_MODEL_PATH)
        .remove(KEY_ACTIVE_TEXT_EMBEDDING_SIZE)
        .remove(KEY_ACTIVE_WIDTH)
        .remove(KEY_ACTIVE_HEIGHT)
        .apply()
    }
  }
}
