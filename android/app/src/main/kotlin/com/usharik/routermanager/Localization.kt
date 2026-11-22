package com.usharik.routermanager

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

// Текст для пользователя из кодов слоя данных: логика разбора и проверки остаётся без строк и языка.

/** Сообщение об ошибке роутера на языке пользователя. */
fun routerErrorText(res: Resources, e: Throwable): String = when (e) {
    is RouterException.BadAddress -> res.getString(R.string.err_bad_address)
    is RouterException.InvalidCredentials -> res.getString(R.string.err_invalid_credentials)
    is RouterException.Locked -> res.getString(R.string.err_locked, e.seconds)
    is RouterException.SessionExpired -> res.getString(R.string.err_session_expired)
    is RouterException.SessionRefreshed -> res.getString(R.string.err_session_refreshed)
    is RouterException.Unreachable -> res.getString(R.string.err_unreachable)
    is RouterException.BadResponse, is RouterException.LoginUnreadable -> res.getString(R.string.err_bad_response)
    is RouterException.BadLoginData -> res.getString(R.string.err_bad_login_data)
    is RouterException.DuplicateSession -> res.getString(R.string.err_duplicate_session)
    is RouterException.TooManySessions -> res.getString(R.string.err_too_many_sessions)
    is RouterException.LoginRejected -> res.getString(R.string.err_login_rejected, e.detail)
    is RouterException.NoData -> res.getString(R.string.err_no_data, e.oid, e.status)
    is RouterException.WriteRejected -> res.getString(R.string.err_write_rejected, e.oid, e.status)
    is RouterException.ProfileNotFound -> res.getString(R.string.err_profile_not_found)
    is RouterException.DeleteRejected -> res.getString(R.string.err_delete_rejected, e.status)
    is RouterException.LogUnavailable -> res.getString(R.string.err_log_unavailable, e.status)
    is RouterException.RebootRejected -> res.getString(R.string.err_reboot_rejected, e.status)
    is RouterException.CertificateChanged -> res.getString(R.string.err_certificate_changed)
    else -> res.getString(R.string.err_unexpected, e::class.simpleName ?: "Exception")
}

@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale {
    // Язык из ресурсов, а не из системы: на словацком телефоне текст английский — и даты, дни и числа тоже английские.
    LocalConfiguration.current   // перечитать при смене языка
    return Locale.forLanguageTag(stringResource(R.string.locale_tag))
}

@Composable
@ReadOnlyComposable
internal fun deviceTypeLabel(type: DeviceType): String = stringResource(
    when (type) {
        DeviceType.PHONE -> R.string.type_phone
        DeviceType.COMPUTER -> R.string.type_computer
        DeviceType.PRINTER -> R.string.type_printer
        DeviceType.TV -> R.string.type_tv
        DeviceType.IOT -> R.string.type_iot
        DeviceType.UNKNOWN -> R.string.type_unknown
    },
)

@Composable
@ReadOnlyComposable
internal fun bandLabel(band: WifiBand): String = stringResource(if (band == WifiBand.GHZ_2_4) R.string.band_24 else R.string.band_5)

/** Где подключено устройство: SSID, «Wi-Fi 5 ГГц (гостевая)», «Проводная сеть» или тип подключения от роутера. */
@Composable
@ReadOnlyComposable
internal fun networkLabel(d: RouterDevice): String {
    if (d.network.isNotEmpty()) return d.network
    val band = bandOfConnection(d.connection)
    if (band == null) return if (d.connection.isEmpty()) "" else stringResource(R.string.wired)
    val wifi = stringResource(R.string.wifi_band, bandLabel(band))
    return if (d.guest) stringResource(R.string.guest_network, wifi) else wifi
}

@Composable
@ReadOnlyComposable
internal fun agoText(ago: Ago): String = when (ago) {
    Ago.LongAgo -> stringResource(R.string.ago_long)
    Ago.JustNow -> stringResource(R.string.ago_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.ago_minutes, ago.n, ago.n)
    is Ago.Hours -> pluralStringResource(R.plurals.ago_hours, ago.n, ago.n)
    is Ago.Days -> pluralStringResource(R.plurals.ago_days, ago.n, ago.n)
}

@Composable
@ReadOnlyComposable
internal fun profileErrorText(e: ProfileError): String = when (e) {
    ProfileError.NO_NAME -> stringResource(R.string.err_profile_no_name)
    ProfileError.NO_DEVICES -> stringResource(R.string.err_profile_no_devices)
    ProfileError.TOO_MANY_DEVICES -> stringResource(R.string.err_profile_too_many_devices, MAX_PROFILE_DEVICES)
    ProfileError.NO_DAYS -> stringResource(R.string.err_profile_no_days)
    ProfileError.BAD_TIME -> stringResource(R.string.err_profile_bad_time)
    ProfileError.START_NOT_BEFORE_END -> stringResource(R.string.err_profile_start_end)
    ProfileError.BAD_MAC -> stringResource(R.string.err_profile_bad_mac)
}

