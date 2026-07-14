package com.google.ai.edge.gallery.customtasks.aikeyboard.model

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

data class AiKeyboardModelDownloadProgress(
    val percent: Int,
    val speedBytesPerSec: Long,
    val sourceDisplayName: String
)

class AiKeyboardModelRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedLanguage(): String {
        return prefs.getString(KEY_SELECTED_LANG, AiKeyboardModelCatalog.LANG_ZH) ?: AiKeyboardModelCatalog.LANG_ZH
    }

    fun setSelectedLanguage(language: String) {
        prefs.edit().putString(KEY_SELECTED_LANG, language).apply()
    }

    fun getSelectedModelId(language: String): String {
        val key = selectedModelKey(language)
        val saved = prefs.getString(key, null)
        if (!saved.isNullOrBlank()) return saved
        return AiKeyboardModelCatalog.defaultModelId(language)
    }

    fun setSelectedModelId(language: String, modelId: String) {
        prefs.edit().putString(selectedModelKey(language), modelId).apply()
    }

    fun getSelectedModel(language: String): AiKeyboardModelDescriptor {
        val selectedId = getSelectedModelId(language)
        return AiKeyboardModelCatalog.byId(selectedId)
            ?: AiKeyboardModelCatalog.modelsForLanguage(language).first()
    }

    fun getModelDir(modelId: String): File {
        return File(File(context.filesDir, "models"), modelId)
    }

    fun hasModel(modelId: String): Boolean {
        val descriptor = AiKeyboardModelCatalog.byId(modelId) ?: return false
        migrateLegacySmallModelIfNeeded(descriptor)
        val dir = getModelDir(modelId)
        return dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
    }

    fun ensureBundledModelsInstalled(): List<String> {
        val installed = mutableListOf<String>()
        AiKeyboardModelCatalog.models
            .filter { !it.bundledAssetZipPath.isNullOrBlank() }
            .forEach { descriptor ->
                if (isBundledBootstrapDone(descriptor.id)) return@forEach
                if (hasModel(descriptor.id)) {
                    markBundledBootstrapDone(descriptor.id)
                    return@forEach
                }
                installBundledModel(descriptor)
                markBundledBootstrapDone(descriptor.id)
                installed += descriptor.id
            }
        return installed
    }

    fun downloadAndInstall(
        modelId: String,
        onProgress: (AiKeyboardModelDownloadProgress) -> Unit = {}
    ) {
        val descriptor = AiKeyboardModelCatalog.byId(modelId)
            ?: throw IllegalArgumentException("unknown model id: $modelId")

        if (!descriptor.bundledAssetZipPath.isNullOrBlank()) {
            installBundledModel(descriptor)
            onProgress(
                AiKeyboardModelDownloadProgress(
                    percent = 100,
                    speedBytesPerSec = 0L,
                    sourceDisplayName = "内置模型"
                )
            )
            return
        }

        val safeId = modelId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val partFile = File(context.cacheDir, "${safeId}_model.part")
        val zipFile = File(context.cacheDir, "${safeId}_model.zip")
        val unzipDir = File(context.cacheDir, "${safeId}_unzipped")
        val tempInstallDir = File(context.cacheDir, "${safeId}_install")

        if (unzipDir.exists()) unzipDir.deleteRecursively()
        if (tempInstallDir.exists()) tempInstallDir.deleteRecursively()

        var stage = "download"
        try {
            downloadZipWithMirrors(descriptor, partFile, onProgress)
            stage = "validate"
            validateArchive(descriptor, partFile)

            if (zipFile.exists()) zipFile.delete()
            if (!partFile.renameTo(zipFile)) {
                copyDir(partFile, zipFile)
                partFile.delete()
            }

            stage = "install"
            installFromZipFile(descriptor, zipFile, unzipDir, tempInstallDir)
            markSourceFailureCleared(modelId)
        } catch (e: Exception) {
            if (stage != "download" && partFile.exists()) {
                partFile.delete()
            }
            throw e
        } finally {
            if (zipFile.exists()) zipFile.delete()
            if (unzipDir.exists()) unzipDir.deleteRecursively()
            if (tempInstallDir.exists()) tempInstallDir.deleteRecursively()
        }
    }

    fun deleteModel(modelId: String) {
        val descriptor = AiKeyboardModelCatalog.byId(modelId)
            ?: throw IllegalArgumentException("unknown model id: $modelId")

        val targetDir = getModelDir(modelId)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }

        ensureSelectedModelValid(descriptor.language)
    }

    private fun installBundledModel(descriptor: AiKeyboardModelDescriptor) {
        val assetPath = descriptor.bundledAssetZipPath ?: return
        if (hasModel(descriptor.id)) return

        val safeId = descriptor.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val zipFile = File(context.cacheDir, "${safeId}_bundled_model.zip")
        val unzipDir = File(context.cacheDir, "${safeId}_bundled_unzipped")
        val tempInstallDir = File(context.cacheDir, "${safeId}_bundled_install")

        if (zipFile.exists()) zipFile.delete()
        if (unzipDir.exists()) unzipDir.deleteRecursively()
        if (tempInstallDir.exists()) tempInstallDir.deleteRecursively()

        context.assets.open(assetPath).use { input ->
            FileOutputStream(zipFile).use { output ->
                input.copyTo(output)
            }
        }

        validateArchive(descriptor, zipFile)
        installFromZipFile(descriptor, zipFile, unzipDir, tempInstallDir)

        if (zipFile.exists()) zipFile.delete()
        if (unzipDir.exists()) unzipDir.deleteRecursively()
    }

    private fun installFromZipFile(
        descriptor: AiKeyboardModelDescriptor,
        zipFile: File,
        unzipDir: File,
        tempInstallDir: File
    ) {
        unzip(zipFile, unzipDir)

        val modelRoot = locateModelRoot(unzipDir)
        if (tempInstallDir.exists()) tempInstallDir.deleteRecursively()
        copyDir(modelRoot, tempInstallDir)

        val targetDir = getModelDir(descriptor.id)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.parentFile?.mkdirs()
        if (!tempInstallDir.renameTo(targetDir)) {
            copyDir(tempInstallDir, targetDir)
            tempInstallDir.deleteRecursively()
        }
    }

    private fun downloadZipWithMirrors(
        descriptor: AiKeyboardModelDescriptor,
        outFile: File,
        onProgress: (AiKeyboardModelDownloadProgress) -> Unit
    ) {
        val rankedSources = rankSourcesByProbe(descriptor.id, descriptor.downloadSources)
        if (rankedSources.isEmpty()) {
            throw IllegalStateException("no download source available")
        }

        val errors = mutableListOf<String>()
        rankedSources.forEach { source ->
            try {
                downloadFromSource(descriptor, source, outFile, onProgress)
                markLastSuccessfulSource(descriptor.id, source.id)
                return
            } catch (e: Exception) {
                markSourceFailure(descriptor.id, source.id)
                errors += "${source.displayName}: ${e.message}"
            }
        }

        throw IllegalStateException("all mirrors failed: ${errors.joinToString(" | ")}")
    }

    private fun rankSourcesByProbe(
        modelId: String,
        sources: List<ModelDownloadSource>
    ): List<ModelDownloadSource> {
        if (sources.size <= 1) return sources

        val stickySourceId = getLastSuccessfulSourceId(modelId)
        val scored = sources.mapIndexed { index, source ->
            val latency = probeSourceLatencyMs(source)
            val latencyScore = when {
                latency == Long.MAX_VALUE -> 900_000L
                else -> latency.coerceAtMost(899_999L)
            }
            val stickyBoost = if (
                !isOfficialSource(source) &&
                !stickySourceId.isNullOrBlank() &&
                source.id == stickySourceId
            ) {
                -500_000L
            } else {
                0L
            }
            val failurePenalty = if (isRecentlyFailed(modelId, source.id)) 300_000L else 0L
            val score = index * 1_000_000L + latencyScore + failurePenalty + stickyBoost
            source to score
        }
        return scored.sortedBy { it.second }.map { it.first }
    }

    private fun probeSourceLatencyMs(source: ModelDownloadSource): Long {
        return runCatching {
            val start = System.nanoTime()
            val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
            }
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            if (code !in 200..399 && code != 206) {
                return@runCatching Long.MAX_VALUE
            }
            ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
        }.getOrElse { Long.MAX_VALUE }
    }

    private fun downloadFromSource(
        descriptor: AiKeyboardModelDescriptor,
        source: ModelDownloadSource,
        outFile: File,
        onProgress: (AiKeyboardModelDownloadProgress) -> Unit
    ) {
        var resumeOffset = if (outFile.exists()) outFile.length().coerceAtLeast(0L) else 0L

        var connection = openDownloadConnection(source.url, resumeOffset)
        var code = connection.responseCode

        if (resumeOffset > 0L && code == HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            resumeOffset = 0L
            if (outFile.exists()) outFile.delete()
            connection = openDownloadConnection(source.url, 0L)
            code = connection.responseCode
        }

        if (code == HTTP_RANGE_NOT_SATISFIABLE) {
            connection.disconnect()
            val localSize = outFile.length().coerceAtLeast(0L)
            val expectedSize = descriptor.fileSizeBytes
            if (expectedSize > 0L && localSize == expectedSize) {
                return
            }
            if (outFile.exists()) outFile.delete()
            resumeOffset = 0L
            connection = openDownloadConnection(source.url, 0L)
            code = connection.responseCode
        }

        if (code !in 200..299 && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            throw IllegalStateException("http code $code")
        }

        val append = resumeOffset > 0L && code == HttpURLConnection.HTTP_PARTIAL
        val startBytes = if (append) resumeOffset else 0L
        val expectedTotalBytes = when {
            descriptor.fileSizeBytes > 0L -> descriptor.fileSizeBytes
            connection.contentLengthLong > 0L && append -> startBytes + connection.contentLengthLong
            connection.contentLengthLong > 0L -> connection.contentLengthLong
            else -> -1L
        }

        var downloadedBytes = startBytes
        var speedWindowStartNs = System.nanoTime()
        var speedWindowBytes = 0L
        var speedBytesPerSec = 0L
        var lastProgressReportNs = 0L

        connection.inputStream.use { input ->
            FileOutputStream(outFile, append).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    speedWindowBytes += read

                    val nowNs = System.nanoTime()
                    val speedWindowElapsedNs = nowNs - speedWindowStartNs
                    if (speedWindowElapsedNs >= SPEED_WINDOW_NS) {
                        speedBytesPerSec = (speedWindowBytes * 1_000_000_000.0 / speedWindowElapsedNs)
                            .roundToInt()
                            .toLong()
                            .coerceAtLeast(0L)
                        speedWindowBytes = 0L
                        speedWindowStartNs = nowNs
                    }

                    if (lastProgressReportNs == 0L || nowNs - lastProgressReportNs >= PROGRESS_REPORT_INTERVAL_NS) {
                        emitProgress(onProgress, downloadedBytes, expectedTotalBytes, speedBytesPerSec, source.displayName)
                        lastProgressReportNs = nowNs
                    }
                }
                output.flush()
            }
        }
        connection.disconnect()

        emitProgress(
            onProgress = onProgress,
            downloadedBytes = outFile.length(),
            totalBytes = expectedTotalBytes,
            speedBytesPerSec = speedBytesPerSec,
            sourceDisplayName = source.displayName
        )
    }

    private fun emitProgress(
        onProgress: (AiKeyboardModelDownloadProgress) -> Unit,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        sourceDisplayName: String
    ) {
        val percent = when {
            totalBytes <= 0L -> 0
            else -> ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        }
        onProgress(
            AiKeyboardModelDownloadProgress(
                percent = percent,
                speedBytesPerSec = speedBytesPerSec,
                sourceDisplayName = sourceDisplayName
            )
        )
    }

    private fun openDownloadConnection(url: String, rangeStart: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS
        connection.readTimeout = DOWNLOAD_READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        if (rangeStart > 0L) {
            connection.setRequestProperty("Range", "bytes=$rangeStart-")
        }
        connection.connect()
        return connection
    }

    private fun validateArchive(descriptor: AiKeyboardModelDescriptor, archiveFile: File) {
        if (!archiveFile.exists() || archiveFile.length() <= 0L) {
            throw IllegalStateException("archive is empty")
        }
        val expectedSha256 = descriptor.archiveSha256
        if (!expectedSha256.isNullOrBlank()) {
            val actualSha256 = sha256(archiveFile)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw IllegalStateException("archive checksum mismatch")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun selectedModelKey(language: String): String {
        return when (language) {
            AiKeyboardModelCatalog.LANG_EN -> KEY_SELECTED_MODEL_EN
            AiKeyboardModelCatalog.LANG_ZH -> KEY_SELECTED_MODEL_ZH
            else -> "selected_model_$language"
        }
    }

    private fun bundledBootstrapKey(modelId: String): String {
        return "bundled_bootstrap_done_$modelId"
    }

    private fun sourceSuccessKey(modelId: String): String {
        return "download_last_success_source_$modelId"
    }

    private fun sourceFailureTsKey(modelId: String, sourceId: String): String {
        return "download_source_failed_ts_${modelId}_$sourceId"
    }

    private fun isBundledBootstrapDone(modelId: String): Boolean {
        return prefs.getBoolean(bundledBootstrapKey(modelId), false)
    }

    private fun markBundledBootstrapDone(modelId: String) {
        prefs.edit().putBoolean(bundledBootstrapKey(modelId), true).apply()
    }

    private fun getLastSuccessfulSourceId(modelId: String): String? {
        return prefs.getString(sourceSuccessKey(modelId), null)
    }

    private fun markLastSuccessfulSource(modelId: String, sourceId: String) {
        prefs.edit().putString(sourceSuccessKey(modelId), sourceId).apply()
    }

    private fun markSourceFailure(modelId: String, sourceId: String) {
        prefs.edit().putLong(sourceFailureTsKey(modelId, sourceId), System.currentTimeMillis()).apply()
    }

    private fun markSourceFailureCleared(modelId: String) {
        val editor = prefs.edit()
        AiKeyboardModelCatalog.byId(modelId)?.downloadSources.orEmpty().forEach { source ->
            editor.remove(sourceFailureTsKey(modelId, source.id))
        }
        editor.apply()
    }

    private fun isRecentlyFailed(modelId: String, sourceId: String): Boolean {
        val failedTs = prefs.getLong(sourceFailureTsKey(modelId, sourceId), 0L)
        if (failedTs <= 0L) return false
        val elapsed = System.currentTimeMillis() - failedTs
        return elapsed in 0 until SOURCE_FAILURE_COOLDOWN_MS
    }

    private fun isOfficialSource(source: ModelDownloadSource): Boolean {
        return source.id.contains("official", ignoreCase = true) ||
            source.displayName.contains("官方")
    }

    private fun ensureSelectedModelValid(language: String) {
        val selectedId = getSelectedModelId(language)
        if (hasModel(selectedId)) return

        val fallbackInstalledId = AiKeyboardModelCatalog.modelsForLanguage(language)
            .firstOrNull { hasModel(it.id) }
            ?.id

        val nextId = fallbackInstalledId ?: AiKeyboardModelCatalog.defaultModelId(language)
        setSelectedModelId(language, nextId)
    }

    private fun migrateLegacySmallModelIfNeeded(descriptor: AiKeyboardModelDescriptor) {
        if (descriptor.tierRank != 1) return

        val targetDir = getModelDir(descriptor.id)
        if (targetDir.exists()) return

        val legacyDir = File(File(context.filesDir, "models"), descriptor.language)
        if (!legacyDir.exists() || !legacyDir.isDirectory) return
        if (legacyDir.listFiles().isNullOrEmpty()) return

        targetDir.parentFile?.mkdirs()
        if (!legacyDir.renameTo(targetDir)) {
            copyDir(legacyDir, targetDir)
            legacyDir.deleteRecursively()
        }
    }

    private fun unzip(zipFile: File, outputDir: File) {
        outputDir.mkdirs()
        val rootPath = outputDir.canonicalPath

        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outFile = File(outputDir, entry.name)
                val canonical = outFile.canonicalPath
                if (!canonical.startsWith(rootPath)) {
                    throw SecurityException("zip entry path traversal: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun locateModelRoot(unzipDir: File): File {
        val unzipSelfLooksLikeModel = File(unzipDir, "am/final.mdl").exists() &&
            File(unzipDir, "conf/model.conf").exists()
        if (unzipSelfLooksLikeModel) return unzipDir

        val direct = unzipDir.listFiles().orEmpty().firstOrNull { file ->
            file.isDirectory && file.name.startsWith("vosk-model")
        }
        if (direct != null) return direct

        val semanticDirect = unzipDir.listFiles().orEmpty().firstOrNull { file ->
            file.isDirectory &&
                File(file, "am/final.mdl").exists() &&
                File(file, "conf/model.conf").exists()
        }
        if (semanticDirect != null) return semanticDirect

        val nested = unzipDir.walkTopDown().firstOrNull { file ->
            file.isDirectory && file.name.startsWith("vosk-model")
        }
        if (nested != null) return nested

        val semanticNested = unzipDir.walkTopDown().firstOrNull { file ->
            file.isDirectory &&
                File(file, "am/final.mdl").exists() &&
                File(file, "conf/model.conf").exists()
        }
        if (semanticNested != null) return semanticNested

        throw IllegalStateException("cannot locate unpacked model directory")
    }

    private fun copyDir(src: File, dst: File) {
        if (src.isDirectory) {
            if (!dst.exists()) dst.mkdirs()
            src.listFiles().orEmpty().forEach { child ->
                copyDir(child, File(dst, child.name))
            }
        } else {
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "voice_ime_prefs"
        private const val KEY_SELECTED_LANG = "selected_lang"
        private const val KEY_SELECTED_MODEL_ZH = "selected_model_zh"
        private const val KEY_SELECTED_MODEL_EN = "selected_model_en"
        private const val DOWNLOAD_BUFFER_SIZE = 32 * 1024
        private const val PROBE_TIMEOUT_MS = 10_000
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val SPEED_WINDOW_NS = 1_000_000_000L
        private const val PROGRESS_REPORT_INTERVAL_NS = 300_000_000L
        private const val SOURCE_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
