package com.usharik.zyxelrouter

import org.json.JSONObject

/** Данные для подключения к сети Wi-Fi. Живут только в окне «Поделиться»: не сохраняются и не уходят в телеметрию. */
data class WifiCredentials(val ssid: String, val security: WifiSecurity, val password: String) {
    override fun toString() = "WifiCredentials(ssid=$ssid, security=$security, password=***)"
}

enum class WifiSecurity(val qrType: String?) {
    OPEN("nopass"),
    WEP("WEP"),
    /** WPA, WPA2 и смешанный WPA2/WPA3: телефоны подключаются по ним как к WPA. */
    WPA("WPA"),
    /** Только WPA3 (SAE): тип из спецификации Wi-Fi Alliance, с WPA телефон не подключится. */
    WPA3("SAE"),
    /** Корпоративная сеть (802.1X): одним паролем не подключиться, QR не строится. */
    ENTERPRISE(null);

    companion object {
        /** По ModeEnabled роутера: "None", "WEP-64", "WPA2-Personal", "WPA3-Personal", "WPA2-WPA3-Personal", "WPA2-Enterprise"… */
        fun parse(mode: String): WifiSecurity {
            val m = mode.uppercase()
            return when {
                m.isEmpty() || m == "NONE" || m == "OPEN" -> OPEN
                m.contains("ENTERPRISE") -> ENTERPRISE
                m.startsWith("WEP") -> WEP
                m.contains("WPA3") && !m.contains("WPA2") && !m.contains("TRANSITION") -> WPA3
                else -> WPA
            }
        }
    }
}

/**
 * Пароль сети [index] из ответа cardpage_status. Если сети на этом месте больше нет или её переименовали, — null:
 * лучше не показать QR, чем показать пароль другой сети.
 */
internal fun parseWifiCredentials(status: JSONObject, index: Int, ssid: String): WifiCredentials? {
    val item = status.optJSONArray("WiFiInfo")?.optJSONObject(index) ?: return null
    // Через str(), как и в снимке: прошивка экранирует &, <, > и кавычки в именах и паролях.
    if (str(item.opt("SSID")) != ssid || ssid.isEmpty()) return null
    val security = WifiSecurity.parse(str(item.opt("ModeEnabled")))
    val password = if (security == WifiSecurity.OPEN) "" else str(item.opt("wifiPassword"))
    if (security != WifiSecurity.OPEN && security != WifiSecurity.ENTERPRISE && password.isEmpty()) return null
    return WifiCredentials(ssid, security, password)
}

/** Строка для QR-кода в формате, который понимают камеры Android и iOS: WIFI:T:WPA;S:<имя>;P:<пароль>;; */
fun wifiQrPayload(c: WifiCredentials): String? {
    val type = c.security.qrType ?: return null
    return buildString {
        append("WIFI:T:").append(type).append(";S:").append(qrEscape(c.ssid)).append(';')
        if (c.security != WifiSecurity.OPEN) append("P:").append(qrEscape(c.password)).append(';')
        append(';')
    }
}

/** Спецсимволы формата: \ ; , : " экранируются обратной косой чертой. */
private const val QR_SPECIAL = "\\;,:\""

internal fun qrEscape(value: String): String = buildString {
    for (ch in value) {
        if (ch in QR_SPECIAL) append('\\')
        append(ch)
    }
}
