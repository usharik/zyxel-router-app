package com.usharik.zyxelrouter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    private fun profile(index: Int, enabled: Boolean) = ParentalProfile(index, "p$index", enabled, emptyList(), emptyList(), emptyList(), false, false)
    private fun parental(max: Int, count: Int) = ParentalControl(true, max, (1..count).map { profile(it, true) })

    @Test fun pluralForms() {
        val f = { n: Int -> plural(n, "устройство", "устройства", "устройств") }
        assertEquals("1 устройство", f(1))
        assertEquals("3 устройства", f(3))
        assertEquals("5 устройств", f(5))
        assertEquals("11 устройств", f(11))
        assertEquals("14 устройств", f(14))
        assertEquals("21 устройство", f(21))
        assertEquals("112 устройств", f(112))
        assertEquals("0 устройств", f(0))
    }

    @Test fun lastSeenBuckets() {
        val now = 1_000_000L
        assertEquals("давно", lastSeenLabel(0, now))
        assertEquals("только что", lastSeenLabel(now - 30, now))
        assertEquals("5 мин назад", lastSeenLabel(now - 300, now))
        assertEquals("2 ч назад", lastSeenLabel(now - 7200, now))
        assertEquals("1 день назад", lastSeenLabel(now - 86400, now))
        assertEquals("3 дня назад", lastSeenLabel(now - 3 * 86400, now))
    }

    @Test fun inactiveDeviceKeepsItsLastSeen() {
        assertEquals(500L, mergedLastSeen(online = true, previous = 100, now = 500))
        assertEquals(100L, mergedLastSeen(online = false, previous = 100, now = 500))
        assertEquals(0L, mergedLastSeen(online = false, previous = 0, now = 500))
    }

    @Test fun pollKeepsWriteErrorAndRebootNotice() {
        assertNull("успешный опрос убирает обычную ошибку", bannerAfterPoll(null, "связь", false, null))
        assertEquals("опрос не стирает ошибку записи", "запись", bannerAfterPoll(null, "запись", false, "запись"))
        assertEquals("новая ошибка связи видна", "связь", bannerAfterPoll("связь", "запись", false, "запись"))
        assertNull("закрытый баннер не возвращается", bannerAfterPoll(null, null, false, "запись"))
        assertEquals("перезагрузка не стирается", "ребут", bannerAfterPoll(null, "ребут", true, null))
        assertEquals("перезагрузка не заменяется ошибкой", "ребут", bannerAfterPoll("связь", "ребут", true, null))
    }

    @Test fun timeFormatting() {
        assertEquals("09:05", formatTime(9, 5))
        assertEquals("23:59", formatTime(23, 59))
    }

    @Test fun profileValidationOrder() {
        assertEquals("Укажите название профиля.", validateProfile("  ", 1, 1, "09:00", "10:00"))
        assertEquals("Выберите хотя бы одно устройство.", validateProfile("a", 0, 1, "09:00", "10:00"))
        assertEquals("Не больше 32 устройств.", validateProfile("a", 33, 1, "09:00", "10:00"))
        assertEquals("Выберите хотя бы один день.", validateProfile("a", 1, 0, "09:00", "10:00"))
        assertEquals("Время в формате ЧЧ:ММ.", validateProfile("a", 1, 1, "x", "10:00"))
        assertTrue(validateProfile("a", 1, 1, "10:00", "10:00")!!.startsWith("Начало"))
        assertNull(validateProfile("a", 1, 1, "9:00", "10:00"))     // сравнение по минутам, а не строк
        assertNull(validateProfile("a", 32, 7, "00:00", "24:00"))
    }

    @Test fun profileLimit() {
        assertTrue(canAddProfile(parental(12, 11)))
        assertFalse(canAddProfile(parental(12, 12)))
        assertFalse(canAddProfile(parental(12, 13)))
        assertTrue("лимит неизвестен", canAddProfile(parental(0, 50)))
    }

    @Test fun toggleTouchesOnlyThatProfile() {
        val pc = parental(12, 3).withProfileEnabled(2, false)
        assertEquals(listOf(true, false, true), pc.profiles.map { it.enabled })
        assertEquals(listOf(true, true, true), pc.withProfileEnabled(2, true).profiles.map { it.enabled })
        assertEquals(listOf(true, false, true), pc.withProfileEnabled(99, false).profiles.map { it.enabled })
    }

    @Test fun recoveryRetriesAfterRelogin() {
        var calls = 0; var relogins = 0
        val result = withSessionRecovery(true, { relogins++ }) {
            if (++calls == 1) throw RouterException.SessionExpired
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertEquals(1, relogins)
    }

    @Test fun recoverySkippedWhenWriteSucceeds() {
        var relogins = 0
        assertEquals(1, withSessionRecovery(true, { relogins++ }) { 1 })
        assertEquals(0, relogins)
    }

    @Test fun recoveryDoesNotRepeatNonIdempotentWrite() {
        var calls = 0; var relogins = 0
        val e = assertThrows(RouterException.Failed::class.java) {
            withSessionRecovery<Unit>(false, { relogins++ }) { calls++; throw RouterException.SessionExpired }
        }
        assertEquals(1, calls)
        assertEquals(1, relogins)
        assertTrue(e.message!!.contains("Повторите"))
    }

    @Test fun recoveryPropagatesRejectedRelogin() {
        var calls = 0
        assertThrows(RouterException.InvalidCredentials::class.java) {
            withSessionRecovery<Unit>(true, { throw RouterException.InvalidCredentials }) { calls++; throw RouterException.SessionExpired }
        }
        assertEquals("после отказа нельзя повторять запись", 1, calls)
    }

    @Test fun recoveryRetriesOnlyOnce() {
        var calls = 0
        assertThrows(RouterException.SessionExpired::class.java) {
            withSessionRecovery<Unit>(true, {}) { calls++; throw RouterException.SessionExpired }
        }
        assertEquals(2, calls)
    }

    @Test fun otherErrorsAreNotRecovered() {
        var relogins = 0
        assertThrows(RouterException.Failed::class.java) {
            withSessionRecovery<Unit>(true, { relogins++ }) { throw RouterException.Failed("x") }
        }
        assertEquals(0, relogins)
    }

    @Test fun daysStringBecomesArray() {
        val arr = daysAsArray(JSONObject().put("Days", "Mon, Tue,,Fri")).get("Days") as JSONArray
        assertEquals(listOf("Mon", "Tue", "Fri"), (0 until arr.length()).map { arr.getString(it) })
    }

    @Test fun daysArrayAndMissingDaysUntouched() {
        val existing = JSONArray(listOf("Sat"))
        assertSame(existing, daysAsArray(JSONObject().put("Days", existing)).get("Days"))
        assertFalse(daysAsArray(JSONObject()).has("Days"))
    }

    @Test fun emptyDaysStringBecomesEmptyArray() {
        assertEquals(0, (daysAsArray(JSONObject().put("Days", "")).get("Days") as JSONArray).length())
    }
}
