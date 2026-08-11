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

package com.google.ai.edge.gallery.customtasks.musicgeneration

import java.text.Normalizer

private const val SCORE_FLOOR = -1.0e30f
private const val UNKNOWN_BYTE_PIECE_TYPE = 6
private const val UNKNOWN_PIECE_TYPE = 2
private const val NORMAL_PIECE_TYPE = 1
private const val USER_DEFINED_PIECE_TYPE = 4
private const val SPACE_MARK = '\u2581'

private data class SentencePieceEntry(val piece: String, val score: Float, val type: Int)

private class ProtoReader(private val bytes: ByteArray) {
  var position: Int = 0
    private set

  val isAtEnd: Boolean
    get() = position >= bytes.size

  fun readVarint(): Long {
    var result = 0L
    var shift = 0
    while (position < bytes.size && shift < 64) {
      val b = bytes[position++].toInt() and 0xff
      result = result or ((b and 0x7f).toLong() shl shift)
      if ((b and 0x80) == 0) {
        return result
      }
      shift += 7
    }
    return result
  }

  fun readFixed32Float(): Float {
    if (position + 4 > bytes.size) {
      position = bytes.size
      return 0f
    }
    val bits =
      (bytes[position].toInt() and 0xff) or
        ((bytes[position + 1].toInt() and 0xff) shl 8) or
        ((bytes[position + 2].toInt() and 0xff) shl 16) or
        ((bytes[position + 3].toInt() and 0xff) shl 24)
    position += 4
    return Float.fromBits(bits)
  }

  fun readString(length: Int): String {
    val safeLength = length.coerceAtMost(bytes.size - position)
    val value = String(bytes, position, safeLength, Charsets.UTF_8)
    position += safeLength
    return value
  }

  fun skip(wireType: Int) {
    when (wireType) {
      0 -> readVarint()
      1 -> position = (position + 8).coerceAtMost(bytes.size)
      2 -> position = (position + readVarint().toInt()).coerceAtMost(bytes.size)
      5 -> position = (position + 4).coerceAtMost(bytes.size)
      else -> position = bytes.size
    }
  }

  fun skipTo(target: Int) {
    position = target.coerceIn(0, bytes.size)
  }
}

private fun parseSentencePieceModel(bytes: ByteArray): List<SentencePieceEntry> {
  val reader = ProtoReader(bytes)
  val pieces = mutableListOf<SentencePieceEntry>()
  while (!reader.isAtEnd) {
    val tag = reader.readVarint()
    val field = (tag ushr 3).toInt()
    val wire = (tag and 7L).toInt()
    if (field != 1 || wire != 2) {
      reader.skip(wire)
      continue
    }
    val end = (reader.position + reader.readVarint().toInt()).coerceAtMost(bytes.size)
    var piece = ""
    var score = 0f
    var type = NORMAL_PIECE_TYPE
    while (reader.position < end) {
      val pieceTag = reader.readVarint()
      when ((pieceTag ushr 3).toInt()) {
        1 -> {
          val length = reader.readVarint().toInt()
          piece = reader.readString(length)
        }
        2 -> score = reader.readFixed32Float()
        3 -> type = reader.readVarint().toInt()
        else -> reader.skip((pieceTag and 7L).toInt())
      }
    }
    reader.skipTo(end)
    pieces += SentencePieceEntry(piece = piece, score = score, type = type)
  }
  return pieces
}

