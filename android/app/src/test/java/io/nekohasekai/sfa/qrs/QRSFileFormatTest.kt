package io.nekohasekai.sfa.qrs

import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QRSFileFormatTest {
    private fun meta(wrapped: ByteArray): JSONObject {
        val length = ByteBuffer.wrap(wrapped, 0, 4).int
        return JSONObject(String(wrapped, 4, length, Charsets.UTF_8))
    }

    private fun envelope(metaLength: Int, meta: ByteArray, dataLength: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + meta.size + data.size).putInt(metaLength).put(meta).putInt(dataLength).put(data).array()

    @Test fun roundTripsThePayload() {
        val payload = ByteArray(300) { it.toByte() }
        val wrapped = QRSFileFormat.wrap(payload, "Home.bpf", "application/octet-stream")
        assertArrayEquals(payload, QRSFileFormat.unwrap(wrapped))
        assertArrayEquals(ByteArray(0), QRSFileFormat.unwrap(QRSFileFormat.wrap(ByteArray(0))))
    }

    @Test fun writesNonLatinNamesAsUtf8() {
        val wrapped = QRSFileFormat.wrap(byteArrayOf(1), "家庭 🏠.bpf", "application/octet-stream")
        val meta = meta(wrapped)
        assertEquals("家庭 🏠.bpf", meta.getString("filename"))
        assertEquals("application/octet-stream", meta.getString("contentType"))
    }

    @Test fun escapesEveryCharacterJsonForbids() {
        val name = "a\"b\\c\nd\re\tf\bg\u000ch\u0001i\u001fj\u2028k\u2029l"
        val wrapped = QRSFileFormat.wrap(byteArrayOf(), name)
        assertEquals(name, meta(wrapped).getString("filename"))
        val raw = String(wrapped, 4, wrapped.size - 8, Charsets.UTF_8)
        assertTrue(raw, raw.none { it < ' ' || it == '\u2028' || it == '\u2029' })
    }

    @Test fun omitsAbsentFields() {
        assertEquals(0, meta(QRSFileFormat.wrap(byteArrayOf())).length())
        assertEquals(setOf("contentType"), meta(QRSFileFormat.wrap(byteArrayOf(), contentType = "x")).keySet())
    }

    @Test fun matchesTheLegacyEncoderEntryPoint() {
        val payload = byteArrayOf(9, 8, 7)
        assertArrayEquals(
            QRSFileFormat.wrap(payload, "p.bpf", "application/octet-stream"),
            QRSEncoder.appendFileHeaderMeta(payload, "p.bpf", "application/octet-stream"),
        )
    }

    @Test fun rawContentIsNotMistakenForAnEnvelope() {
        val meta = "{}".toByteArray()
        val data = byteArrayOf(1, 2, 3)
        for (bytes in listOf(
            byteArrayOf(),
            byteArrayOf(0, 0, 0, 0, 0, 0, 0),
            envelope(meta.size, meta, data.size + 1, data), // data length runs past the end
            envelope(meta.size, meta, data.size - 1, data), // trailing bytes
            envelope(meta.size + 10, meta, data.size, data), // meta length runs past the end
            envelope(-1, meta, data.size, data), // 0xFFFFFFFF must not wrap around
            envelope(meta.size, meta, -1, data),
            envelope(Int.MAX_VALUE, meta, data.size, data),
        )) {
            assertNull(bytes.contentToString(), QRSFileFormat.unwrap(bytes))
        }
    }

    @Test fun acceptsAnEnvelopeFromTheWebSender() {
        // The official web sender writes UTF-8 JSON with big-endian lengths.
        val meta = "{\"filename\":\"配置.bpf\"}".toByteArray(Charsets.UTF_8)
        val data = byteArrayOf(4, 5, 6)
        assertArrayEquals(data, QRSFileFormat.unwrap(envelope(meta.size, meta, data.size, data)))
    }
}
