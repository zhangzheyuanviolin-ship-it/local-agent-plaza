#!/usr/bin/env python3
"""MCP238: replace broken Qwen3.5-2B bundles with the verified Plaza Q8/4096 bundle.

The verified model is larger than GitHub's per-release-asset limit, so the app downloads four
public release assets, resumes safely inside each part, reconstructs the original .litertlm file,
and validates the final byte size and SHA-256 before exposing it to LiteRT-LM.
"""

from __future__ import annotations

import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]  # Android/src
REPO_ROOT = ROOT.parents[1]

MODEL_NAME = "Qwen3.5-2B LiteRT-LM Q8 4096 Plaza"
MODEL_ID = "local-agent-plaza/Qwen3.5-2B-Q8-4096"
MODEL_FILE = "Qwen3.5-2B-LiteRT-LM-Q8-4096.litertlm"
MODEL_VERSION = "qwen35-2b-q8-4096-v1"
MODEL_SIZE = 4_780_966_112
MODEL_SHA256 = "a3a7cd9d05242200a4f819228e7cd3987e046f5fd81b030d71eb88e4a96fcd03"
RELEASE_BASE = (
    "https://github.com/zhangzheyuanviolin-ship-it/local-agent-plaza/releases/download/"
    f"{MODEL_VERSION}"
)
PART_URLS = [f"{RELEASE_BASE}/{MODEL_FILE}.part{i:02d}" for i in range(4)]
PART_SIZES = [1_200_000_000, 1_200_000_000, 1_200_000_000, 1_180_966_112]


def fail(message: str) -> None:
    print(f"MCP238 patch failure: {message}", file=sys.stderr)
    raise SystemExit(1)


def replace_once(path: Path, old: str, new: str, marker: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"MCP238 already patched: {path.relative_to(REPO_ROOT)}")
        return
    count = text.count(old)
    if count != 1:
        fail(f"expected one anchor in {path.relative_to(REPO_ROOT)}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"MCP238 patched: {path.relative_to(REPO_ROOT)}")


def patch_allowlist(path: Path) -> None:
    if not path.exists():
        fail(f"allowlist missing: {path}")
    data = json.loads(path.read_text(encoding="utf-8"))
    models = data.get("models")
    if not isinstance(models, list):
        fail(f"allowlist has no models list: {path}")

    old_names = {
        "Qwen3.5-2B LiteRT-LM Q8 experimental",
        "Qwen3.5-2B LiteRT-LM Q4 experimental",
    }
    filtered = [
        model
        for model in models
        if model.get("name") not in old_names
        and model.get("name") != MODEL_NAME
        and not (
            model.get("modelId") == "paulsp94/Qwen3.5-2B-LiteRT-LM"
            and model.get("modelFile") in {"qwen35_2b.litertlm", "qwen35_2b_q4.litertlm"}
        )
    ]

    new_model = {
        "name": MODEL_NAME,
        "modelId": MODEL_ID,
        "modelFile": MODEL_FILE,
        "description": (
            "Local Agent Plaza verified Qwen3.5 2B Q8 LiteRT-LM bundle rebuilt with the current "
            "Qwen3.5 exporter and LiteRT-LM 0.15 runtime contract. The app downloads four public "
            "GitHub Release parts, reconstructs the exact 4,780,966,112-byte .litertlm file, and "
            "verifies SHA-256 before loading. 4096 context. Experimental Agent validation target."
        ),
        "sizeInBytes": MODEL_SIZE,
        "minDeviceMemoryInGb": 12,
        "commitHash": MODEL_VERSION,
        "url": PART_URLS[0],
        "defaultConfig": {
            "topK": 20,
            "topP": 0.8,
            "temperature": 0.6,
            "maxTokens": 1536,
            "accelerators": "gpu,cpu",
            "maxContextLength": 4096,
        },
        "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat"],
        "bestForTaskTypes": ["llm_agent_chat"],
    }
    filtered.append(new_model)
    data["models"] = filtered
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    remaining_old = [m for m in filtered if m.get("name") in old_names]
    if remaining_old:
        fail(f"broken Qwen3.5-2B entries remained in {path}")
    matches = [m for m in filtered if m.get("name") == MODEL_NAME]
    if len(matches) != 1:
        fail(f"expected one MCP238 Qwen3.5 model in {path}, found {len(matches)}")
    print(
        f"MCP238 allowlist updated: {path.relative_to(REPO_ROOT)} "
        f"(removed old Q4/Q8; added {MODEL_NAME})"
    )


for allowlist in (
    REPO_ROOT / "model_allowlists/1_0_14.json",
    ROOT / "app/src/main/assets/model_allowlists/1_0_14.json",
):
    patch_allowlist(allowlist)

