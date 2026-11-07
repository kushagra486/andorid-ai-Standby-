package com.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/** The bundled roots must all parse, be CA certificates, and not expire within five years. */
class CertificatesTest {
    @Test fun `bundled modern roots are valid`() {
        val file = File("src/main/res/raw/extra_roots.pem")
        val certs = file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }.map { it as X509Certificate }
        assertEquals(28, certs.size)
        val fiveYears = Date(System.currentTimeMillis() + 5L * 365 * 24 * 3600 * 1000)
        certs.forEach { c ->
            assertTrue("${c.subjectX500Principal} is not a CA", c.basicConstraints >= 0)
            assertTrue("${c.subjectX500Principal} is not self-signed", c.subjectX500Principal == c.issuerX500Principal)
            assertTrue("${c.subjectX500Principal} expires too soon", c.notAfter.after(fiveYears))
        }
        val names = certs.joinToString { it.subjectX500Principal.name }
        listOf("ISRG Root X1", "ISRG Root X2", "GTS Root R1", "GTS Root R4").forEach { assertTrue(it, names.contains(it)) }
    }

    @Test fun `manifest uses the network security config`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("@xml/network_security_config"))
        val cfg = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(cfg.contains("""src="system"""") && cfg.contains("@raw/extra_roots"))
    }
}
