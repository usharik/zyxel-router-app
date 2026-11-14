package com.usharik.zyxelrouter

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CertificatePinTest {
    private fun cert(name: String): X509Certificate =
        javaClass.getResourceAsStream("/certs/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Test fun pinDecision() {
        assertEquals(PinCheck.FIRST_USE, checkPin(null, "AB"))
        assertEquals(PinCheck.MATCH, checkPin("ab", "AB"))
        assertEquals(PinCheck.MISMATCH, checkPin("AB", "CD"))
    }

    /** Тестовые сертификаты давно просрочены — как и у многих роутеров; срок не проверяется. */
    @Test fun firstCertificateIsPinnedAndAnotherIsRejected() {
        val pins = MemoryCertificatePins()
        val tm = PinningTrustManager("192.168.1.1", pins)
        val a = cert("router_a")
        tm.checkServerTrusted(arrayOf(a), "RSA")
        assertEquals(certificateFingerprint(a.encoded), pins.get("192.168.1.1"))
        tm.checkServerTrusted(arrayOf(a), "RSA")   // тот же — принимается
        val b = cert("router_b")
        val e = assertThrows(PinMismatchException::class.java) { tm.checkServerTrusted(arrayOf(b), "RSA") }
        assertEquals(certificateFingerprint(b.encoded), e.fingerprint)
        // Другой роутер (адрес) — свой отпечаток.
        PinningTrustManager("192.168.0.1", pins).checkServerTrusted(arrayOf(b), "RSA")
    }

    @Test fun emptyChainAndClientCertificatesAreRejected() {
        val tm = PinningTrustManager("192.168.1.1", MemoryCertificatePins())
        assertThrows(CertificateException::class.java) { tm.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { tm.checkClientTrusted(arrayOf(cert("router_a")), "RSA") }
    }

    @Test fun fingerprintIsSha256Hex() {
        assertEquals(64, certificateFingerprint(byteArrayOf(1, 2, 3)).length)
        assertEquals("039058C6F2C0CB492C533B0A4D14EF77CC0F78ABCCCED5287D84A1A2011CFB81", certificateFingerprint(byteArrayOf(1, 2, 3)))
    }
}
