package com.github.gordonmu.coio.tls

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

fun loadX509Certificates(input: InputStream): List<X509Certificate> =
        CertificateFactory.getInstance("X.509").generateCertificates(input).map { it as X509Certificate }

fun loadPrivateKey(input: InputStream): PrivateKey {
    val derKeyBates = readPrivateKey(input)
    val keySpec = PKCS8EncodedKeySpec(derKeyBates)
    return try {
        KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    } catch (ignore: InvalidKeySpecException) {
        try {
            KeyFactory.getInstance("DSA").generatePrivate(keySpec)
        } catch (ignore2: InvalidKeySpecException) {
            try {
                KeyFactory.getInstance("EC").generatePrivate(keySpec)
            } catch (e: InvalidKeySpecException) {
                throw InvalidKeySpecException("Neither RSA, DSA nor EC worked", e)
            }
        }
    }
}

private val CERT_PATTERN = Pattern.compile(
        "-+BEGIN\\s+.*CERTIFICATE[^-]*-+(?:\\s|\\r|\\n)+" +  // Header
                "([a-z0-9+/=\\r\\n]+)" +  // Base64 text
                "-+END\\s+.*CERTIFICATE[^-]*-+",  // Footer
        Pattern.CASE_INSENSITIVE)
private val KEY_PATTERN = Pattern.compile(
        "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" +  // Header
                "([a-z0-9+/=\\r\\n]+)" +  // Base64 text
                "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",  // Footer
        Pattern.CASE_INSENSITIVE)

private fun readPrivateKey(input: InputStream): ByteArray {
    val content: String
    content = try {
        readContent(input)
    } catch (e: IOException) {
        throw KeyException("failed to read key input stream", e)
    }
    val m: Matcher = KEY_PATTERN.matcher(content)
    if (!m.find()) {
        throw KeyException("could not find a PKCS #8 private key in input stream")
    }
    val b64 = m.group(1).replace(Regex("""\s"""), "")
    return Base64.getDecoder().decode(b64)
}

private fun readContent(input: InputStream): String {
    val out = ByteArrayOutputStream()
    return try {
        val buf = ByteArray(8192)
        while (true) {
            val ret = input.read(buf)
            if (ret < 0) {
                break
            }
            out.write(buf, 0, ret)
        }
        out.toString(Charsets.US_ASCII.name())
    } finally {
        out.close()
    }
}
