package com.usharik.zyxelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryTest {
    @Test fun redactsAddresses() {
        val text = redact("connect to 192.168.1.1 failed, host AA:bb:CC:dd:EE:01 / aa-bb-cc-dd-ee-02, v6 2a00:1028:9192:1234::1")
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("AA:bb", ignoreCase = true))
        assertFalse(text.contains("aa-bb"))
        assertFalse(text.contains("2a00"))
        assertTrue(text.contains("<ip>") && text.contains("<mac>") && text.contains("<ipv6>"))
    }

    @Test fun redactedExceptionKeepsStackAndClass() {
        val original = IllegalStateException("router 10.0.0.1 said no", RuntimeException("mac 00:11:22:33:44:55"))
        val redacted = RedactedException.of(original)
        assertEquals("java.lang.IllegalStateException: router <ip> said no", redacted.message)
        assertEquals("java.lang.RuntimeException: mac <mac>", redacted.cause?.message)
        assertEquals(original.stackTrace.toList(), redacted.stackTrace.toList())
    }

    @Test fun eventsGoToSinkWithoutAddresses() {
        val events = mutableListOf<Pair<String, Map<String, Any>>>()
        Telemetry.install(object : TelemetrySink {
            override fun logEvent(name: String, params: Map<String, Any>) { events += name to params }
            override fun log(message: String) = Unit
            override fun setCustomKey(key: String, value: String) = Unit
            override fun recordException(error: Throwable) = Unit
        })
        try {
            Telemetry.event("x", mapOf("text" to "at 192.168.0.1", "count" to 3))
            assertEquals("at <ip>", events.single().second["text"])
            assertEquals(3L, events.single().second["count"])
        } finally {
            Telemetry.install(null)
        }
    }

    @Test fun loginFailureReasonsAreCategories() {
        assertEquals("invalid_credentials", loginFailureReason(RouterException.InvalidCredentials))
        assertEquals("certificate_changed", loginFailureReason(RouterException.CertificateChanged("AB")))
        assertEquals("unreachable", loginFailureReason(RouterException.Unreachable))
        assertEquals("unexpected", loginFailureReason(IllegalStateException()))
    }

    @Test fun redactsCompressedIpv6ButNotTimes() {
        assertEquals("from <ipv6> and <ipv6>", redact("from fe80::1 and 2001:db8::1"))
        assertEquals("<ipv6>", redact("2001:0db8:0000:0000:0000:ff00:0042:8329"))
        assertEquals("время остаётся", "Nov  8 16:54:14 lease", redact("Nov  8 16:54:14 lease"))
        assertEquals("<mac> <ip>", redact("AA:BB:CC:DD:EE:FF 192.168.1.5"))
    }
}
