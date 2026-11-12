package io.nekohasekai.sfa.selfuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PairedProfileStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun writesTheConfigurationUnderTheRequestedId() {
        val directory = File(folder.root, "configs")
        val file = PairedProfileStore.writeConfig(directory, 3, "{}")
        assertEquals(File(directory, "3.json"), file)
        assertEquals("{}", file.readText())
        assertEquals(listOf("3.json"), directory.list()!!.toList())
    }

    @Test fun neverOverwritesAnExistingConfiguration() {
        val directory = folder.newFolder("configs")
        File(directory, "3.json").writeText("other")
        File(directory, "4.json").writeText("another")
        val file = PairedProfileStore.writeConfig(directory, 3, "{}")
        assertEquals(File(directory, "5.json"), file)
        assertEquals("other", File(directory, "3.json").readText())
        assertEquals("another", File(directory, "4.json").readText())
        assertEquals("{}", file.readText())
    }

    @Test fun replacesAStaleTemporaryFile() {
        val directory = folder.newFolder("configs")
        File(directory, "1.json.tmp").writeText("partial")
        val file = PairedProfileStore.writeConfig(directory, 1, "{}")
        assertEquals("{}", file.readText())
        assertEquals(listOf("1.json"), directory.list()!!.toList())
    }

    @Test fun missingInboxIsNotAnImport() {
        assertNull(PrivateBootstrap.take(File(folder.root, "vless.txt")))
    }

    @Test fun takeReadsAndRemovesTheLink() {
        val input = folder.newFile("vless.txt").apply { writeText("vless://link") }
        assertEquals("vless://link", PrivateBootstrap.take(input))
        assertFalse(input.exists())
    }

    @Test fun rejectedFilesAreStillRemoved() {
        val empty = folder.newFile("empty.txt")
        assertThrows(IllegalArgumentException::class.java) { PrivateBootstrap.take(empty) }
        assertFalse(empty.exists())
        val large = folder.newFile("large.txt").apply { writeText("v".repeat(16385)) }
        assertThrows(IllegalArgumentException::class.java) { PrivateBootstrap.take(large) }
        assertFalse(large.exists())
    }
}
