package com.usharik.routermanager

import java.util.Locale

// Чистая логика интерфейса: вынесена из Compose и ViewModel, чтобы покрываться JVM-тестами.

/** Сколько прошло с последнего появления в сети; текст строит интерфейс с числительными языка пользователя. */
sealed interface Ago {
    data object LongAgo : Ago
    data object JustNow : Ago
    data class Minutes(val n: Int) : Ago
    data class Hours(val n: Int) : Ago
    data class Days(val n: Int) : Ago
}

internal fun lastSeenAgo(epoch: Long, now: Long = System.currentTimeMillis() / 1000): Ago {
    if (epoch <= 0) return Ago.LongAgo
    val diff = now - epoch
    return when {
        diff < 90 -> Ago.JustNow
        diff < 3600 -> Ago.Minutes((diff / 60).toInt())
        diff < 86400 -> Ago.Hours((diff / 3600).toInt())
        else -> Ago.Days((diff / 86400).toInt())
    }
}

/** Устройство, которое роутер ещё перечисляет как неактивное, не должно считаться «виденным только что». */
internal fun mergedLastSeen(online: Boolean, previous: Long, now: Long): Long = if (online) now else previous

/**
 * Баннер после фонового обновления. Уведомление о перезагрузке и ошибку записи опрос не стирает: иначе она
 * могла бы исчезнуть раньше, чем её прочитают. Новая ошибка связи заменяет только обычный баннер или ошибку записи.
 */
internal fun bannerAfterPoll(text: String?, current: String?, rebootNotice: Boolean, writeError: String?): String? = when {
    rebootNotice -> current
    text == null && current != null && current == writeError -> current
    else -> text
}

internal fun formatTime(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

enum class ProfileError { NO_NAME, NO_DEVICES, TOO_MANY_DEVICES, NO_DAYS, BAD_TIME, START_NOT_BEFORE_END, BAD_MAC }

/** Ошибка формы профиля или null, если всё верно. */
internal fun validateProfile(name: String, macCount: Int, dayCount: Int, start: String, end: String): ProfileError? {
    val startMin = normalizeTime(start)?.let { minutesOfTime(it) }
    val endMin = normalizeTime(endOfDay(end))?.let { minutesOfTime(it) }
    return when {
        name.isBlank() -> ProfileError.NO_NAME
        macCount == 0 -> ProfileError.NO_DEVICES
        macCount > MAX_PROFILE_DEVICES -> ProfileError.TOO_MANY_DEVICES
        dayCount == 0 -> ProfileError.NO_DAYS
        startMin == null || endMin == null -> ProfileError.BAD_TIME
        startMin >= endMin -> ProfileError.START_NOT_BEFORE_END
        else -> null
    }
}

const val MAX_PROFILE_DEVICES = 32

/** Конец интервала «00:00» значит «до полуночи»: роутер ждёт для этого 24:00. */
internal fun endOfDay(end: String): String = if (normalizeTime(end) == "00:00") "24:00" else end

/** Интервал профиля для роутера: ЧЧ:ММ-ЧЧ:ММ, конец 00:00 превращается в 24:00. Null, если время неверное. */
internal fun profileTimeRange(start: String, end: String): String? {
    val s = normalizeTime(start) ?: return null
    val e = normalizeTime(endOfDay(end)) ?: return null
    return "$s-$e"
}

/**
 * Интервалы при сохранении профиля. Если у профиля их было несколько и первый пользователь не менял, сохраняются
 * все; иначе — один новый. Сравнение по нормализованному виду: роутер может вернуть «9:00-17:00».
 */
internal fun timeRangesToSave(original: List<String>, edited: String): String {
    val first = original.firstOrNull()?.split("-")?.let { if (it.size == 2) profileTimeRange(it[0], it[1]) else null }
    return if (original.size > 1 && first == edited) {
        original.map { r -> r.split("-").let { if (it.size == 2) profileTimeRange(it[0], it[1]) ?: r else r } }.joinToString(",")
    } else edited
}

/**
 * Что делать, если фоновый повторный вход (или чтение сразу после него) не удался. Продолжать опрос можно только
 * при недоступном роутере (перезагрузка, Wi-Fi пропал): тогда пароль роутеру не отправлялся. Любой отказ самого
 * роутера, включая снова истёкшую сессию, останавливает опрос: иначе приложение входило бы каждые 15 секунд,
 * забивая таблицу сессий или блокируя учётную запись.
 */
// Нечитаемый ответ здесь — уже на чтение снимка после удачного входа (отказ во входе приходит как LoginUnreadable).
internal fun keepPollingAfterReloginFailure(e: RouterException): Boolean = e is RouterException.Unreachable || e is RouterException.BadResponse

/** Роутер не примет профиль сверх лимита; 0 означает, что лимит неизвестен. */
internal fun canAddProfile(pc: ParentalControl): Boolean = pc.maxProfiles <= 0 || pc.profiles.size < pc.maxProfiles

internal fun ParentalControl.withProfileEnabled(index: Int, on: Boolean): ParentalControl =
    copy(profiles = profiles.map { if (it.index == index) it.copy(enabled = on) else it })

/**
 * Выполняет действие с записью, восстанавливая сессию при устаревшем CSRF-токене: чтение токена не требует,
 * поэтому опрос об истёкшей для записи сессии не узнает. [retry] = false — для неидемпотентных действий:
 * HTTP 504 не гарантирует, что запись не применилась, поэтому пользователь повторяет её сам.
 */
internal fun <T> withSessionRecovery(retry: Boolean, relogin: () -> Unit, block: () -> T): T =
    try {
        block()
    } catch (e: RouterException.SessionExpired) {
        relogin()
        if (!retry) throw RouterException.SessionRefreshed
        block()
    }

/**
 * Ошибка, которую мог вернуть только повторный вход посреди действия: роутер отверг пароль, заблокировал вход,
 * сменил сертификат или не принял ещё одну сессию. Дальше работать без экрана входа нельзя.
 */
internal fun isLoginFailure(e: RouterException): Boolean = when (e) {
    is RouterException.InvalidCredentials, is RouterException.Locked, is RouterException.CertificateChanged,
    is RouterException.LoginRejected, is RouterException.TooManySessions, is RouterException.DuplicateSession,
    is RouterException.BadLoginData, is RouterException.LoginUnreadable -> true
    else -> false
}

/**
 * Уровень записи журнала для фильтра: «err» включает crit, alert и emerg, «warning» — warn. Так серьёзные записи
 * видны не только в «Все».
 */
internal fun severityGroup(severity: String): String = when (severity.lowercase()) {
    "emerg", "alert", "crit", "err", "error" -> "err"
    "warn", "warning" -> "warning"
    else -> severity.lowercase()
}