# Pass multipart release metadata to WorkManager only for the MCP238 verified model.
repo_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt"
repo_anchor = """        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)\n\n    if (model.extraDataFiles.isNotEmpty()) {\n"""
repo_insert = f"""        .putLong(KEY_MODEL_TOTAL_BYTES, totalBytes)\n\n    // MCP238 Qwen3.5 multipart release download.\n    if (model.name == \"{MODEL_NAME}\") {{\n      inputDataBuilder\n        .putString(\"KEY_MODEL_MULTIPART_URLS\", \"{'\\\\n'.join(PART_URLS)}\")\n        .putString(\"KEY_MODEL_MULTIPART_PART_SIZES\", \"{','.join(str(x) for x in PART_SIZES)}\")\n        .putString(\"KEY_MODEL_EXPECTED_SHA256\", \"{MODEL_SHA256}\")\n    }}\n\n    if (model.extraDataFiles.isNotEmpty()) {{\n"""
replace_once(repo_path, repo_anchor, repo_insert, "// MCP238 Qwen3.5 multipart release download.")

worker_path = ROOT / "app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt"

# MessageDigest import for final full-file validation.
replace_once(
    worker_path,
    "import java.net.URL\n",
    "import java.net.URL\nimport java.security.MessageDigest\n",
    "import java.security.MessageDigest",
)

worker_vars_anchor = """    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)\n    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)\n\n    return withContext(Dispatchers.IO) {\n"""
worker_vars_insert = """    val totalBytes = inputData.getLong(KEY_MODEL_TOTAL_BYTES, 0L)\n    val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)\n    // MCP238 Qwen3.5 multipart metadata. Empty for every normal single-file model.\n    val multipartUrls =\n      inputData\n        .getString(\"KEY_MODEL_MULTIPART_URLS\")\n        ?.split(\"\\n\")\n        ?.map { it.trim() }\n        ?.filter { it.isNotEmpty() }\n        ?: emptyList()\n    val multipartPartSizes =\n      inputData\n        .getString(\"KEY_MODEL_MULTIPART_PART_SIZES\")\n        ?.split(\",\")\n        ?.mapNotNull { it.trim().toLongOrNull() }\n        ?: emptyList()\n    val multipartExpectedSha256 = inputData.getString(\"KEY_MODEL_EXPECTED_SHA256\").orEmpty()\n\n    return withContext(Dispatchers.IO) {\n"""
replace_once(worker_path, worker_vars_anchor, worker_vars_insert, "// MCP238 Qwen3.5 multipart metadata.")

