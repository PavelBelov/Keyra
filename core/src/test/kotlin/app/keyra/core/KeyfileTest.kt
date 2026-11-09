package app.keyra.core

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.KdfParameters
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class KeyfileTest {
    @get:Rule val temp = TemporaryFolder()
    private val binary = ByteArray(32) { it.toByte() }
    // SHA-256 of bytes 00..1F starts with 630DCD29 (independent format fixture).
    private val xml = """<?xml version="1.0" encoding="utf-8"?>
        <KeyFile><Meta><Version>2.0</Version></Meta><Key><Data Hash="630DCD29">
            00010203 04050607 08090A0B 0C0D0E0F
            10111213 14151617 18191A1B 1C1D1E1F
        </Data></Key></KeyFile>""".trimIndent().toByteArray()

    @Test fun detectsBinaryAndXmlAndRejectsCorruption() {
        assertContentEquals(binary, Kdbx.credentials("", binary).key!!.getBinary())
        assertContentEquals(binary, Kdbx.credentials("", xml).key!!.getBinary())
        val corrupt = xml.toString(Charsets.UTF_8).replace("630DCD29", "00000000").toByteArray()
        assertFails { Kdbx.credentials("", corrupt) }
        assertFails { Kdbx.exportKeyfile(corrupt) }
    }

    @Test fun exportAlwaysUsesXmlV2AndPreservesInput() {
        val v1 = "<KeyFile><Meta><Version>1.0</Version></Meta><Key><Data>AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=</Data></Key></KeyFile>".toByteArray()
        for (source in listOf(binary, xml, v1)) {
            val before = source.copyOf()
            val exported = Kdbx.exportKeyfile(source)
            val text = exported.toString(Charsets.UTF_8)
            assertTrue(text.startsWith("<?xml"))
            assertTrue(text.contains("<Version>2.0</Version>"))
            assertTrue(text.contains("Hash=\"630DCD29\""))
            assertContentEquals(binary, Kdbx.credentials("", exported).key!!.getBinary())
            assertContentEquals(before, source)
        }
    }

    @Test fun exportedKeyOpensExistingDatabaseWithAndWithoutPassword() {
        for (password in listOf("", "password")) {
            val original = Kdbx.create(Kdbx.credentials(password, binary)) as KeePassDatabase.Ver4x
            val db = original.copy(header = original.header.copy(kdfParameters = KdfParameters.Aes(2UL, original.header.masterSeed)))
            val file = temp.newFile()
            Kdbx.write(db, file)
            for (source in listOf(binary, xml, Kdbx.exportKeyfile(binary), Kdbx.exportKeyfile(xml))) {
                assertEquals(db.content.group.uuid, Kdbx.read(file, Kdbx.credentials(password, source)).content.group.uuid)
            }
        }
    }
}
