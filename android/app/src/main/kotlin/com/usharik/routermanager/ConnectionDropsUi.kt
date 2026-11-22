package com.usharik.routermanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// Обрывы связи на экране: блок на вкладке «Журнал», строка обрыва и окно с подробностями.

@Composable
@ReadOnlyComposable
internal fun dropKindLabel(kind: DropKind): String = stringResource(
    when (kind) {
        DropKind.DSL_LINE -> R.string.drop_kind_dsl
        DropKind.PPP_SESSION -> R.string.drop_kind_ppp
        DropKind.WAN -> R.string.drop_kind_wan
    },
)

@Composable
@ReadOnlyComposable
internal fun dropCauseLabel(cause: DropCause): String = stringResource(
    when (cause) {
        DropCause.PEER_TERMINATED -> R.string.drop_cause_peer
        DropCause.NO_ECHO -> R.string.drop_cause_no_echo
        DropCause.HANGUP -> R.string.drop_cause_hangup
        DropCause.NO_SERVER -> R.string.drop_cause_no_server
        DropCause.AUTH_FAILED -> R.string.drop_cause_auth
    },
)

/** Дата и время события: «22 нояб. 17:14:58». Время — как в журнале роутера, 24 часа. */
internal fun epochTimeText(epoch: Long, locale: Locale, seconds: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochSecond(epoch).atZone(zone)
    val time = if (seconds) String.format(Locale.ROOT, "%02d:%02d:%02d", t.hour, t.minute, t.second) else formatTime(t.hour, t.minute)
    return "${dayMonthText(t.dayOfMonth, t.monthValue, locale)} $time"
}

/** Короткая длительность обрыва: «38 с», «2 мин 0 с», дальше — как время работы. */
@Composable
@ReadOnlyComposable
internal fun shortDurationText(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.duration_s, seconds.toInt())
    seconds < 3600 -> stringResource(R.string.duration_ms, (seconds / 60).toInt(), (seconds % 60).toInt())
    else -> durationText(seconds)
}

/** Когда случился обрыв; если начало в журнал не попало — примерное время восстановления. */
@Composable
@ReadOnlyComposable
internal fun dropWhenText(d: ConnectionDrop, seconds: Boolean = true): String {
    val locale = currentLocale()
    return d.lostAt?.let { epochTimeText(it, locale, seconds) } ?: stringResource(R.string.drop_approx, epochTimeText(d.at, locale, seconds))
}

/** Длительность или состояние: «≈ 2 мин 0 с», «ещё не восстановлена», «нет в журнале». */
@Composable
@ReadOnlyComposable
internal fun dropDurationText(d: ConnectionDrop, latest: Boolean): String {
    val duration = d.durationSec
    return when {
        duration != null -> shortDurationText(duration).let { if (d.restoredInferred) stringResource(R.string.drop_approx, it) else it }
        d.restoredAt == null -> stringResource(if (latest) R.string.drop_ongoing else R.string.drop_not_logged)
        else -> "—"
    }
}

@Composable
@ReadOnlyComposable
private fun dropSubtitle(d: ConnectionDrop): String = listOfNotNull(
    dropKindLabel(d.kind), d.iface.takeIf { it.isNotEmpty() },
    if (d.flaps > 1) pluralStringResource(R.plurals.drop_flaps_times, d.flaps, d.flaps) else null,
).joinToString(" · ")

/** Строка обрыва: время, что случилось, длительность. Вся строка — одна кнопка, TalkBack читает её целиком. */
@Composable
internal fun DropRow(d: ConnectionDrop, latest: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag("drop").padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.LinkOff, null, tint = scheme.error)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // FlowRow: на узком экране или с крупным шрифтом длительность уходит на свою строку, а не сжимает время в столбик.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dropWhenText(d), Modifier.padding(end = 8.dp), fontWeight = FontWeight.SemiBold)
                Text(dropDurationText(d, latest), style = MaterialTheme.typography.bodyMedium, color = if (d.restoredAt == null && latest) scheme.error else scheme.onSurface)
            }
            Text(dropSubtitle(d), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            d.cause?.let { Text(dropCauseLabel(it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
    }
}

/** Блок «Обрывы связи» вверху статистики журнала: счётчики и последние обрывы. [drops] — от новых к старым. */
@Composable
internal fun DropsSummaryCard(drops: List<ConnectionDrop>, now: Long, onShowAll: () -> Unit, onOpen: (ConnectionDrop) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("drops_card")) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LinkOff, null, tint = if (drops.isEmpty()) scheme.outline else scheme.error)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.drops_title), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        }
        if (drops.isEmpty()) {
            Text(stringResource(R.string.drops_none), Modifier.padding(16.dp, 8.dp))
            Text(stringResource(R.string.drops_hint), Modifier.padding(16.dp, 0.dp, 16.dp, 12.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            Text(
                stringResource(R.string.drops_counts, dropsWithin(drops, now, 86400), dropsWithin(drops, now, 7 * 86400), drops.size),
                Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            drops.take(SUMMARY_DROPS).forEachIndexed { i, d -> DropRow(d, latest = i == 0) { onOpen(d) } }
            if (drops.size > SUMMARY_DROPS) TextButton(onShowAll, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.drops_show_all)) }
            else Spacer(Modifier.height(4.dp))
        }
    }
}