@Composable
@ReadOnlyComposable
internal fun bytesText(bytes: Long): String = formatBytes(bytes, stringArrayResource(R.array.bytes_units).toList(), currentLocale())

@Composable
@ReadOnlyComposable
internal fun durationText(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = (seconds / 86400).toInt()
    val h = (seconds % 86400 / 3600).toInt()
    val m = (seconds % 3600 / 60).toInt()
    return when {
        d > 0 -> stringResource(R.string.duration_dhm, d, h, m)
        h > 0 -> stringResource(R.string.duration_hm, h, m)
        else -> stringResource(R.string.duration_m, m)
    }
}

@Composable
@ReadOnlyComposable
internal fun speedText(kbps: Long): String =
    if (kbps > 0) stringResource(R.string.speed_mbps, String.format(currentLocale(), "%.1f", kbps / 1000.0)) else "—"

@Composable
@ReadOnlyComposable
internal fun routeSubtitle(r: RouteItem): String = when (val d = r.detail) {
    is RouteDetail.Static -> if (d.gateway.isEmpty()) stringResource(R.string.route_static, d.destination)
        else stringResource(R.string.route_static_via, d.destination, d.gateway)
    RouteDetail.Dns -> stringResource(R.string.route_dns)
    is RouteDetail.Policy -> {
        val from = listOfNotNull(d.sourceIp.takeIf { it.isNotEmpty() }?.let { "IP $it" }, "MAC".takeIf { d.byMac }).joinToString(", ")
        val source = if (from.isEmpty()) stringResource(R.string.route_policy_any) else stringResource(R.string.route_policy_from, from)
        if (d.protocol.isEmpty()) source else "$source, ${d.protocol}"
    }
    is RouteDetail.Rip -> listOf(d.version, d.mode).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
@ReadOnlyComposable
internal fun routeTitle(r: RouteItem): String = when (val d = r.detail) {
    is RouteDetail.Rip -> if (d.iface.isEmpty()) stringResource(R.string.rip) else stringResource(R.string.rip_interface, d.iface)
    else -> r.title
}

@Composable
@ReadOnlyComposable
internal fun periodLabel(p: LogPeriod): String = stringResource(
    when (p) {
        LogPeriod.DAY -> R.string.period_day
        LogPeriod.WEEK -> R.string.period_week
        LogPeriod.MONTH -> R.string.period_month
    },
)

internal fun monthShort(month: Int, locale: Locale): String = Month.of(month).getDisplayName(TextStyle.SHORT, locale)

/** День и короткий месяц в принятом для языка порядке: «8 нояб.», «Nov 8», «8. Nov.», «8. 11.». */
internal fun dayMonthText(day: Int, month: Int, locale: Locale): String {
    if (month !in 1..12 || day !in 1..31) return "—"
    val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM") }.getOrNull()
        ?: return "$day ${monthShort(month, locale)}"   // JVM-тесты: Android-шаблонов там нет
    val date = java.util.Calendar.getInstance().apply { clear(); set(2024, month - 1, day) }.time   // високосный год: 29 февраля из журнала не станет 1 марта
    return java.text.SimpleDateFormat(pattern, locale).format(date)
}

@Composable
@ReadOnlyComposable
internal fun periodCountLabel(c: PeriodCount): String = when (c.period) {
    LogPeriod.DAY -> dayMonthText(c.day, c.month, currentLocale())
    LogPeriod.WEEK -> stringResource(R.string.week_n, c.week)
    LogPeriod.MONTH -> monthName(c.month, currentLocale())
}

/**
 * Название месяца в именительном падеже («январь», «leden»). Не через java.time: на Android формы STANDALONE
 * для многих языков возвращают номер вместо названия; ICU-шаблон «LLLL» работает и там, и на JVM.
 */
internal fun monthName(month: Int, locale: Locale): String {
    val date = java.util.Calendar.getInstance().apply { clear(); set(2026, month - 1, 1) }.time
    return java.text.SimpleDateFormat("LLLL", locale).format(date).replaceFirstChar { it.titlecase(locale) }
}

/** Коды дней роутера по порядку недели. */
internal val DayCodes = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Короткое название дня на языке пользователя: «Пн», «Mon», «Po», «Mo». */
internal fun dayLabel(code: String, locale: Locale): String {
    val index = DayCodes.indexOf(code)
    if (index < 0) return code
    // SHORT, а не SHORT_STANDALONE: последняя на Android для ru/uk/cs/de даёт номер дня вместо названия.
    return DayOfWeek.of(index + 1).getDisplayName(TextStyle.SHORT, locale).trimEnd('.').replaceFirstChar { it.titlecase(locale) }
}

internal fun daysText(days: List<String>, locale: Locale) = days.joinToString(" ") { dayLabel(it, locale) }
