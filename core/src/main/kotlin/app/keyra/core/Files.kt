package app.keyra.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.io.InputStream
import java.io.ByteArrayOutputStream

fun InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        require(output.size().toLong() + n <= maxBytes) { "INPUT_TOO_LARGE" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

internal fun ByteArray.hasDoctype(): Boolean {
    val marker = "<!DOCTYPE".toByteArray()
    var position = 0
    for (byte in this) {
        if (byte == 0.toByte()) continue // UTF-16 / UTF-32 XML must be checked as well.
        position = if (byte == marker[position]) position + 1 else if (byte == marker[0]) 1 else 0
        if (position == marker.size) return true
    }
    return false
}

fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** Temp and destination share a directory; the previous file survives failures before commit. */
fun atomicWrite(destination: File, writer: (File) -> Unit) {
    destination.parentFile?.mkdirs()
    val temp = File.createTempFile(".keyra-", ".tmp", destination.parentFile)
    try {
        writer(temp)
        FileOutputStream(temp, true).use { it.fd.sync() }
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temp.delete() }
}

fun atomicCopy(source: File, destination: File) = atomicWrite(destination) { temp -> source.inputStream().use { i -> temp.outputStream().use(i::copyTo) } }
