package com.usharik.routermanager

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// Обрывы связи с провайдером: чистая логика без сети и Android. Из записей журнала роутера (и, если есть, состояния
// WAN и линии DSL) собираются инциденты [ConnectionDrop]; они копятся локально, потому что журнал роутера короткий
// (на VMG8623-T50B ~600 строк, это несколько часов).
//
// Та же логика на Swift — macos/Sources/RouterKit/ConnectionDrops.swift. Правила, константы и формат JSON должны
// совпадать: меняя одно, меняйте и другое (тесты на общей фикстуре fixtures/real/syslog_drops.json есть с обеих сторон).

/** Что оборвалось. Порядок — от общего к точному: при слиянии двух записей об одном инциденте берётся более точное. */
enum class DropKind { WAN, PPP_SESSION, DSL_LINE }

/** Причина, если роутер её записал (сообщения pppd). */
enum class DropCause { PEER_TERMINATED, NO_ECHO, HANGUP, NO_SERVER, AUTH_FAILED }

internal enum class LinkRole { DOWN, UP, CAUSE, INFO }
internal enum class LinkSource { SYSTEM, PPP, DSL }
internal data class LinkSignal(val role: LinkRole, val source: LinkSource, val iface: String = "", val cause: DropCause? = null, val sessionMinutes: Long? = null)

/** События ближе этого интервала (секунды) — один инцидент: связь «моргает» несколько раз подряд. */
const val DROP_GROUP_GAP = 300L
/** Соединение, поднятое в первые минуты после загрузки роутера, — обычный старт, а не обрыв. */
const val DROP_BOOT_GRACE = 600L
/** Допуск при сравнении времени из журнала (часы роутера) и времени работы соединения (часы телефона). */
const val DROP_CLOCK_TOLERANCE = 60L
const val DROP_RETENTION_DAYS = 90L
const val DROP_MAX_STORED = 500
const val DROP_MAX_LINES = 40
/** Замер линии позже этого срока после обрыва уже ничего не говорит о нём. */
const val DROP_LINE_STATS_MAX_AGE = 6 * 3600L

