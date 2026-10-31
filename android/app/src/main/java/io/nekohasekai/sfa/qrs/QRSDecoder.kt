package io.nekohasekai.sfa.qrs

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class QRSDecoder {
    private var codec: LubyCodec? = null
    private var state: LubyCodec.DecodingState? = null
    private var sliceSize = 0
    private val processedHashes = mutableSetOf<Int>()

    private val inflater = Inflater()
    private val decompressBuffer = ByteArray(8192)
    private val outputBuffer = ByteArrayOutputStream(32768)

    data class DecodeProgress(
        val decodedBlocks: Int,
        val totalBlocks: Int,
        val isComplete: Boolean,
        val data: ByteArray? = null,
        val error: String? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as DecodeProgress
            return decodedBlocks == other.decodedBlocks &&
                totalBlocks == other.totalBlocks &&
                isComplete == other.isComplete &&
                data.contentEquals(other.data) &&
                error == other.error
        }

        override fun hashCode(): Int {
            var result = decodedBlocks
            result = 31 * result + totalBlocks
            result = 31 * result + isComplete.hashCode()
            result = 31 * result + (data?.contentHashCode() ?: 0)
            result = 31 * result + (error?.hashCode() ?: 0)
            return result
        }
    }

    @Synchronized
    fun processFrame(base64Content: String): DecodeProgress? {
        val payload = try {
            Base64.decode(base64Content, Base64.NO_WRAP)
        } catch (e: Exception) {
            return null
        }
        return processFrame(payload)
    }

    @Synchronized
    fun processFrame(payload: ByteArray): DecodeProgress? {
        val hash = payload.contentHashCode()
        if (hash in processedHashes) {
            return state?.let {
                DecodeProgress(it.decodedCount, it.totalBlocks, it.decodedCount == it.totalBlocks)
            }
        }
        processedHashes.add(hash)

        val block = parseFramePayload(payload) ?: return null

        // Auto-detect dataset switch: if checksum changes, reset decoder
        if (state != null && state!!.checksum != block.checksum) {
            reset()
            processedHashes.add(hash)
        }

        // A frame that claims the current dataset but disagrees with its layout
        // is corrupt. Ignore it instead of indexing the state with foreign sizes.
        state?.let { current ->
            if (block.totalBlocks != current.totalBlocks ||
                block.compressedSize != current.compressedSize ||
                block.data.size != sliceSize
            ) {
                return DecodeProgress(current.decodedCount, current.totalBlocks, current.decodedCount == current.totalBlocks)
            }
        }

        if (codec == null) {
            sliceSize = block.data.size
            codec = LubyCodec(sliceSize = block.data.size)
            state = codec!!.createDecodingState(block)
        }

        val currentState = state!!
        val complete = codec!!.processBlock(currentState, block)

        return if (complete) {
            val assembledData = codec!!.assembleData(currentState)
            val compressedData = assembledData.copyOf(currentState.compressedSize)

            val decompressedData = try {
                decompress(compressedData)
            } catch (e: Exception) {
                null
            }

            if (decompressedData != null) {
                val checksumValid = codec!!.verifyChecksum(
                    decompressedData,
                    currentState.checksum,
                    currentState.totalBlocks,
                )
                if (checksumValid) {
                    return DecodeProgress(
                        currentState.decodedCount,
                        currentState.totalBlocks,
                        true,
                        decompressedData,
                    )
                }
            }

            val rawChecksumValid = codec!!.verifyChecksum(
                compressedData,
                currentState.checksum,
                currentState.totalBlocks,
            )
            if (rawChecksumValid) {
                DecodeProgress(currentState.decodedCount, currentState.totalBlocks, true, compressedData)
            } else {
                DecodeProgress(
                    currentState.decodedCount,
                    currentState.totalBlocks,
                    true,
                    error = "Checksum verification failed",
                )
            }
        } else {
            DecodeProgress(currentState.decodedCount, currentState.totalBlocks, false)
        }
    }

    @Synchronized
    fun reset() {
        codec = null
        state = null
        sliceSize = 0
        processedHashes.clear()
    }

    val progress: DecodeProgress?
        @Synchronized get() = state?.let {
            DecodeProgress(it.decodedCount, it.totalBlocks, it.decodedCount == it.totalBlocks)
        }

    private fun decompress(data: ByteArray): ByteArray {
        inflater.reset()
        inflater.setInput(data)
        outputBuffer.reset()

        while (!inflater.finished()) {
            val count = inflater.inflate(decompressBuffer)
            // A dictionary-based or truncated stream can produce no output
            // without finishing. Never keep the camera analysis thread spinning.
            if (count == 0 && !inflater.finished()) {
                throw DataFormatException("Incomplete or unsupported QRS compressed stream")
            }
            if (outputBuffer.size().toLong() + count > QRSConstants.MAX_DECOMPRESSED_SIZE) {
                throw DataFormatException("QRS compressed stream expands beyond the import limit")
            }
            outputBuffer.write(decompressBuffer, 0, count)
        }

        return outputBuffer.toByteArray()
    }

    internal companion object {
        private const val FIXED_HEADER_SIZE = 16

        /**
         * Parses one fountain-code frame, returning null for anything that is
         * not internally consistent. Rejected frames are skipped by the caller,
         * so a damaged or hostile QR code can never size an allocation, index
         * outside the decoding state, or make the scanner throw.
         */
        fun parseFramePayload(payload: ByteArray): LubyCodec.EncodedBlock? {
            if (payload.size < FIXED_HEADER_SIZE + 1) return null

            val degree = payload.readIntLE(0)
            if (degree !in 1..QRSConstants.MAX_DECODE_DEGREE) return null

            val headerSize = FIXED_HEADER_SIZE + 4L * degree
            // At least one data byte must follow the header.
            if (payload.size <= headerSize) return null

            var offset = 4
            val indices = IntArray(degree) {
                val idx = payload.readIntLE(offset)
                offset += 4
                idx
            }

            val totalBlocks = payload.readIntLE(offset)
            offset += 4
            val compressedSize = payload.readIntLE(offset)
            offset += 4
            val checksum = payload.readIntLE(offset).toLong() and 0xFFFFFFFFL
            offset += 4

            if (totalBlocks !in 1..QRSConstants.MAX_DECODE_BLOCKS || degree > totalBlocks) return null
            if (indices.any { it !in 0 until totalBlocks }) return null
            if (indices.toSet().size != degree) return null

            val data = payload.copyOfRange(offset, payload.size)
            val slice = data.size.toLong()
            val assembledSize = slice * totalBlocks
            if (assembledSize > QRSConstants.MAX_ASSEMBLED_SIZE) return null
            // The encoder uses k = ceil(compressedSize / slice), so the size must
            // fall inside the final slice.
            if (compressedSize <= slice * (totalBlocks - 1) || compressedSize > assembledSize) return null

            return LubyCodec.EncodedBlock(degree, indices, totalBlocks, compressedSize, checksum, data)
        }
    }
}
