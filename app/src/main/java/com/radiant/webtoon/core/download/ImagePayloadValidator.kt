package com.radiant.webtoon.core.download

import java.io.File

object ImagePayloadValidator {
    fun isImage(file: File): Boolean {
        if (!file.exists() || file.length() < 12L) return false
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        return isImage(header.copyOf(read.coerceAtLeast(0)))
    }

    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        var i = 0
        while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
        if (i < bytes.size && (bytes[i] == '<'.code.toByte() || bytes[i] == '{'.code.toByte())) {
            return false
        }
        return isJpeg(bytes) || isPng(bytes) || isGif(bytes) || isWebp(bytes)
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() &&
            bytes[3] == 'G'.code.toByte()

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
}

object AtomicImageStore {
    fun writePage(dir: File, index: Int, bytes: ByteArray): File {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, DownloadPathUtils.pageFileName(index))
        val tmp = File(dir, DownloadPathUtils.pageFileName(index) + ".part")
        tmp.writeBytes(bytes)
        if (!ImagePayloadValidator.isImage(tmp)) {
            tmp.delete()
            throw IllegalStateException("invalid image payload")
        }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw IllegalStateException("failed to replace page file")
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("failed to commit page file")
        }
        return target
    }
}
