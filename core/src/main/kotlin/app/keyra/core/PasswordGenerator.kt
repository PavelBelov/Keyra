package app.keyra.core

import java.security.SecureRandom

data class PasswordOptions(
    val length: Int = 20,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true
) {
    fun groups(): List<String> = buildList {
        if (lowercase) add("abcdefghijklmnopqrstuvwxyz")
        if (uppercase) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        if (digits) add("0123456789")
        if (symbols) add((33..126).map(Int::toChar).filterNot(Char::isLetterOrDigit).joinToString(""))
    }
}

object PasswordGenerator {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 256
    private val random = SecureRandom()

    /** Every enabled group is represented; selection and Fisher-Yates shuffle use a CSPRNG. */
    fun generate(options: PasswordOptions): String {
        val groups = options.groups()
        require(groups.isNotEmpty()) { "NO_CHARACTER_GROUPS" }
        require(options.length in MIN_LENGTH..MAX_LENGTH && options.length >= groups.size) { "INVALID_PASSWORD_LENGTH" }
        val alphabet = groups.joinToString("")
        val chars = CharArray(options.length)
        groups.forEachIndexed { index, group -> chars[index] = group[random.nextInt(group.length)] }
        for (index in groups.size until chars.size) chars[index] = alphabet[random.nextInt(alphabet.length)]
        for (index in chars.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val temporary = chars[index]; chars[index] = chars[other]; chars[other] = temporary
        }
        return try { String(chars) } finally { chars.fill('\u0000') }
    }
}
