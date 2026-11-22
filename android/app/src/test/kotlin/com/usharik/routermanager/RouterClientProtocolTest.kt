package com.usharik.routermanager

import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Настоящий [RouterClient] против мок-роутера на Node (mock/mock_router.js): TLS с закреплением сертификата,
 * вход RSA + AES, ротация CSRF, коды ошибок. Мок слушает 127.0.0.1 — для клиента это частный адрес.
 * Без Node тесты пропускаются (Assume), а не падают.
 */
class RouterClientProtocolTest {
    companion object {
        private var process: Process? = null
        private var port = 0
        private val address get() = "127.0.0.1:$port"

        private fun findMock(): File? = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "mock/mock_router.js") }.firstOrNull { it.isFile }

        private fun findNode(): String? = (listOfNotNull(System.getenv("NODE")) + listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node"))
            .firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) }

        @BeforeClass @JvmStatic fun startMock() {
            val mock = findMock() ?: return
            val node = findNode() ?: return
            port = ServerSocket(0).use { it.localPort }
            val p = ProcessBuilder(node, mock.path, port.toString()).redirectErrorStream(true).start()
            val ready = p.inputStream.bufferedReader().readLine()
            if (ready?.startsWith("mock on") == true) {
                process = p
                // Остальной вывод (журнал запросов) читаем в фоне, чтобы мок не встал на переполненном буфере.
                Thread { p.inputStream.bufferedReader().forEachLine { } }.apply { isDaemon = true }.start()
            } else p.destroy()
        }

        @AfterClass @JvmStatic fun stopMock() { process?.destroy() }

        /** Управляющие запросы к моку: без проверки сертификата, это не клиент приложения. */
        private fun control(path: String, body: String = "{}"): JSONObject {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val c = URL("https://$address$path").openConnection() as HttpsURLConnection
            c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            if (path != "/__test/state") {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        }
    }

    private val pins = MemoryCertificatePins()

    @Before fun reset() {
        assumeTrue("нужен Node.js и mock/mock_router.js", process != null)
        control("/__test/reset")
    }

    private fun signedIn() = RouterClient(address, pins).apply { login("admin", "testpass") }

    @Test fun loginPinsCertificateOnlyAfterSuccess() {
        val failed = RouterClient(address, pins)
        assertThrows(RouterException.InvalidCredentials::class.java) { failed.login("admin", "wrong") }
        assertNull("неверный пароль — отпечаток не закреплён", pins.get("127.0.0.1"))
        val c = signedIn()
        assertNotNull(pins.get("127.0.0.1"))
        assertEquals(1, c.sessionGeneration)
        assertTrue(c.snapshot().internetUp)
    }

    @Test fun requestsShareOneConnection() {
        val c = signedIn()
        c.snapshot()
        c.routing()
        // Вход, снимок и четыре раздела маршрутов — 9 запросов; рукопожатие TLS на роутере стоит около 0,2 с.
        assertEquals("одно TLS-соединение на все запросы", 1, control("/__test/state").getInt("connections"))
    }

    @Test fun loginReportsStepsAndRoutingProgress() {
        val steps = mutableListOf<LoginStep>()
        val c = RouterClient(address, pins).apply { login("admin", "testpass") { steps += it } }
        assertEquals(listOf(LoginStep.CONNECTING, LoginStep.SIGNING_IN), steps)
        val progress = mutableListOf<Int>()
        c.routing { progress += it }
        assertEquals((1..ROUTING_PARTS).toList(), progress)
    }

    @Test fun probeDoesNotPin() {
        RouterClient(address, pins).basicInfo()
        assertNull(pins.get("127.0.0.1"))
    }

    @Test fun lockedAfterThreeFailures() {
        repeat(3) { assertThrows(RouterException.InvalidCredentials::class.java) { RouterClient(address, pins).login("admin", "bad") } }
        val e = assertThrows(RouterException.Locked::class.java) { RouterClient(address, pins).login("admin", "testpass") }
        assertEquals(60, e.seconds)
    }

    @Test fun refusedClientIsClosed() {
        val c = RouterClient(address, pins)
        assertThrows(RouterException.InvalidCredentials::class.java) { c.login("admin", "bad") }
        // Запрос, ждавший очереди, не входит заново с тем же паролем.
        assertThrows(RouterException.SessionExpired::class.java) { c.reloginIfUnchanged(0, "admin", "bad") }
        assertEquals(1, control("/__test/state").getInt("fails"))
    }

    @Test fun writesRotateCsrfToken() {
        val c = signedIn()
        val profile = c.parentalControl().profiles.first()
        c.setProfileEnabled(profile.index, !profile.enabled)
        // Вторая запись подряд проходит только с новым токеном из ответа на первую.
        c.setProfileEnabled(profile.index, profile.enabled)
        c.saveProfile(null, "Homework", listOf("AA:BB:CC:DD:EE:FF"), listOf("Mon", "Tue"), "16:00-18:00", true)
        val created = c.parentalControl().profiles.last()
        assertEquals("Homework", created.name)
        assertEquals(listOf("Mon", "Tue"), created.days)
        c.deleteProfile(created.index)
        assertTrue(c.parentalControl().profiles.none { it.name == "Homework" })
    }

    @Test fun expiredSessionAndRecovery() {
        val c = signedIn()
        control("/__test/expire")
        assertThrows(RouterException.SessionExpired::class.java) { c.snapshot() }
        c.reloginIfUnchanged(c.sessionGeneration, "admin", "testpass")
        assertEquals(2, c.sessionGeneration)
        assertTrue(c.snapshot().devices.isNotEmpty())
    }

    @Test fun gatewayTimeoutMeansSessionExpired() {
        val c = signedIn()
        control("/__test/config", """{"status":{"cardpage_status":504}}""")
        assertThrows(RouterException.SessionExpired::class.java) { c.snapshot() }
        control("/__test/config", """{"status":{"paren_ctl":500}}""")
        assertThrows(RouterException.NoData::class.java) { c.parentalControl() }
    }

    @Test fun routingSurvivesMissingSections() {
        val c = signedIn()
        control("/__test/config", """{"status":{"rip":404,"dns_route":404}}""")
        val r = c.routing()
        assertTrue(r.rip.isEmpty() && r.dnsRoutes.isEmpty())
        assertEquals(1, r.staticRoutes.size)
        control("/__test/config", """{"status":{"rip":404,"dns_route":404,"static_route":404,"policy_route":404}}""")
        assertThrows(RouterException.NoData::class.java) { c.routing() }
        control("/__test/config", """{"status":{"rip":401}}""")
        assertThrows("сессия важнее частичной ошибки", RouterException.SessionExpired::class.java) { c.routing() }
    }

    @Test fun encryptedLogIsDecrypted() {
        val log = signedIn().systemLog()
        assertEquals(5, log.size)
        assertTrue(log.any { it.severity == "err" })
    }

    @Test fun changedCertificateIsRefusedBeforePassword() {
        pins.put("127.0.0.1", "00".repeat(32))
        val e = assertThrows(RouterException.CertificateChanged::class.java) { RouterClient(address, pins).login("admin", "testpass") }
        assertEquals(64, e.fingerprint.length)
        assertEquals("пароль до роутера не дошёл", false, control("/__test/state").getBoolean("session"))
    }

    @Test fun trustedNewFingerprintIsSavedAfterLogin() {
        pins.put("127.0.0.1", "00".repeat(32))
        val actual = assertThrows(RouterException.CertificateChanged::class.java) { RouterClient(address, pins).basicInfo() }.fingerprint
        RouterClient(address, TrustedPins(pins, "127.0.0.1", actual)).login("admin", "testpass")
        assertEquals(actual, pins.get("127.0.0.1"))
    }

    @Test fun unreadableLoginResponseIsNotUnreachable() {
        control("/__test/config", """{"garbageLogin":true}""")
        // «Недоступен» продолжил бы опрос с повторными входами; ответ был — значит, это отказ.
        assertThrows(RouterException.LoginUnreadable::class.java) { RouterClient(address, pins).login("admin", "testpass") }
    }

    @Test fun loginWithoutRsa() {
        control("/__test/config", """{"noRsa":true}""")
        val c = signedIn()
        assertTrue(c.snapshot().wifi.isNotEmpty())
    }

    @Test fun logoutClosesClient() {
        val c = signedIn()
        c.logout()
        assertEquals(false, control("/__test/state").getBoolean("session"))
        assertThrows(RouterException.SessionExpired::class.java) { c.snapshot() }
    }

    @Test fun unreachableWhenNothingListens() {
        val free = ServerSocket(0).use { it.localPort }
        assertThrows(RouterException.Unreachable::class.java) { RouterClient("127.0.0.1:$free", pins).basicInfo() }
    }
}