private const val SUMMARY_DROPS = 3

@Composable
private fun DropDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(2f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(3f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

/** Всё, что известно об обрыве, включая строки журнала роутера как есть. */
@Composable
internal fun DropDetailsDialog(d: ConnectionDrop, latest: Boolean, onClose: () -> Unit) {
    val locale = currentLocale()
    val scheme = MaterialTheme.colorScheme
    fun number(v: Double?) = v?.let { String.format(locale, "%.1f", it) } ?: "—"
    TrackOverlay()
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
        icon = { Icon(Icons.Default.LinkOff, null, tint = scheme.error) },
        title = { Text(dropKindLabel(d.kind), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DropDetail(stringResource(R.string.drop_lost), d.lostAt?.let { epochTimeText(it, locale) } ?: stringResource(R.string.drop_not_logged))
                DropDetail(
                    stringResource(R.string.drop_restored),
                    d.restoredAt?.let { t -> epochTimeText(t, locale).let { if (d.restoredInferred) stringResource(R.string.drop_approx, it) else it } }
                        ?: stringResource(if (latest) R.string.drop_ongoing else R.string.drop_not_logged),
                )
                if (d.durationSec != null) DropDetail(stringResource(R.string.drop_duration), dropDurationText(d, latest))
                if (d.iface.isNotEmpty()) DropDetail(stringResource(R.string.drop_interface), d.iface)
                if (d.flaps > 1) DropDetail(stringResource(R.string.drop_flaps), d.flaps.toString())
                DropDetail(stringResource(R.string.drop_cause), d.cause?.let { dropCauseLabel(it) } ?: stringResource(R.string.drop_cause_none))
                d.sessionMinutes?.let { DropDetail(stringResource(R.string.drop_session), durationText(it * 60)) }
                d.lineHeld?.let { DropDetail(stringResource(R.string.drop_line), stringResource(if (it) R.string.drop_line_held else R.string.drop_line_resynced)) }
                if (d.restoredInferred) {
                    Text(stringResource(R.string.drop_approx_hint), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                d.line?.let { l ->
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.drop_line_measured, epochTimeText(l.capturedAt, locale, seconds = false)), color = scheme.primary, style = MaterialTheme.typography.labelLarge)
                    DropDetail(stringResource(R.string.drop_sync), stringResource(R.string.value_pair, speedText(l.downKbps), speedText(l.upKbps)))
                    if (l.snrDown != null || l.snrUp != null) DropDetail(stringResource(R.string.drop_snr_margin), stringResource(R.string.value_pair_db, number(l.snrDown), number(l.snrUp)))
                    if (l.attnDown != null || l.attnUp != null) DropDetail(stringResource(R.string.drop_attenuation), stringResource(R.string.value_pair_db, number(l.attnDown), number(l.attnUp)))
                    if (l.powerDown != null || l.powerUp != null) DropDetail(stringResource(R.string.drop_power), stringResource(R.string.value_pair_dbm, number(l.powerDown), number(l.powerUp)))
                    DropDetail(stringResource(R.string.drop_crc), stringResource(R.string.value_pair, l.crcDown.toString(), l.crcUp.toString()))
                    DropDetail(stringResource(R.string.drop_fec), stringResource(R.string.value_pair, l.fecDown.toString(), l.fecUp.toString()))
                    val since = d.crcSince
                    if (d.crcDelta != null && since != null) DropDetail(stringResource(R.string.drop_crc_delta, epochTimeText(since, locale, seconds = false)), d.crcDelta.toString())
                    l.uptimeSec?.let { DropDetail(stringResource(R.string.drop_line_uptime), durationText(it)) }
                }
                if (d.lines.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.drop_router_log), color = scheme.primary, style = MaterialTheme.typography.labelLarge)
                    d.lines.forEach { line ->
                        Text(line, Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        HorizontalDivider()
                    }
                }
            }
        },
    )
}