class SoundGenTokenizer private constructor(
  private val pieces: Map<String, Int>,
  private val scores: FloatArray,
  private val maxPieceLength: Int,
  private val unknownScore: Float,
) {
  fun encode(prompt: String): IntArray {
    val normalized =
      SPACE_MARK +
        Regex("\\s+")
          .replace(Normalizer.normalize(prompt.trim(), Normalizer.Form.NFKC), " ")
          .replace(' ', SPACE_MARK)
    if (normalized.isEmpty()) {
      return intArrayOf(1)
    }
    val bestScore = FloatArray(normalized.length + 1) { SCORE_FLOOR }
    val prev = IntArray(normalized.length + 1) { -1 }
    val ids = IntArray(normalized.length + 1) { UNKNOWN_PIECE_TYPE }
    bestScore[0] = 0f
    for (start in normalized.indices) {
      if (bestScore[start] == SCORE_FLOOR) {
        continue
      }
      val limit = minOf(maxPieceLength, normalized.length - start)
      for (size in 1..limit) {
        val end = start + size
        val id = pieces[normalized.substring(start, end)] ?: continue
        val score = bestScore[start] + scores[id]
        if (score > bestScore[end]) {
          bestScore[end] = score
          prev[end] = start
          ids[end] = id
        }
      }
      val unknownEnd = start + 1
      val unknownPieceScore = bestScore[start] + unknownScore
      if (unknownPieceScore > bestScore[unknownEnd]) {
        bestScore[unknownEnd] = unknownPieceScore
        prev[unknownEnd] = start
        ids[unknownEnd] = UNKNOWN_PIECE_TYPE
      }
    }
    val output = mutableListOf<Int>()
    var cursor = normalized.length
    while (cursor > 0) {
      output += ids[cursor]
      cursor = prev[cursor].takeIf { it >= 0 } ?: 0
    }
    output.reverse()
    if (output.isEmpty() || output.last() != 1) {
      output += 1
    }
    return output.toIntArray()
  }

  companion object {
    fun fromBytes(bytes: ByteArray): SoundGenTokenizer {
      val entries = parseSentencePieceModel(bytes)
      val scores = FloatArray(entries.size)
      val pieces = mutableMapOf<String, Int>()
      var maxPieceLength = 1
      var lowestScore = 0f
      entries.forEachIndexed { index, entry ->
        scores[index] = entry.score
        if (
          (entry.type == NORMAL_PIECE_TYPE || entry.type == USER_DEFINED_PIECE_TYPE) &&
            entry.piece.isNotEmpty()
        ) {
          pieces[entry.piece] = index
          maxPieceLength = maxOf(maxPieceLength, entry.piece.length)
          lowestScore = minOf(lowestScore, entry.score)
        }
      }
      return SoundGenTokenizer(
        pieces = pieces,
        scores = scores,
        maxPieceLength = maxPieceLength,
        unknownScore = lowestScore - 10f,
      )
    }
  }
}

class SoundGenHdTokenizer private constructor(
  private val pieces: Map<String, Int>,
  private val scores: FloatArray,
  private val bytePieces: IntArray,
  private val unknownPieceId: Int,
) {
  fun encode(prompt: String): IntArray {
    val parts = prompt.replace(' ', SPACE_MARK).codePoints().toArray().map { String(Character.toChars(it)) }.toMutableList()
    while (parts.size > 1) {
      var bestScore = Float.NEGATIVE_INFINITY
      var bestIndex = -1
      for (index in 0 until parts.lastIndex) {
        val score = scores.getOrNull(pieces[parts[index] + parts[index + 1]] ?: -1) ?: continue
        if (score > bestScore) {
          bestScore = score
          bestIndex = index
        }
      }
      if (bestIndex < 0) {
        break
      }
      parts[bestIndex] = parts[bestIndex] + parts[bestIndex + 1]
      parts.removeAt(bestIndex + 1)
    }
    val ids = mutableListOf<Int>()
    for (part in parts) {
      val id = pieces[part]
      if (id != null) {
        ids += id
      } else {
        part.toByteArray(Charsets.UTF_8).forEach { byte ->
          val byteId = bytePieces[byte.toInt() and 0xff]
          ids += if (byteId >= 0) byteId else unknownPieceId
        }
      }
    }
    return ids.toIntArray()
  }

  companion object {
    fun fromBytes(bytes: ByteArray): SoundGenHdTokenizer {
      val entries = parseSentencePieceModel(bytes)
      val pieces = mutableMapOf<String, Int>()
      val scores = FloatArray(entries.size)
      val bytePieces = IntArray(256) { -1 }
      var unknownPieceId = 0
      entries.forEachIndexed { index, entry ->
        scores[index] = entry.score
        when (entry.type) {
          UNKNOWN_PIECE_TYPE -> unknownPieceId = index
          UNKNOWN_BYTE_PIECE_TYPE -> parseHexBytePiece(entry.piece)?.let { bytePieces[it] = index }
          else -> if (entry.piece.isNotEmpty()) pieces[entry.piece] = index
        }
      }
      return SoundGenHdTokenizer(
        pieces = pieces,
        scores = scores,
        bytePieces = bytePieces,
        unknownPieceId = unknownPieceId,
      )
    }

    private fun parseHexBytePiece(piece: String): Int? {
      if (!piece.startsWith("<0x") || !piece.endsWith(">") || piece.length != 6) {
        return null
      }
      return piece.substring(3, 5).toIntOrNull(16)?.takeIf { it in 0..255 }
    }
  }
}
