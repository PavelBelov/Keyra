package app.keyra.core

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The modern KeePassXC `otp` attribute: an otpauth://totp URI. */
class Totp private constructor(
    private val secret: ByteArray,
    private val algorithm: String,
    val digits: Int,
    val period: Int,
    private val steam: Boolean
) {
    data class Code(val value: String, val remainingMillis: Long, val periodMillis: Long) {
        val remainingSeconds: Int get() = ((remainingMillis + 999) / 1000).toInt()
    }

    fun codeAt(timeMillis: Long): Code {
        require(timeMillis >= 0)
        val periodMillis = period * 1000L
        val counter = timeMillis / periodMillis
        val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(secret, algorithm)) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 15
        var number = ((hash[offset].toLong() and 127) shl 24) or
            ((hash[offset + 1].toLong() and 255) shl 16) or
            ((hash[offset + 2].toLong() and 255) shl 8) or (hash[offset + 3].toLong() and 255)
        val value = if (steam) {
            buildString {
                repeat(5) {
                    append(STEAM_ALPHABET[(number % STEAM_ALPHABET.length).toInt()])
                    number /= STEAM_ALPHABET.length
                }
            }
        } else {
            var modulus = 1L
            repeat(digits) { modulus *= 10 }
            (number % modulus).toString().padStart(digits, '0')
        }
        return Code(value, periodMillis - timeMillis % periodMillis, periodMillis)
    }

    companion object {
        const val FIELD = "otp"
        private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

        /** Invalid and legacy values are deliberately not interpreted as default settings. */
        fun parse(value: String): Totp? = runCatching {
            val uri = URI(value.trim())
            require(uri.scheme == "otpauth" && uri.host == "totp")
            val parameters = linkedMapOf<String, String>()
            uri.rawQuery.orEmpty().split('&').forEach { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size == 2) {
                    fun decode(text: String) = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
                    val name = decode(pair[0])
                    require(name !in parameters)
                    parameters[name] = decode(pair[1])
                }
            }
            val secret = decodeBase32(requireNotNull(parameters["secret"]))
            val algorithm = when (parameters["algorithm"]?.uppercase(Locale.ROOT) ?: "SHA1") {
                "SHA1" -> "HmacSHA1"
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> error("Unsupported TOTP algorithm")
            }
            val digits = parameters["digits"]?.toInt() ?: 6
            val period = parameters["period"]?.toInt() ?: 30
            require(digits in 1..10 && period in 1..86400)
            val encoder = parameters["encoder"].orEmpty()
            require(encoder.isEmpty() || encoder == "steam")
            Totp(secret, algorithm, if (encoder == "steam") 5 else digits, period, encoder == "steam")
        }.getOrNull()

        private fun decodeBase32(raw: String): ByteArray {
            val text = raw.uppercase(Locale.ROOT).filterNot { it.isWhitespace() }.trimEnd('=')
            require(text.isNotEmpty() && text.length % 8 in setOf(0, 2, 4, 5, 7))
            val output = ByteArray(text.length * 5 / 8)
            var buffer = 0
            var bits = 0
            var index = 0
            for (char in text) {
                val digit = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(char)
                require(digit >= 0)
                buffer = (buffer shl 5) or digit
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    output[index++] = (buffer shr bits).toByte()
                }
            }
            require(output.isNotEmpty() && buffer and ((1 shl bits) - 1) == 0)
            return output
        }
    }
}
