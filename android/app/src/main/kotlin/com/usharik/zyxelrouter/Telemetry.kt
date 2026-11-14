package com.usharik.zyxelrouter

/** Firebase Analytics и Crashlytics так, как их видит приложение; значения параметров — String или Long. */
interface TelemetrySink {
    fun logEvent(name: String, params: Map<String, Any>)
    /** Запись в журнал Crashlytics, попадает в отчёт о следующем сбое. */
    fun log(message: String)
    fun setCustomKey(key: String, value: String)
    fun recordException(error: Throwable)
    /** Сбор включается только после ответа формы согласия (см. [AdPacing.analyticsAllowed]). */
    fun setCollectionEnabled(enabled: Boolean) {}
}

/**
 * Аналитика без личных данных. Ничего не отправляется, пока приложение не установит [TelemetrySink]: без
 * google-services.json, в debug-сборке и в тестах всё молча пропускается.
 *
 * Не отправляются: адреса (IP, MAC), имена устройств и сетей, логин, пароль, содержимое журнала роутера.
 * Тексты ошибок проходят через [redact].
 */
object Telemetry {
    @Volatile private var sink: TelemetrySink? = null

    fun install(value: TelemetrySink?) { sink = value }

    fun setCollectionEnabled(enabled: Boolean) { sink?.setCollectionEnabled(enabled) }

    fun event(name: String, params: Map<String, Any> = emptyMap()) {
        sink?.logEvent(name, params.mapValues { (_, v) -> if (v is Number) v.toLong() else redact(v.toString()).take(MAX_VALUE) })
    }

    /** Вкладка или диалог; автоматический учёт экранов по Activity выключен в манифесте. */
    fun screen(name: String) = event("screen_view", mapOf("screen_name" to name, "screen_class" to name))

    fun breadcrumb(message: String) { sink?.log(redact(message).take(MAX_LOG)) }

    /** Модель и прошивка роутера помогают разбирать сбои: формат API отличается между прошивками. */
    fun router(model: String, firmware: String) {
        sink?.setCustomKey("router_model", model.take(MAX_VALUE))
        sink?.setCustomKey("router_firmware", firmware.take(MAX_VALUE))
        event("router_connected", mapOf("model" to model.ifEmpty { "unknown" }, "firmware" to firmware.ifEmpty { "unknown" }))
    }

    /** Неожиданная ошибка: в Crashlytics без адресов в тексте, в Analytics — только название операции. */
    fun error(operation: String, error: Throwable) {
        sink?.setCustomKey("operation", operation)
        sink?.recordException(RedactedException.of(error))
        event("app_error", mapOf("operation" to operation, "error_class" to (error::class.simpleName ?: "Throwable")))
    }

    private const val MAX_VALUE = 100
    private const val MAX_LOG = 1000
}

/** Причина ошибки входа одним словом, без текста от роутера. */
internal fun loginFailureReason(e: Throwable): String = when (e) {
    is RouterException.BadAddress -> "bad_address"
    is RouterException.InvalidCredentials -> "invalid_credentials"
    is RouterException.Locked -> "locked"
    is RouterException.SessionExpired -> "session_expired"
    is RouterException.CertificateChanged -> "certificate_changed"
    is RouterException.Unreachable -> "unreachable"
    is RouterException.BadResponse -> "bad_response"
    is RouterException.LoginUnreadable -> "login_unreadable"
    is RouterException.DuplicateSession -> "duplicate_session"
    is RouterException.TooManySessions -> "too_many_sessions"
    is RouterException.BadLoginData -> "bad_login_data"
    is RouterException.LoginRejected -> "login_rejected"
    is RouterException -> "failed"
    else -> "unexpected"
}

private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b""")
private val IPV6_CANDIDATE = Regex("""(?<![0-9A-Fa-f:.])[0-9A-Fa-f:]*:[0-9A-Fa-f:]*:[0-9A-Fa-f:]*(?![0-9A-Fa-f:])""")

/**
 * IPv6 в полной и сжатой форме («fe80::1», «2001:db8::1»). Время «16:54:14» похоже по символам, поэтому адресом
 * считается только запись с «::», с буквой a–f или из восьми групп.
 */
private fun isIpv6(s: String): Boolean {
    val groups = s.split(":")
    if (groups.size !in 3..8 || groups.any { it.length > 4 } || s.contains(":::")) return false
    return s.contains("::") || s.any { it.lowercaseChar() in 'a'..'f' } || groups.size == 8
}

/** Убирает из текста IP- и MAC-адреса. */
internal fun redact(text: String): String = text.replace(MAC, "<mac>").replace(IPV6_CANDIDATE) { if (isIpv6(it.value)) "<ipv6>" else it.value }.replace(IPV4, "<ip>")

/** Копия исключения с тем же стеком, но без адресов в сообщении; класс исходного исключения — в начале текста. */
class RedactedException private constructor(message: String, cause: Throwable?) : Exception(message, cause) {
    companion object {
        fun of(error: Throwable, depth: Int = 0): RedactedException {
            val cause = error.cause?.takeIf { depth < 5 && it !== error }?.let { of(it, depth + 1) }
            return RedactedException("${error::class.java.name}: ${redact(error.message.orEmpty())}", cause).also { it.stackTrace = error.stackTrace }
        }
    }
}
