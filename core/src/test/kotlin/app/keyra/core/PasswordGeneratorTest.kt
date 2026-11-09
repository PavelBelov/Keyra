package app.keyra.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasswordGeneratorTest {
    @Test fun includesEverySelectedGroupAtExactLength() {
        repeat(100) {
            val options = PasswordOptions(length = 32)
            val password = PasswordGenerator.generate(options)
            assertEquals(32, password.length)
            options.groups().forEach { group -> assertTrue(password.any { it in group }) }
        }
    }

    @Test fun numericOnlyCanGeneratePinsWithoutOtherCharacters() {
        repeat(100) {
            val password = PasswordGenerator.generate(PasswordOptions(6, false, false, true, false))
            assertEquals(6, password.length)
            assertTrue(password.all { it in '0'..'9' })
        }
    }

    @Test fun honorsEveryCombinationOfGroups() {
        for (mask in 1..15) {
            val options = PasswordOptions(64, mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            val result = PasswordGenerator.generate(options)
            val groups = options.groups()
            assertTrue(result.all { it in groups.joinToString("") })
            groups.forEach { group -> assertTrue(result.any { it in group }) }
        }
    }

    @Test fun rejectsEmptyGroupsAndLengthsThatCannotMeetSelectedGroups() {
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordOptions(24, false, false, false, false)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordOptions(3)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordOptions(3, true, false, false, false)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordOptions(0)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordOptions(257)) }
    }

    @Test fun allowsBoundaryLengths() {
        assertEquals(20, PasswordGenerator.generate(PasswordOptions()).length)
        assertEquals(4, PasswordGenerator.generate(PasswordOptions(4, true, false, false, false)).length)
        assertEquals(256, PasswordGenerator.generate(PasswordOptions(256)).length)
    }
}
