package io.nekohasekai.sfa.selfuse

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class InitialRuleSetsTest {
    @get:Rule val folder = TemporaryFolder()

    private val data = "rule-set".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val hashes = mapOf("a.srs" to hash)

    private fun install(directory: File, bytes: ByteArray = data, opened: MutableList<String> = mutableListOf()) =
        InitialRuleSets.install(directory, hashes) { opened += it; bytes.inputStream() }

    @Test fun copiesMissingRuleSetAndLeavesNoTemporaryFile() {
        val directory = File(folder.root, "rules")
        assertEquals(directory.absolutePath, install(directory))
        assertArrayEquals(data, File(directory, "a.srs").readBytes())
        assertEquals(listOf("a.srs"), directory.list()!!.toList())
    }

    @Test fun keepsAnIdenticalCopyWithoutReadingTheAsset() {
        val directory = folder.newFolder("rules")
        File(directory, "a.srs").writeBytes(data)
        val opened = mutableListOf<String>()
        install(directory, opened = opened)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun replacesACorruptedCopy() {
        val directory = folder.newFolder("rules")
        File(directory, "a.srs").writeBytes("damaged".toByteArray())
        install(directory)
        assertArrayEquals(data, File(directory, "a.srs").readBytes())
    }

    @Test fun replacesADirectoryAtTheTargetPath() {
        val directory = folder.newFolder("rules")
        File(directory, "a.srs/stale").apply { parentFile!!.mkdirs(); writeText("x") }
        install(directory)
        assertArrayEquals(data, File(directory, "a.srs").readBytes())
    }

    @Test fun rejectsATamperedAssetWithoutWritingIt() {
        val directory = folder.newFolder("rules")
        assertThrows(IllegalArgumentException::class.java) { install(directory, "tampered".toByteArray()) }
        assertFalse(File(directory, "a.srs").exists())
        assertFalse(File(directory, "a.srs.tmp").exists())
    }

    @Test fun concurrentCallersEndWithOneValidCopy() {
        val directory = folder.newFolder("rules")
        val threads = List(8) { Thread { repeat(20) { install(directory) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertArrayEquals(data, File(directory, "a.srs").readBytes())
        assertEquals(listOf("a.srs"), directory.list()!!.toList())
    }
}
