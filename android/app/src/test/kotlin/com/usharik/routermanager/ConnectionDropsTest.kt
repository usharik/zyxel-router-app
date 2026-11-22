package com.usharik.routermanager

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Обрывы связи: разбор на настоящем журнале (fixtures/real/syslog_drops.json, снят 22.11.2026 в 19:15 с VMG8623-T50B)
 * и на синтетических случаях. Те же проверки на Swift — в macos/Sources/RouterKitTests.
 */
class ConnectionDropsTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toEpochSecond()
    private val captured = at(2026, 11, 22, 19, 15, 20)

    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream("real/$name.json")!!.bufferedReader().readText()
    private fun log(vararg lines: String) = parseLog(JSONArray().put(JSONObject().put("result", JSONArray(lines.toList()))).toString())
    private val realLog by lazy { parseLog(resource("syslog_drops")) }
    private val realLine by lazy { parseLineStats(lineStatsText(resource("xdsl_statistics")), captured)!! }
    /** Состояние роутера в момент снятия фикстур: работает 14 суток, сессия PPP — 7104 с. */
    private fun realLink(later: Long = 0) = LinkStatus(1_228_689 + later, true, 7104 + later, true, realLine.copy(capturedAt = captured + later, uptimeSec = realLine.uptimeSec!! + later))

    private fun lost(time: String, iface: String = "VDSL") = "[system] $time user.notice zcmdModuleCfg: System: Wan interface $iface connection lost"
    private fun ready(time: String, iface: String = "VDSL") = "[system] $time user.notice zcmdModuleCfg: System: Wan interface $iface connection ready"

    @Test fun realLogIsOneIncidentWithThreeLosses() {
        assertTrue("журнал разобран", realLog.size > 500)
        val drops = dropsFromLog(realLog, captured, zone)
        assertEquals(1, drops.size)
        val d = drops.single()
        assertEquals("начало — конец сессии pppd, а не более позднее сообщение system", at(2026, 11, 22, 17, 14, 58), d.lostAt)
        assertEquals(at(2026, 11, 22, 17, 16, 58), d.restoredAt)
        assertFalse(d.restoredInferred)
        assertEquals(120L, d.durationSec)
        assertEquals("pppd down + два «connection lost»", 3, d.flaps)
        assertEquals(DropKind.PPP_SESSION, d.kind)
        assertEquals("VDSL", d.iface)
        assertEquals(20355L, d.sessionMinutes)
        assertNull("роутер причину не записал", d.cause)
        assertTrue("итог сессии pppd стоит первым", d.lines.first().contains("Connect time 20355.0 minutes"))
        assertEquals(2, d.lines.count { it.contains("connection lost") })
        assertTrue(d.lines.any { it.contains("Sent PADT") } && d.lines.none { it.contains("ZTR69") || it.contains("dnsmasq") })
    }

    @Test fun realLineStatisticsAreParsed() {
        val l = realLine
        assertTrue(l.up)
        assertTrue(l.modulation.contains("G.993.5"))
        assertEquals(184_364L, l.downKbps); assertEquals(55_613L, l.upKbps)
        assertEquals(6.3, l.snrDown!!, 0.001); assertEquals(7.5, l.snrUp!!, 0.001)
        assertEquals(9.7, l.attnDown!!, 0.001); assertEquals(1.9, l.attnUp!!, 0.001)
        assertEquals(12.3, l.powerDown!!, 0.001); assertEquals(7.5, l.powerUp!!, 0.001)
        assertEquals(45L, l.crcDown); assertEquals(0L, l.crcUp)
        assertEquals(17_390_643L, l.fecDown); assertEquals(3449L, l.fecUp)
        assertEquals("14 days,  5:15, 21 secs", 14 * 86400L + 5 * 3600 + 15 * 60 + 21, l.uptimeSec)
        assertEquals(70L, parseLineUptime("1 min, 10 secs"))
        assertNull(parseLineStats("{\"result\":\"ZCFG_NO_SUCH_OBJECT\"}", captured))
        assertNull(lineStatsText("not json"))
    }

    @Test fun realIncidentIsSessionDropBecauseLineKeptSync() {
        val drops = DropLog().record(realLog, realLink(), captured, zone).drops
        assertEquals("время работы PPP совпало с концом инцидента — лишнего не добавлено", 1, drops.size)
        val d = drops.single()
        assertEquals(DropKind.PPP_SESSION, d.kind)
        assertEquals("линия DSL в синхронизации 14 суток", true, d.lineHeld)
        assertEquals("замер линии приложен к свежему обрыву", 184_364L, d.line?.downKbps)
        assertNull("прошлого замера нет — прироста ошибок нет", d.crcDelta)
    }

    @Test fun rereadingAndPartialLogDoNotDuplicate() {
        val first = DropLog().record(realLog, realLink(), captured, zone)
        val again = first.record(realLog, realLink(300), captured + 300, zone)
        assertEquals(first.drops, again.drops)
        // Буфер роутера ушёл вперёд: начало инцидента пропало, остался хвост со вторым «connection lost».
        val tail = realLog.filter { (logEpoch(it, captured, zone) ?: 0) >= at(2026, 11, 22, 17, 16, 40) }
        assertEquals("в хвосте виден свой, более короткий инцидент", at(2026, 11, 22, 17, 16, 46), dropsFromLog(tail, captured, zone).single().lostAt)
        val later = again.record(tail, realLink(3600), captured + 3600, zone)
        assertEquals(first.drops, later.drops)
        // Журнал пуст (очищен), роутер просто работает дальше.
        val empty = later.record(emptyList(), LinkStatus(1_228_689 + 7200, true, 7104 + 7200, true, null), captured + 7200, zone)
        assertEquals(first.drops, empty.drops)
        assertEquals(first.drops, DropLog.fromJson(first.toJson()).drops)
        assertEquals(first.lastLine, DropLog.fromJson(first.toJson()).lastLine)
        assertEquals(DropLog(), DropLog.fromJson("не json"))
    }

    @Test fun flapsWithinFiveMinutesAreOneIncidentOthersSeparate() {
        val now = at(2026, 11, 22, 20, 0)
        val drops = dropsFromLog(
            log(
                lost("Nov 22 17:16:08"), ready("Nov 22 17:16:36"), lost("Nov 22 17:16:46"), ready("Nov 22 17:16:58"),
                lost("Nov 22 17:30:00"), ready("Nov 22 17:30:38"),
            ),
            now, zone,
        )
        assertEquals(2, drops.size)
        assertEquals(2, drops[0].flaps); assertEquals(50L, drops[0].durationSec); assertEquals(DropKind.WAN, drops[0].kind)
        assertEquals(1, drops[1].flaps); assertEquals(38L, drops[1].durationSec)
        // Другой интерфейс — другой инцидент, даже в ту же минуту.
        assertEquals(2, dropsFromLog(log(lost("Nov 22 17:16:08"), lost("Nov 22 17:16:20", "WWAN"), ready("Nov 22 17:16:40", "WWAN")), now, zone).size)
    }

    @Test fun longOutageEndsWithItsRestoreAndNewLossStartsNewIncident() {
        val now = at(2026, 11, 22, 23, 0)
        val long = dropsFromLog(log(lost("Nov 22 10:00:00"), ready("Nov 22 12:30:00")), now, zone).single()
        assertEquals("пока связи нет, восстановление относится к тому же инциденту", 9000L, long.durationSec)
        val two = dropsFromLog(log(lost("Nov 22 10:00:00"), lost("Nov 22 18:00:00"), ready("Nov 22 18:00:30")), now, zone)
        assertEquals(2, two.size)
        assertNull("восстановление первого в журнал не попало", two[0].restoredAt)
        assertEquals(30L, two[1].durationSec)
    }

    @Test fun yearBoundary() {
        val now = at(2027, 1, 2, 9, 0)
        val d = dropsFromLog(log(lost("Dec 31 23:59:50"), ready("Jan  1 00:00:20")), now, zone).single()
        assertEquals("31 декабря, прочитанное в январе, — прошлый год", at(2026, 12, 31, 23, 59, 50), d.lostAt)
        assertEquals(at(2027, 1, 1, 0, 0, 20), d.restoredAt)
        assertEquals(30L, d.durationSec)
        assertNull(logEpoch(log("без даты").single(), now, zone))
    }

    @Test fun missingRestoreIsInferredFromConnectionUptime() {
        val now = at(2026, 11, 22, 20, 0)
        val entries = log(lost("Nov 22 19:00:00"))
        val down = DropLog().record(entries, LinkStatus(500_000, false, 0, true, null), now, zone).drops.single()
        assertNull("связи всё ещё нет", down.restoredAt)
        val up = DropLog().record(entries, LinkStatus(500_000, true, 3540, true, null), now, zone).drops.single()
        assertEquals(at(2026, 11, 22, 19, 1), up.restoredAt)
        assertTrue(up.restoredInferred)
        assertEquals(60L, up.durationSec)
        // Позже в журнале нашлось настоящее восстановление — оно важнее вычисленного.
        val exact = DropLog(listOf(up)).record(log(lost("Nov 22 19:00:00"), ready("Nov 22 19:00:48")), null, now, zone).drops.single()
        assertEquals(48L, exact.durationSec)
        assertFalse(exact.restoredInferred)
    }

    @Test fun reconnectMissingFromLogIsInferredOnce() {
        val now = at(2026, 11, 22, 20, 0)
        val first = DropLog().record(emptyList(), LinkStatus(500_000, true, 3600, true, null), now, zone)
        val d = first.drops.single()
        assertNull(d.lostAt)
        assertEquals(now - 3600, d.restoredAt)
        assertTrue(d.restoredInferred)
        assertEquals(DropKind.PPP_SESSION, d.kind)
        // Следующие чтения (часы телефона и роутера расходятся на секунды) не плодят копий.
        val second = first.record(emptyList(), LinkStatus(500_300, true, 3897, true, null), now + 300, zone)
        assertEquals(first.drops, second.drops)
        assertTrue("после загрузки роутера соединение поднимается штатно — это не обрыв",
            DropLog().record(emptyList(), LinkStatus(4000, true, 3800, true, null), now, zone).drops.isEmpty())
        assertEquals(DropKind.WAN, DropLog().record(emptyList(), LinkStatus(500_000, true, 3600, false, null), now, zone).drops.single().kind)
    }

    @Test fun lineResyncMakesItADslDrop() {
        val now = at(2026, 11, 22, 20, 0)
        val line = realLine.copy(capturedAt = now, uptimeSec = 3600)   // линия поднялась в 19:00:00
        val entries = log(lost("Nov 22 18:58:20"), ready("Nov 22 19:00:40"), lost("Nov 22 19:40:00"), ready("Nov 22 19:40:20"))
        val drops = DropLog().record(entries, LinkStatus(500_000, true, 1180, true, line), now, zone).drops
        assertEquals(2, drops.size)
        assertEquals(DropKind.DSL_LINE, drops[0].kind); assertEquals(false, drops[0].lineHeld)
        assertEquals(DropKind.WAN, drops[1].kind); assertEquals("после пересинхронизации линия держалась", true, drops[1].lineHeld)
        // В журнале ничего нет, но время работы линии меньше времени работы роутера: обрыв линии выведен из него.
        val inferred = DropLog().record(emptyList(), LinkStatus(500_000, true, 3570, true, line), now, zone).drops.single()
        assertEquals(DropKind.DSL_LINE, inferred.kind)
        assertEquals(now - 3600, inferred.restoredAt)
        assertNull(inferred.lostAt)
    }

    @Test fun crcGrowthSincePreviousMeasurement() {
        val now = at(2026, 11, 22, 20, 0)
        val before = realLine.copy(capturedAt = now - 1800, crcDown = 40)
        val d = DropLog(lastLine = before).record(log(lost("Nov 22 19:50:00"), ready("Nov 22 19:50:30")), LinkStatus(1_228_689, true, 570, true, realLine.copy(capturedAt = now)), now, zone).drops.single()
        assertEquals(5L, d.crcDelta)
        assertEquals(now - 1800, d.crcSince)
        // Замер через много часов после обрыва к нему не прикладывается.
        val old = DropLog().record(log(lost("Nov 22 09:00:00"), ready("Nov 22 09:00:30")), LinkStatus(1_228_689, true, 39_570, true, realLine.copy(capturedAt = now)), now, zone).drops.single()
        assertNull(old.line)
    }

    @Test fun pppdCausesAndUnknownWording() {
        val now = at(2026, 11, 22, 20, 0)
        val d = dropsFromLog(
            log(
                "[pppoe] Nov 22 19:00:00 daemon.info pppd: No response to 5 echo-requests",
                "[pppoe] Nov 22 19:00:00 daemon.notice pppd: Serial link appears to be disconnected.",
                "[pppoe] Nov 22 19:00:01 daemon.notice pppd: Json PPP { \"linkname\": \"pppd3\", \"type\": \"ipv4\", \"state\": \"down\" }",
                "[pppoe] Nov 22 19:01:05 daemon.warning pppd: Timeout waiting for PADO packets",
                "[pppoe] Nov 22 19:02:10 daemon.notice pppd: Json PPP { \"type\": \"ipv4\", \"state\": \"up\" }",
            ),
            now, zone,
        ).single()
        assertEquals(DropCause.NO_ECHO, d.cause)
        assertEquals("No response to 5 echo-requests", d.causeText)
        assertEquals(1, d.flaps)
        assertEquals(130L, d.durationSec)
        assertEquals(DropKind.PPP_SESSION, d.kind)
        val other = log(
            "[kernel] Nov 22 19:00:00 kern.err kernel: DSL line retrained",
            "[ppp] Nov 22 19:00:00 daemon.info pppd: PPP session established",
            "[tr69] Nov 22 19:00:00 daemon.info ZTR69: [ERROR ] cwmp_monitor_cwmp(): wan not ready",
            "[dhcpc] Nov 22 19:00:00 user.err dhcp6c: client6_send: transmit failed: Network is unreachable",
            "[system] Nov 22 19:00:00 user.notice zcmdModuleCfg: System: configuration saved",
            // Восстановление без потери инцидент не открывает.
            ready("Nov 22 19:05:00"),
        )
        assertTrue(dropsFromLog(other, now, zone).isEmpty())
        assertEquals(listOf(null, null, null, null, null, false), other.map(::dropLineIsLoss))
        assertEquals(true, dropLineIsLoss(log(lost("Nov 22 19:00:00")).single()))
        assertEquals(DropKind.DSL_LINE, dropsFromLog(log("[xdsl] Nov 22 19:00:00 daemon.notice xdsl: DSL link down", "[xdsl] Nov 22 19:01:00 daemon.notice xdsl: DSL link up"), now, zone).single().kind)
    }

    @Test fun retentionAndLimits() {
        val now = at(2026, 11, 22, 20, 0)
        val old = ConnectionDrop(now - 100 * 86400, now - 100 * 86400 + 30)
        val recent = ConnectionDrop(now - 89 * 86400, now - 89 * 86400 + 30)
        assertEquals(listOf(recent), DropLog(listOf(old, recent)).record(emptyList(), null, now, zone).drops)
        val many = (1..DROP_MAX_STORED + 20).map { ConnectionDrop(now - it * 3600L, now - it * 3600L + 10) }
        val kept = DropLog(many).record(emptyList(), null, now, zone).drops
        assertEquals(DROP_MAX_STORED, kept.size)
        assertEquals("остаются самые новые", now - 3600, kept.last().lostAt)
        assertEquals(1, dropsWithin(kept, now, 3700)); assertEquals(24, dropsWithin(kept, now, 86400))
    }

    @Test fun demoShowsSessionDropAndLineDrop() {
        val now = 1_763_000_000_000L
        val demo = DemoRouter { now }
        val s = demo.snapshot()
        val drops = DropLog().record(demo.systemLog(), LinkStatus(s.uptime, s.internetUp, s.internetUptime, true, demo.lineStats()), now / 1000).drops
        assertEquals(2, drops.size)
        assertEquals(DropKind.DSL_LINE, drops[0].kind); assertEquals(DropCause.NO_ECHO, drops[0].cause); assertEquals(128L, drops[0].durationSec)
        assertEquals(DropKind.PPP_SESSION, drops[1].kind); assertEquals(2, drops[1].flaps); assertEquals(120L, drops[1].durationSec)
        assertEquals(true, drops[1].lineHeld)
        assertNotNull("в демо видны показатели линии", drops[1].line)
    }
}
