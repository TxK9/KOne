package io.nekohasekai.sfa.qrs

/**
 * The file envelope used by the official QRS web sender and receiver:
 *
 * `[meta length: u32 BE][meta: UTF-8 JSON][data length: u32 BE][data]`
 *
 * The receiver decodes the metadata as UTF-8, so profile names outside Latin-1
 * (for example Chinese names) must be written as UTF-8 to survive the transfer.
 */
object QRSFileFormat {
    private const val LENGTH_SIZE = 4

    fun wrap(data: ByteArray, filename: String? = null, contentType: String? = null): ByteArray {
        val fields = listOfNotNull(
            filename?.let { "\"filename\":\"${escapeJson(it)}\"" },
            contentType?.let { "\"contentType\":\"${escapeJson(it)}\"" },
        )
        val metaBytes = fields.joinToString(",", "{", "}").toByteArray(Charsets.UTF_8)

        val result = ByteArray(LENGTH_SIZE + metaBytes.size + LENGTH_SIZE + data.size)
        var offset = 0
        result.writeIntBE(offset, metaBytes.size)
        offset += LENGTH_SIZE
        metaBytes.copyInto(result, offset)
        offset += metaBytes.size
        result.writeIntBE(offset, data.size)
        offset += LENGTH_SIZE
        data.copyInto(result, offset)
        return result
    }

    /**
     * Returns the payload of a well-formed envelope, or null when [data] is not one.
     * Both lengths come from untrusted camera input, so they must exactly account
     * for every byte; anything else is treated as raw, unwrapped content.
     */
    fun unwrap(data: ByteArray): ByteArray? {
        if (data.size < LENGTH_SIZE * 2) return null
        val metaLength = data.readUIntBE(0)
        val dataOffset = LENGTH_SIZE + metaLength + LENGTH_SIZE
        if (dataOffset > data.size) return null
        val dataLength = data.readUIntBE((dataOffset - LENGTH_SIZE).toInt())
        if (dataOffset + dataLength != data.size.toLong()) return null
        return data.copyOfRange(dataOffset.toInt(), data.size)
    }

    private fun ByteArray.readUIntBE(offset: Int): Long = ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

    internal fun escapeJson(value: String): String = buildString(value.length) {
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                // JSON forbids raw control characters; U+2028/U+2029 break JavaScript parsers.
                in '\u0000'..'\u001F', '\u2028', '\u2029' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
    }
}
