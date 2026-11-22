package com.usharik.routermanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Описание UPnP загружается под настоящими сетевыми правилами Android, где незашифрованный HTTP запрещён.
 * Ручной тест: на компьютере `python3 -m http.server 8765` в папке с desc.xml; эмулятор видит компьютер как 10.0.2.2.
 * Без сервера пропускается.
 */
@RunWith(AndroidJUnit4::class)
class UpnpFetchTest {
    private val url = URL("http://10.0.2.2:8765/desc.xml")

    @Test fun descriptionIsFetchedDespiteCleartextBan() {
        val reachable = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("10.0.2.2", 8765), 500) } }.isSuccess
        assumeTrue("нужен HTTP-сервер на компьютере, порт 8765", reachable)
        // Системный клиент HTTP отказывает: ровно поэтому описания загружаются своим запросом по сокету.
        val system = runCatching { (url.openConnection() as HttpURLConnection).run { connectTimeout = 1_000; responseCode } }
        assertTrue("системный HTTP запрещён: ${system.exceptionOrNull()}", system.exceptionOrNull()?.message.orEmpty().contains("Cleartext", ignoreCase = true))
        val body = httpGet(url, System.currentTimeMillis() + 2_000)
        assertEquals("Test TV", parseUpnpDescription(body!!).name)
    }
}
