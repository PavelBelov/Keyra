package app.keyra.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TotpTest {
    // RFC 6238, Appendix B: independent test vectors for all three algorithms.
    @Test fun rfc6238Vectors() {
        val times = listOf(59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L)
        val cases = listOf(
            Triple("SHA1", "12345678901234567890", listOf("94287082", "07081804", "14050471", "89005924", "69279037", "65353130")),
            Triple("SHA256", "12345678901234567890123456789012", listOf("46119246", "68084774", "67062674", "91819424", "90698825", "77737706")),
            Triple("SHA512", "1234567890123456789012345678901234567890123456789012345678901234", listOf("90693936", "25091201", "99943326", "93441116", "38618901", "47863826"))
        )
        for ((algorithm, secret, expected) in cases) {
            val totp = assertNotNull(Totp.parse("otpauth://totp/Test:alice?secret=${base32(secret)}&digits=8&algorithm=$algorithm"))
            times.zip(expected).forEach { (time, code) -> assertEquals(code, totp.codeAt(time * 1000).value) }
        }
    }

    @Test fun defaultsAndExactPeriodBoundary() {
        val totp = assertNotNull(Totp.parse("otpauth://totp/Test?secret=${base32("12345678901234567890")}"))
        assertEquals(6, totp.digits)
        assertEquals(30, totp.period)
        assertEquals("287082", totp.codeAt(59_999).value)
        assertEquals(1L, totp.codeAt(59_999).remainingMillis)
        assertEquals(1, totp.codeAt(59_999).remainingSeconds)
        assertEquals("359152", totp.codeAt(60_000).value)
        assertEquals(30_000L, totp.codeAt(60_000).remainingMillis)
        assertEquals(30, totp.codeAt(60_000).remainingSeconds)
    }

    @Test fun customPeriodAndTenDigits() {
        val totp = assertNotNull(Totp.parse("otpauth://totp/Test?secret=${base32("12345678901234567890")}&digits=10&period=60"))
        assertEquals("1284755224", totp.codeAt(59_999).value)
        assertEquals(1L, totp.codeAt(59_999).remainingMillis)
        assertEquals("1094287082", totp.codeAt(60_000).value)
        assertEquals(60_000L, totp.codeAt(60_000).periodMillis)
    }

    @Test fun steamEncoderMatchesKeePassXCAlphabetAndOrder() {
        val totp = assertNotNull(Totp.parse("otpauth://totp/Steam?secret=${base32("12345678901234567890")}&encoder=steam"))
        // HOTP truncated value at counter 1 is 1094287082, encoded least-significant character first.
        assertEquals("PV9M4", totp.codeAt(59_000).value)
    }

    @Test fun percentEncodedPaddedLowercaseSecretAndLabels() {
        val totp = assertNotNull(Totp.parse("otpauth://totp/Example%20Service:alice%40example.org?secret=my%3D%3D%3D%3D%3D%3D&issuer=Example%20Service"))
        val unpadded = assertNotNull(Totp.parse("otpauth://totp/Test?secret=MY"))
        assertEquals(unpadded.codeAt(0).value, totp.codeAt(0).value)
    }

    @Test fun rejectsMalformedUnsupportedAndLegacyValues() {
        for (value in listOf(
            "", "key=MY&size=6&step=30", "30;6", "otpauth://hotp/Test?secret=MY",
            "https://totp/Test?secret=MY", "otpauth://totp/Test", "otpauth://totp/Test?secret=",
            "otpauth://totp/Test?secret=***", "otpauth://totp/Test?secret=M",
            "otpauth://totp/Test?secret=MY&algorithm=MD5", "otpauth://totp/Test?secret=MY&encoder=unknown",
            "otpauth://totp/Test?secret=MY&digits=0", "otpauth://totp/Test?secret=MY&digits=11",
            "otpauth://totp/Test?secret=MY&period=0", "otpauth://totp/Test?secret=MY&period=86401",
            "otpauth://totp/Test?secret=MY&period=abc", "otpauth://totp/Test?secret=MY&secret=MZXQ"
        )) assertNull(Totp.parse(value), value)
    }

    private fun base32(text: String): String = buildString {
        var buffer = 0
        var bits = 0
        for (byte in text.toByteArray(Charsets.US_ASCII)) {
            buffer = (buffer shl 8) or (byte.toInt() and 255)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"[(buffer shl (5 - bits)) and 31])
    }
}