// ---- Правила распознавания. Все формулировки прошивки — только здесь. ----
// Подтверждено на VMG8623-T50B V5.50(ABPM.7.3): «Wan interface … connection lost/ready» и сообщения pppd
// «Json PPP … state», «Connect time», «Sent PADT» и др. Причины pppd (LCP terminated, echo-requests, Modem hangup,
// PADO) — штатные тексты pppd/rp-pppoe; на живом роутере не встречались. Категория xdsl на живом роутере пуста
// (линия не падала), поэтому её правило — осторожное предположение.
private val WAN_STATE = Regex("""wan interface (\S+) connection (lost|ready)""", RegexOption.IGNORE_CASE)
private val PPP_STATE = Regex(""""state"\s*:\s*"(up|down)"""", RegexOption.IGNORE_CASE)
private val PPP_CONNECT_TIME = Regex("""^Connect time ([\d.]+) minutes""", RegexOption.IGNORE_CASE)
private val PPP_CAUSES = listOf(
    Regex("LCP terminated by peer", RegexOption.IGNORE_CASE) to DropCause.PEER_TERMINATED,
    Regex("""No response to \d+ echo-requests|Serial link appears to be disconnected""", RegexOption.IGNORE_CASE) to DropCause.NO_ECHO,
    Regex("Modem hangup", RegexOption.IGNORE_CASE) to DropCause.HANGUP,
    Regex("Timeout waiting for PAD[OS] packets|Unable to complete PPPoE Discovery", RegexOption.IGNORE_CASE) to DropCause.NO_SERVER,
    Regex("authentication failed", RegexOption.IGNORE_CASE) to DropCause.AUTH_FAILED,
)
private val PPP_INFO = Regex(
    """^(Sent \d+ bytes|Sent PADT|PPP session is|Using interface|Connect: |Connection terminated|Remote message|(PAP|CHAP) authentication succeeded|peer from calling number|(local|remote) +(IP|LL) address|(primary|secondary) +DNS address)""",
    RegexOption.IGNORE_CASE,
)
private val DSL_DOWN = Regex("""\b(link|line)\b.*\b(down|lost)\b|\bloss of (signal|sync|frame)|\bretrain""", RegexOption.IGNORE_CASE)
private val DSL_UP = Regex("""\b(link|line)\b.*\bup\b|\bshowtime\b""", RegexOption.IGNORE_CASE)

/** Что строка журнала говорит о связи с провайдером; null — к обрывам не относится. */
internal fun classifyLinkEvent(e: LogEntry): LinkSignal? {
    val m = e.message
    WAN_STATE.find(m)?.let {
        return LinkSignal(if (it.groupValues[2].equals("lost", true)) LinkRole.DOWN else LinkRole.UP, LinkSource.SYSTEM, it.groupValues[1])
    }
    if (e.process.startsWith("pppd", ignoreCase = true)) {
        PPP_STATE.find(m)?.let { return LinkSignal(if (it.groupValues[1].equals("down", true)) LinkRole.DOWN else LinkRole.UP, LinkSource.PPP) }
        PPP_CAUSES.firstOrNull { it.first.containsMatchIn(m) }?.let { return LinkSignal(LinkRole.CAUSE, LinkSource.PPP, cause = it.second) }
        PPP_CONNECT_TIME.find(m)?.let { return LinkSignal(LinkRole.INFO, LinkSource.PPP, sessionMinutes = it.groupValues[1].toDoubleOrNull()?.toLong()) }
        if (PPP_INFO.containsMatchIn(m)) return LinkSignal(LinkRole.INFO, LinkSource.PPP)
        return null
    }
    if (e.category.equals("xdsl", ignoreCase = true)) {
        if (DSL_DOWN.containsMatchIn(m)) return LinkSignal(LinkRole.DOWN, LinkSource.DSL)
        if (DSL_UP.containsMatchIn(m)) return LinkSignal(LinkRole.UP, LinkSource.DSL)
    }
    return null
}

/** Строка журнала о потере связи (true), о восстановлении (false) или не про это (null) — для подсветки в списке. */
internal fun dropLineIsLoss(e: LogEntry): Boolean? = when (classifyLinkEvent(e)?.role) {
    LinkRole.DOWN, LinkRole.CAUSE -> true
    LinkRole.UP -> false
    else -> null
}

/**
 * Время записи журнала (секунды эпохи). Года в журнале нет: берётся текущий, а дата «из будущего» относится
 * к прошлому году (31 декабря, прочитанное в январе).
 */
internal fun logEpoch(e: LogEntry, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (e.month !in 1..12 || e.day !in 1..31) return null
    val t = e.time.split(":").map { it.toIntOrNull() ?: return null }
    val year = Instant.ofEpochSecond(now).atZone(zone).year
    fun at(y: Int) = runCatching { LocalDateTime.of(y, e.month, e.day, t.getOrElse(0) { 0 }, t.getOrElse(1) { 0 }, t.getOrElse(2) { 0 }).atZone(zone).toEpochSecond() }.getOrNull()
    val thisYear = at(year)
    return if (thisYear != null && thisYear <= now + 86400) thisYear else at(year - 1)
}

/** Состояние линии DSL из «xDSL Statistics» роутера на момент [capturedAt]. Счётчики ошибок — с начала синхронизации. */
data class LineStats(
    val capturedAt: Long, val up: Boolean, val modulation: String, val downKbps: Long, val upKbps: Long,
    val snrDown: Double?, val snrUp: Double?, val attnDown: Double?, val attnUp: Double?, val powerDown: Double?, val powerUp: Double?,
    val crcDown: Long, val crcUp: Long, val fecDown: Long, val fecUp: Long,
    /** Сколько линия держит синхронизацию; null — прошивка не сообщила. */
    val uptimeSec: Long?,
)

/** Текст статистики из ответа `/cgi-bin/xDSLStatistics_handle?line=0`: `[{"result": "…"}]`. */
internal fun lineStatsText(reply: String?): String? =
    runCatching { JSONArray(reply).optJSONObject(0)?.optString("result") }.getOrNull()?.takeIf { it.isNotBlank() }

/** «14 days,  5:15, 21 secs», «1 min, 10 secs», «3 hours, 2 min, 5 secs» → секунды. */
internal fun parseLineUptime(text: String): Long? {
    fun n(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: 0 }
    val days = n("""(\d+)\s*days?"""); val hm = n("""(\d+):(\d+)"""); val hours = n("""(\d+)\s*hours?""")
    val min = n("""(\d+)\s*min"""); val sec = n("""(\d+)\s*secs?""")
    if (days == null && hm == null && hours == null && min == null && sec == null) return null
    return (days?.get(0) ?: 0) * 86400 + (hm?.get(0) ?: 0) * 3600 + (hm?.get(1) ?: 0) * 60 + (hours?.get(0) ?: 0) * 3600 + (min?.get(0) ?: 0) * 60 + (sec?.get(0) ?: 0)
}

/**
 * Разбирает текст «xDSL Statistics». near-end — приём на роутере (downstream), far-end — приём на стороне
 * провайдера (upstream). Null, если это не статистика DSL (другая модель, Ethernet WAN).
 */
internal fun parseLineStats(text: String?, now: Long): LineStats? {
    if (text == null || !text.contains("Line State", ignoreCase = true)) return null
    fun value(label: String) = Regex("""(?im)^\s*${Regex.escape(label)}\s*:\s*(.*?)\s*$""").find(text)?.groupValues?.get(1)
    fun num(label: String) = value(label)?.let { Regex("""-?\d+(\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
    fun both(prefix: String, what: String) = listOf("fast", "interleaved").sumOf { num("$prefix $what $it")?.toLong() ?: 0 }
    fun rate(side: String) = listOf("fast", "interleaved").sumOf { num("$side $it channel bit rate")?.toLong() ?: 0 }
    return LineStats(
        now, value("Line State").equals("Up", ignoreCase = true), value("Modulation").orEmpty(), rate("near-end"), rate("far-end"),
        num("noise margin downstream"), num("noise margin upstream"), num("attenuation downstream"), num("attenuation upstream"),
        num("output power downstream"), num("output power upstream"),
        both("near-end", "CRC error"), both("far-end", "CRC error"), both("near-end", "FEC error"), both("far-end", "FEC error"),
        value("DSL uptime")?.let(::parseLineUptime),
    )
}

/**
 * Один инцидент: связь пропала и вернулась (возможно, несколько раз подряд — [flaps]).
 * Хотя бы одно из [lostAt] и [restoredAt] известно.
 */
data class ConnectionDrop(
    /** Когда связь пропала; null — начало в журнал не попало (инцидент выведен из времени работы соединения). */
    val lostAt: Long?,
    /** Когда связь вернулась; null — ещё не вернулась или восстановление не видно. */
    val restoredAt: Long?,
    /** [restoredAt] вычислено из времени работы соединения, а не взято из журнала. */
    val restoredInferred: Boolean = false,
    val kind: DropKind = DropKind.WAN,
    /** Имя WAN-интерфейса из журнала («VDSL», «ADSL», «ETHWAN»); пусто, если роутер его не назвал. */
    val iface: String = "",
    /** Сколько раз связь пропадала за инцидент. */
    val flaps: Int = 1,
    val cause: DropCause? = null,
    /** Строка журнала с причиной как есть. */
    val causeText: String = "",
    /** Сколько минут прожила оборвавшаяся PPP-сессия (pppd «Connect time»). */
    val sessionMinutes: Long? = null,
    /** Линия DSL при этом держала синхронизацию (true), пересинхронизировалась (false) или неизвестно (null). */
    val lineHeld: Boolean? = null,
    /** Строки журнала роутера об этом инциденте, как есть. */
    val lines: List<String> = emptyList(),
    /** Линия при первом чтении после обрыва. */
    val line: LineStats? = null,
    /** Прирост ошибок CRC на приёме между замером до обрыва ([crcSince]) и замером после. */
    val crcDelta: Long? = null,
    val crcSince: Long? = null,
) {
    /** Время инцидента для сортировки и как ключ: после слияния у разных инцидентов оно разное. */
    val at: Long get() = lostAt ?: restoredAt ?: 0
    val durationSec: Long? get() = if (lostAt != null && restoredAt != null) (restoredAt - lostAt).coerceAtLeast(0) else null
    internal val end: Long get() = restoredAt ?: at
}

/** Собирает инциденты из журнала (записи идут от старых к новым). */
internal fun dropsFromLog(entries: List<LogEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ConnectionDrop> {
    class Builder(val start: Long) {
        var last = start; var down = false; var flaps = 0; var lastUp: Long? = null; var iface = ""
        var cause: DropCause? = null; var causeText = ""; var sessionMinutes: Long? = null
        val sources = mutableSetOf<LinkSource>(); val lines = mutableListOf<String>()

        fun add(s: LinkSignal, t: Long, e: LogEntry) {
            last = maxOf(last, t)
            if (iface.isEmpty()) iface = s.iface
            if (lines.size < DROP_MAX_LINES) lines += e.raw
            when (s.role) {
                LinkRole.DOWN, LinkRole.CAUSE -> {
                    if (!down) { flaps++; down = true }
                    sources += s.source
                    if (s.role == LinkRole.CAUSE && cause == null) { cause = s.cause; causeText = e.message }
                }
                LinkRole.UP -> { down = false; lastUp = t }
                LinkRole.INFO -> if (sessionMinutes == null) sessionMinutes = s.sessionMinutes
            }
        }

        fun build() = ConnectionDrop(
            start, if (down) null else lastUp,
            kind = when { LinkSource.DSL in sources -> DropKind.DSL_LINE; LinkSource.PPP in sources -> DropKind.PPP_SESSION; else -> DropKind.WAN },
            iface = iface, flaps = flaps, cause = cause, causeText = causeText, sessionMinutes = sessionMinutes, lines = lines.toList(),
        )
    }

    val out = mutableListOf<ConnectionDrop>()
    var current: Builder? = null
    // pppd пишет итог сессии («Connect time…») за мгновение до «state: down» — эти строки ждут здесь начала инцидента.
    var pending = listOf<Triple<LinkSignal, Long, LogEntry>>()
    for (e in entries) {
        val s = classifyLinkEvent(e) ?: continue
        val t = logEpoch(e, now, zone) ?: continue
        var b = current
        if (b != null) {
            val gap = t - b.last
            val otherIface = s.iface.isNotEmpty() && b.iface.isNotEmpty() && !s.iface.equals(b.iface, ignoreCase = true)
            // Пока связи нет, её восстановление относится к этому инциденту, сколько бы ни прошло. Новая потеря
            // после долгой тишины — уже другой инцидент (восстановление предыдущего в журнал не попало).
            val joins = gap >= 0 && !otherIface && (gap <= DROP_GROUP_GAP || (b.down && (s.role == LinkRole.UP || s.role == LinkRole.INFO)))
            if (!joins) { out += b.build(); b = null }
        }
        if (b == null) {
            if (s.role == LinkRole.UP || s.role == LinkRole.INFO) {
                current = null
                pending = (pending + Triple(s, t, e)).filter { t - it.second in 0..5 }
                continue
            }
            b = Builder(t)
            pending.filter { t - it.second in 0..5 && it.first.role == LinkRole.INFO }.forEach { b.add(it.first, t, it.third) }
        }
        pending = emptyList()
        b.add(s, t, e)
        current = b
    }
    current?.let { out += it.build() }
    return out
}

/**
 * Сливает сохранённые инциденты с только что прочитанными. Один и тот же журнал читается много раз, а его начало
 * уходит из буфера роутера, поэтому тождество инцидента — пересечение по времени (с допуском [DROP_GROUP_GAP]),
 * а не совпадение полей: запись с более ранним началом дополняется более поздними сведениями.
 */
internal fun mergeDrops(stored: List<ConnectionDrop>, fresh: List<ConnectionDrop>): List<ConnectionDrop> {
    val out = mutableListOf<ConnectionDrop>()
    for (d in (stored + fresh).sortedBy { it.at }) {
        val a = out.lastOrNull()
        if (a == null || d.at > a.end + DROP_GROUP_GAP) { out += d; continue }
        // Время восстановления из журнала надёжнее вычисленного; из двух журнальных берётся более позднее.
        val (restoredAt, inferred) = when {
            a.restoredAt == null -> d.restoredAt to d.restoredInferred
            d.restoredAt == null -> a.restoredAt to a.restoredInferred
            a.restoredInferred != d.restoredInferred -> if (a.restoredInferred) d.restoredAt to false else a.restoredAt to false
            a.restoredInferred -> a.restoredAt to true
            else -> maxOf(a.restoredAt, d.restoredAt) to false
        }
        out[out.lastIndex] = ConnectionDrop(
            lostAt = listOfNotNull(a.lostAt, d.lostAt).minOrNull(), restoredAt = restoredAt, restoredInferred = inferred,
            kind = maxOf(a.kind, d.kind), iface = a.iface.ifEmpty { d.iface }, flaps = maxOf(a.flaps, d.flaps),
            cause = a.cause ?: d.cause, causeText = a.causeText.ifEmpty { d.causeText }, sessionMinutes = a.sessionMinutes ?: d.sessionMinutes,
            lineHeld = a.lineHeld ?: d.lineHeld, lines = (a.lines + d.lines.filter { it !in a.lines }).take(DROP_MAX_LINES),
            line = a.line ?: d.line, crcDelta = a.crcDelta ?: d.crcDelta, crcSince = a.crcSince ?: d.crcSince,
        )
    }
    return out
}

/** Что роутер сообщает о соединении прямо сейчас. */
data class LinkStatus(
    val routerUptime: Long, val wanUp: Boolean,
    /** Сколько секунд держится текущее WAN-соединение (PPP-сессия или аренда IPoE). */
    val wanUptime: Long,
    val pppoe: Boolean, val line: LineStats?,
)

/**
 * Дополняет инциденты тем, чего нет в журнале:
 *  - линия DSL пересинхронизировалась или держалась (по времени её работы) — отсюда вид обрыва;
 *  - время восстановления незакрытого инцидента — из времени работы WAN-соединения;
 *  - переподключение, которого в журнале уже нет (буфер ушёл), — отдельным инцидентом без времени начала;
 *  - состояние линии при первом чтении после обрыва и прирост ошибок CRC с прошлого замера [previousLine].
 */
internal fun withLinkStatus(drops: List<ConnectionDrop>, link: LinkStatus, now: Long, previousLine: LineStats? = null): List<ConnectionDrop> {
    var out = drops.sortedBy { it.at }
    /** Инцидент, к которому относится переподключение в момент [t], или -1. */
    fun covering(t: Long) = out.indexOfLast { it.at <= t + DROP_CLOCK_TOLERANCE }.takeIf { it >= 0 && t <= (out[it].restoredAt ?: now) + DROP_GROUP_GAP } ?: -1

    val lineUptime = link.line?.takeIf { it.up }?.uptimeSec
    if (lineUptime != null) {
        val lineStart = now - lineUptime
        val resynced = link.routerUptime - lineUptime > DROP_BOOT_GRACE
        val hit = if (resynced) covering(lineStart) else -1
        out = out.mapIndexed { i, d ->
            when {
                i == hit -> d.copy(kind = DropKind.DSL_LINE, lineHeld = false)
                d.lineHeld == null && d.at > lineStart + DROP_CLOCK_TOLERANCE -> d.copy(lineHeld = true)
                else -> d
            }
        }
        if (resynced && hit < 0) out = (out + ConnectionDrop(null, lineStart, restoredInferred = true, kind = DropKind.DSL_LINE, lineHeld = false)).sortedBy { it.at }
    }

    if (link.wanUp && link.wanUptime > 0) {
        val wanStart = now - link.wanUptime
        val hit = covering(wanStart)
        if (hit >= 0) {
            val d = out[hit]
            if (d.restoredAt == null && hit == out.lastIndex) out = out.dropLast(1) + d.copy(restoredAt = maxOf(wanStart, d.at), restoredInferred = true)
        } else if (link.routerUptime - link.wanUptime > DROP_BOOT_GRACE) {
            val held = lineUptime?.let { wanStart > now - it + DROP_CLOCK_TOLERANCE }
            out = (out + ConnectionDrop(null, wanStart, restoredInferred = true, kind = if (link.pppoe) DropKind.PPP_SESSION else DropKind.WAN, lineHeld = held)).sortedBy { it.at }
        }
    }

    val last = out.lastOrNull()
    val line = link.line
    if (last != null && line != null && last.line == null && last.restoredAt != null && now - last.restoredAt <= DROP_LINE_STATS_MAX_AGE) {
        // Счётчики CRC сбрасываются при пересинхронизации, поэтому прирост считается только если линия держалась.
        val before = previousLine?.takeIf { it.capturedAt < last.at && last.lineHeld == true && line.crcDown >= it.crcDown }
        out = out.dropLast(1) + last.copy(line = line, crcDelta = before?.let { line.crcDown - it.crcDown }, crcSince = before?.capturedAt)
    }
    return out
}

/** Локальная история обрывов одного роутера и последний замер линии (для прироста ошибок к следующему обрыву). */
data class DropLog(val drops: List<ConnectionDrop> = emptyList(), val lastLine: LineStats? = null) {
    /** Учитывает новое чтение журнала: слияние без дублей, дополнение по состоянию связи, срок хранения. */
    fun record(entries: List<LogEntry>, link: LinkStatus?, now: Long, zone: ZoneId = ZoneId.systemDefault()): DropLog {
        val merged = mergeDrops(drops, dropsFromLog(entries, now, zone))
        val completed = if (link != null) withLinkStatus(merged, link, now, lastLine) else merged
        return DropLog(completed.filter { it.at >= now - DROP_RETENTION_DAYS * 86400 }.takeLast(DROP_MAX_STORED), link?.line ?: lastLine)
    }

    fun toJson(): String = JSONObject().put("drops", JSONArray(drops.map(::dropToJson))).put("lastLine", lastLine?.let(::lineToJson) ?: JSONObject.NULL).toString()

    companion object {
        fun fromJson(raw: String?): DropLog {
            val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return DropLog()
            val arr = o.optJSONArray("drops") ?: JSONArray()
            return DropLog((0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::dropFromJson) }, o.optJSONObject("lastLine")?.let(::lineFromJson))
        }
    }
}

/** Сколько обрывов началось (или обнаружено) за последние [seconds]. */
internal fun dropsWithin(drops: List<ConnectionDrop>, now: Long, seconds: Long): Int = drops.count { it.at >= now - seconds }

// ---- JSON. Имена полей те же, что у Codable в Swift (drops.json диагностики macOS). ----

private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun lineToJson(l: LineStats) = JSONObject()
    .put("capturedAt", l.capturedAt).put("up", l.up).put("modulation", l.modulation).put("downKbps", l.downKbps).put("upKbps", l.upKbps)
    .putOpt("snrDown", l.snrDown).putOpt("snrUp", l.snrUp).putOpt("attnDown", l.attnDown).putOpt("attnUp", l.attnUp)
    .putOpt("powerDown", l.powerDown).putOpt("powerUp", l.powerUp)
    .put("crcDown", l.crcDown).put("crcUp", l.crcUp).put("fecDown", l.fecDown).put("fecUp", l.fecUp).putOpt("uptimeSec", l.uptimeSec)

private fun lineFromJson(o: JSONObject) = LineStats(
    o.optLong("capturedAt"), o.optBoolean("up"), o.optString("modulation"), o.optLong("downKbps"), o.optLong("upKbps"),
    o.doubleOrNull("snrDown"), o.doubleOrNull("snrUp"), o.doubleOrNull("attnDown"), o.doubleOrNull("attnUp"), o.doubleOrNull("powerDown"), o.doubleOrNull("powerUp"),
    o.optLong("crcDown"), o.optLong("crcUp"), o.optLong("fecDown"), o.optLong("fecUp"), o.longOrNull("uptimeSec"),
)

private fun dropToJson(d: ConnectionDrop) = JSONObject()
    .putOpt("lostAt", d.lostAt).putOpt("restoredAt", d.restoredAt).put("restoredInferred", d.restoredInferred)
    .put("kind", d.kind.name).put("iface", d.iface).put("flaps", d.flaps).putOpt("cause", d.cause?.name).put("causeText", d.causeText)
    .putOpt("sessionMinutes", d.sessionMinutes).putOpt("lineHeld", d.lineHeld).put("lines", JSONArray(d.lines))
    .putOpt("line", d.line?.let(::lineToJson)).putOpt("crcDelta", d.crcDelta).putOpt("crcSince", d.crcSince)

private fun dropFromJson(o: JSONObject): ConnectionDrop? {
    val lostAt = o.longOrNull("lostAt"); val restoredAt = o.longOrNull("restoredAt")
    if (lostAt == null && restoredAt == null) return null
    val lines = o.optJSONArray("lines")
    return ConnectionDrop(
        lostAt, restoredAt, o.optBoolean("restoredInferred"),
        runCatching { DropKind.valueOf(o.optString("kind")) }.getOrDefault(DropKind.WAN), o.optString("iface"), o.optInt("flaps", 1),
        o.optString("cause").takeIf { it.isNotEmpty() }?.let { runCatching { DropCause.valueOf(it) }.getOrNull() }, o.optString("causeText"),
        o.longOrNull("sessionMinutes"), if (o.isNull("lineHeld")) null else o.optBoolean("lineHeld"),
        if (lines == null) emptyList() else (0 until lines.length()).map { lines.optString(it) },
        o.optJSONObject("line")?.let(::lineFromJson), o.longOrNull("crcDelta"), o.longOrNull("crcSince"),
    )
}
