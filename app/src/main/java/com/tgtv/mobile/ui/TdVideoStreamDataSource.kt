package com.tgtv.mobile.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.tgtv.mobile.td.TdError
import com.tgtv.mobile.td.TdLib
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Streams a Telegram file into Media3 by reading TDLib's partial local file
 * directly.
 *
 * Why not TdReadFilePart? TDLib's FileManager refuses ReadFilePart requests
 * whose range is beyond the contiguous downloaded prefix
 * ("There is not enough downloaded bytes in the file to read"), so any
 * read past the current prefix - MP4 tail atoms, MKV Cues at EOF - fails.
 *
 * Instead a synchronised range [TdLib.downloadRange] is requested (which
 * physically writes the bytes into TDLib's partial file at their offsets),
 * and the bytes are then served straight from that file via
 * [RandomAccessFile]. Seeks anywhere in the file work the same way.
 */
class TdVideoStreamDataSource(
    private val fileId: Int,
    initialSize: Long,
) : BaseDataSource(false) {

    companion object {
        // TDLib range downloads and offsets must be 1 KiB aligned
        private const val ALIGN_1K = 1024L

        // Bytes downloaded per synchronised window
        private const val WINDOW = 2L * 1024L * 1024L

        // Bytes warmed a head after each window (single fire-and-forget request)
        private const val AHEAD = 4L * ALIGN_1K * 1024L

        private const val DOWNLOAD_PRIORITY = 32

        private const val PATH_TIMEOUT_MS = 120_000L
        private const val POLL_MS = 200L
    }

    private var uri: Uri? = null
    private var readPosition: Long = 0
    private var remaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    private var fileSize: Long = initialSize
    private var handle: RandomAccessFile? = null
    private var handlePath: String? = null

    private var scratch: ByteArray = ByteArray(0)
    private var scratchOffset = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        readPosition = dataSpec.position
        remaining = if (fileSize > 0) fileSize - readPosition else C.LENGTH_UNSET.toLong()
        if (remaining < 0) throw IOException("Read position past end of file")

        scratch = ByteArray(0)
        scratchOffset = 0
        opened = true
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (opened && remaining == 0L) return C.RESULT_END_OF_INPUT

        if (scratchOffset >= scratch.size) {
            fillScratch()
            if (scratchOffset >= scratch.size) return C.RESULT_END_OF_INPUT
        }

        val toCopy = minOf(length, scratch.size - scratchOffset)
        System.arraycopy(scratch, scratchOffset, buffer, offset, toCopy)
        scratchOffset += toCopy
        readPosition += toCopy
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
        scratch = ByteArray(0)
        scratchOffset = 0
        handle?.close()
        handle = null
        handlePath = null
    }

    override fun toString(): String = "TdStream(fileId=$fileId)"

    /**
     * Synchronously downloads the range, retrying transient failures. Hard
     * refusals (Telegram's 2 GB non-premium download limit, invalid file)
     * are rethrown immediately - retrying them would just buffer forever.
     */
    private fun fetchRange(alignedPos: Long, limit: Long): TdApi.File {
        var lastError: TdError? = null
        for (attempt in 0 until 3) {
            try {
                return runBlocking {
                    TdLib.downloadRange(fileId, alignedPos, limit, DOWNLOAD_PRIORITY)
                }
            } catch (e: TdError) {
                lastError = e
                val msg = e.message
                if (msg != null && (msg.contains("premium", ignoreCase = true) ||
                        msg.contains("too big", ignoreCase = true) ||
                        msg.contains("FILE_TOO_BIG", ignoreCase = true) ||
                        msg.contains("FILE_ID_INVALID", ignoreCase = true))
                ) {
                    throw e
                }
                try {
                    Thread.sleep(150L * (attempt + 1))
                } catch (_: InterruptedException) {
                    throw IOException("Playback loader interrupted")
                }
            }
        }
        throw lastError ?: IOException("TDLib range download failed at $alignedPos")
    }

    @Throws(IOException::class)
    private fun fillScratch() {
        val alignedPos = readPosition - readPosition % ALIGN_1K
        val want: Long = if (remaining in 1..WINDOW) remaining else WINDOW
        val span = want + (readPosition - alignedPos)
        var limit = (span + ALIGN_1K - 1) / ALIGN_1K * ALIGN_1K
        if (fileSize > 0) {
            val clipped = fileSize - alignedPos
            if (clipped <= 0) {
                readPosition = fileSize
                remaining = 0
                return
            }
            limit = minOf(limit, clipped)
        }

        // Block until the range exists physically in TDLib's partial file
        val fi: TdApi.File = try {
            fetchRange(alignedPos, limit)
        } catch (e: TdError) {
            throw IOException("TDLib could not download $limit bytes at $alignedPos: ${e.message}", e)
        }
        rememberFi(fi)
        if (fi.size > 0 && fi.size != fileSize) {
            fileSize = fi.size
            if (remaining != C.LENGTH_UNSET.toLong()) {
                remaining = fileSize - readPosition
                if (remaining < 0) {
                    readPosition = fileSize
                    remaining = 0
                    return
                }
            }
        }

        val path = resolvePartialPath()
        val target = if (fileSize > 0) minOf(limit, fileSize - alignedPos) else limit
        val f = openHandle(path)
        try {
            f.seek(alignedPos)
            val buf = ByteArray(target.toInt())
            var filled = 0
            while (filled < buf.size) {
                val got = f.read(buf, filled, buf.size - filled)
                if (got < 0) break
                filled += got
            }
            val skip = (readPosition - alignedPos).toInt()
            if (filled <= skip) {
                readPosition = if (fileSize > 0) fileSize else readPosition
                remaining = 0
                return
            }
            scratch = buf
            scratchOffset = skip
            if (filled < buf.size) scratch = buf.copyOf(filled)
            // A short read with unknown total size means we reached the end
            if (fileSize <= 0 && filled < span) {
                fileSize = alignedPos + filled
                remaining = (filled - skip).toLong()
            }
        } catch (e: IOException) {
            // The partial file may have been moved/rewritten by TDLib - reopen once
            reopenHandle(path)
            try {
                fetchRange(alignedPos, limit)
            } catch (e: TdError) {
                throw IOException("TDLib could not re-download $limit bytes at $alignedPos: ${e.message}", e)
            }
            val fresh = File(path)
            if (!fresh.exists() || fresh.length() < alignedPos) {
                throw IOException("TDLib local file unreadable at $alignedPos", e)
            }
            val f2 = openHandle(path)
            f2.seek(alignedPos)
            val buf = ByteArray(target.toInt())
            var filled = 0
            while (filled < buf.size) {
                val got = f2.read(buf, filled, buf.size - filled)
                if (got < 0) break
                filled += got
            }
            val skip = (readPosition - alignedPos).toInt()
            if (filled <= skip) {
                readPosition = if (fileSize > 0) fileSize else readPosition
                remaining = 0
                return
            }
            scratch = buf
            scratchOffset = skip
            if (filled < buf.size) scratch = buf.copyOf(filled)
        }

        // Warm the next window in the background; next sync range cancels it safely
        if (fileSize > 0) {
            val nextPos = alignedPos + limit
            if (nextPos < fileSize) {
                TdLib.fire(
                    TdApi.DownloadFile(
                        fileId, DOWNLOAD_PRIORITY, nextPos,
                        minOf(AHEAD, fileSize - nextPos), false,
                    ),
                )
            }
        }
    }

    private fun rememberFi(fi: TdApi.File) {
        val local = fi.local ?: return
        val p = local.path
        if (p.isNotBlank()) handlePath = p
    }

    /** Resolves TDLib's partial local file path, waiting for the file to appear. */
    private fun resolvePartialPath(): String {
        handlePath?.let { p ->
            if (File(p).exists()) return p
        }
        var waited = 0L
        while (true) {
            val fi: TdApi.File = try {
                runBlocking { TdLib.send(TdApi.GetFile(fileId)) }
            } catch (e: TdError) {
                throw IOException("TDLib file unavailable: ${e.message}", e)
            }
            rememberFi(fi)
            val p = fi.local?.path?.takeIf { it.isNotBlank() && File(it).exists() }
            if (p != null) return p
            if (waited >= PATH_TIMEOUT_MS) {
                throw IOException("TDLib never produced a local file for playback")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                throw IOException("Playback loader interrupted")
            }
            waited += POLL_MS
        }
    }

    private fun openHandle(path: String): RandomAccessFile {
        val existing = handle
        if (existing != null && handlePath == path) return existing
        reopenHandle(path)
        return handle ?: throw IOException("TDLib local file could not be opened: $path")
    }

    private fun reopenHandle(path: String) {
        try {
            handle?.close()
        } catch (_: IOException) {
        }
        handlePath = path
        handle = RandomAccessFile(path, "r")
    }
}