multipart_anchor = """            val candidateUrls =\n              file.url.split(\"\\n\").map { it.trim() }.filter { it.isNotEmpty() }.distinct()\n"""
multipart_block = """            // MCP238 Qwen3.5 multipart reconstruction. Other models keep the original path below.\n            if (file.fileName == fileName && multipartUrls.isNotEmpty()) {\n              if (multipartUrls.size != multipartPartSizes.size) {\n                throw IOException(\n                  \"Multipart metadata mismatch: urls=${multipartUrls.size} sizes=${multipartPartSizes.size}\"\n                )\n              }\n              val multipartExpectedBytes = multipartPartSizes.sum()\n              if (multipartExpectedBytes <= 0L) {\n                throw IOException(\"Multipart expected size is invalid: $multipartExpectedBytes\")\n              }\n              if (partialFile.length() > multipartExpectedBytes) {\n                Log.w(TAG, \"Multipart partial file is oversized; restarting from zero\")\n                partialFile.delete()\n              }\n\n              downloadedBytes = partialFile.length()\n              var partStart = 0L\n              for (partIndex in multipartUrls.indices) {\n                val partUrl = multipartUrls[partIndex]\n                val partSize = multipartPartSizes[partIndex]\n                val partEnd = partStart + partSize\n\n                if (partialFile.length() >= partEnd) {\n                  downloadedBytes = partialFile.length()\n                  Log.d(TAG, \"Multipart resume: part $partIndex already complete\")\n                  partStart = partEnd\n                  continue\n                }\n                if (partialFile.length() < partStart) {\n                  throw IOException(\n                    \"Multipart gap before part $partIndex: local=${partialFile.length()} expectedStart=$partStart\"\n                  )\n                }\n\n                var resumeInPart = (partialFile.length() - partStart).coerceAtLeast(0L)\n                while (true) {\n                  val connection =\n                    (URL(partUrl).openConnection() as HttpURLConnection).apply {\n                      connectTimeout = DOWNLOAD_CONNECT_TIMEOUT_MS\n                      readTimeout = DOWNLOAD_READ_TIMEOUT_MS\n                      setRequestProperty(\"Accept-Encoding\", \"identity\")\n                      instanceFollowRedirects = true\n                      if (resumeInPart > 0L) {\n                        setRequestProperty(\"Range\", \"bytes=${resumeInPart}-\")\n                      }\n                    }\n                  connection.connect()\n                  val responseCode = connection.responseCode\n                  Log.d(\n                    TAG,\n                    \"MCP238 multipart part=$partIndex response=$responseCode resume=$resumeInPart\",\n                  )\n\n                  // Some CDNs ignore Range and return the whole part. Rewind exactly to this part's\n                  // boundary before retrying so a resume can never duplicate bytes.\n                  if (resumeInPart > 0L && responseCode == HttpURLConnection.HTTP_OK) {\n                    connection.disconnect()\n                    java.io.RandomAccessFile(partialFile, \"rw\").use { raf ->\n                      raf.setLength(partStart)\n                    }\n                    downloadedBytes = partStart\n                    resumeInPart = 0L\n                    continue\n                  }\n\n                  if (\n                    responseCode != HttpURLConnection.HTTP_OK &&\n                      responseCode != HttpURLConnection.HTTP_PARTIAL\n                  ) {\n                    connection.disconnect()\n                    throw IOException(\"Multipart HTTP $responseCode for part $partIndex\")\n                  }\n\n                  var lastSetProgressTs = 0L\n                  var deltaBytes = 0L\n                  connection.inputStream.use { input ->\n                    FileOutputStream(partialFile, true).use { output ->\n                      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)\n                      var bytesRead: Int\n                      while (input.read(buffer).also { bytesRead = it } != -1) {\n                        output.write(buffer, 0, bytesRead)\n                        downloadedBytes += bytesRead\n                        deltaBytes += bytesRead\n\n                        val curTs = System.currentTimeMillis()\n                        if (curTs - lastSetProgressTs > 200) {\n                          var bytesPerMs = 0f\n                          if (lastSetProgressTs != 0L) {\n                            if (bytesReadSizeBuffer.size == 5) bytesReadSizeBuffer.removeAt(0)\n                            bytesReadSizeBuffer.add(deltaBytes)\n                            if (bytesReadLatencyBuffer.size == 5) bytesReadLatencyBuffer.removeAt(0)\n                            bytesReadLatencyBuffer.add(curTs - lastSetProgressTs)\n                            deltaBytes = 0L\n                            val latencySum = bytesReadLatencyBuffer.sum()\n                            if (latencySum > 0L) {\n                              bytesPerMs = bytesReadSizeBuffer.sum().toFloat() / latencySum\n                            }\n                          }\n                          val remainingMs =\n                            if (bytesPerMs > 0f) {\n                              ((multipartExpectedBytes - downloadedBytes).coerceAtLeast(0L) / bytesPerMs)\n                                .toLong()\n                            } else {\n                              0L\n                            }\n                          setProgress(\n                            Data.Builder()\n                              .putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes)\n                              .putLong(KEY_MODEL_DOWNLOAD_RATE, (bytesPerMs * 1000).toLong())\n                              .putLong(KEY_MODEL_DOWNLOAD_REMAINING_MS, remainingMs)\n                              .build()\n                          )\n                          setForeground(\n                            createForegroundInfo(\n                              progress =\n                                (downloadedBytes * 100 / multipartExpectedBytes).toInt().coerceIn(0, 100),\n                              modelName = modelName,\n                            )\n                          )\n                          lastSetProgressTs = curTs\n                        }\n                      }\n                    }\n                  }\n                  connection.disconnect()\n                  break\n                }\n\n                if (partialFile.length() != partEnd) {\n                  throw IOException(\n                    \"Multipart part $partIndex size mismatch: local=${partialFile.length()} expected=$partEnd\"\n                  )\n                }\n                downloadedBytes = partialFile.length()\n                partStart = partEnd\n              }\n\n              if (partialFile.length() != multipartExpectedBytes) {\n                throw IOException(\n                  \"Multipart final size mismatch: ${partialFile.length()} != $multipartExpectedBytes\"\n                )\n              }\n\n              if (multipartExpectedSha256.isNotBlank()) {\n                val digest = MessageDigest.getInstance(\"SHA-256\")\n                partialFile.inputStream().buffered(1024 * 1024).use { input ->\n                  val hashBuffer = ByteArray(1024 * 1024)\n                  var count: Int\n                  while (input.read(hashBuffer).also { count = it } != -1) {\n                    digest.update(hashBuffer, 0, count)\n                  }\n                }\n                val actualSha256 =\n                  digest.digest().joinToString(\"\") { byte ->\n                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')\n                  }\n                if (!actualSha256.equals(multipartExpectedSha256, ignoreCase = true)) {\n                  partialFile.delete()\n                  throw IOException(\n                    \"Multipart SHA-256 mismatch: $actualSha256 != $multipartExpectedSha256\"\n                  )\n                }\n                Log.d(TAG, \"MCP238 multipart SHA-256 verified: $actualSha256\")\n              }\n\n              if (completedFile.exists()) completedFile.delete()\n              if (!partialFile.renameTo(completedFile)) {\n                throw IOException(\"Failed to finalize multipart model file\")\n              }\n              downloadedBytes = completedFile.length()\n              setProgress(\n                Data.Builder().putLong(KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, downloadedBytes).build()\n              )\n              setForeground(createForegroundInfo(progress = 100, modelName = modelName))\n              Log.d(TAG, \"MCP238 multipart model download complete: ${completedFile.absolutePath}\")\n              continue\n            }\n\n            val candidateUrls =\n              file.url.split(\"\\n\").map { it.trim() }.filter { it.isNotEmpty() }.distinct()\n"""
replace_once(
    worker_path,
    multipart_anchor,
    multipart_block,
    "// MCP238 Qwen3.5 multipart reconstruction.",
)

print("MCP238 Qwen3.5 model/download patch completed successfully.")
