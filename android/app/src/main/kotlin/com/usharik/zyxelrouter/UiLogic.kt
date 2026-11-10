package com.usharik.zyxelrouter

import java.util.Locale

// Чистая логика интерфейса: вынесена из Compose и ViewModel, чтобы покрываться JVM-тестами.

/** Русское числительное: plural(1, "устройство", "устройства", "устройств") → «1 устройство». */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val form = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $form"
}

internal fun lastSeenLabel(epoch: Long, now: Long = System.currentTimeMillis() / 1000): String {
    if (epoch <= 0) return "давно"
    val diff = now - epoch
    return when {
        diff < 90 -> "только что"
        diff < 3600 -> "${diff / 60} мин назад"
        diff < 86400 -> "${diff / 3600} ч назад"
        else -> "${plural((diff / 86400).toInt(), "день", "дня", "дней")} назад"
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

/** Текст ошибки формы профиля или null, если всё верно. */
internal fun validateProfile(name: String, macCount: Int, dayCount: Int, start: String, end: String): String? {
    val startMin = normalizeTime(start)?.let { minutesOfTime(it) }
    val endMin = normalizeTime(end)?.let { minutesOfTime(it) }
    return when {
        name.isBlank() -> "Укажите название профиля."
        macCount == 0 -> "Выберите хотя бы одно устройство."
        macCount > 32 -> "Не больше 32 устройств."
        dayCount == 0 -> "Выберите хотя бы один день."
        startMin == null || endMin == null -> "Время в формате ЧЧ:ММ."
        startMin >= endMin -> "Начало должно быть раньше конца. Ночной интервал задайте двумя профилями."
        else -> null
    }
}

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
        if (!retry) throw RouterException.Failed("Сессия обновлена. Повторите действие.")
        block()
    }
