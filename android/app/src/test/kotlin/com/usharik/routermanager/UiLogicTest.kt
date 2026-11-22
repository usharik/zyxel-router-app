package com.usharik.routermanager

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

    @Test fun lastSeenBuckets() {
        val now = 1_000_000L
        assertEquals(Ago.LongAgo, lastSeenAgo(0, now))
        assertEquals(Ago.JustNow, lastSeenAgo(now - 30, now))
        assertEquals(Ago.Minutes(5), lastSeenAgo(now - 300, now))
        assertEquals(Ago.Hours(2), lastSeenAgo(now - 7200, now))
        assertEquals(Ago.Days(1), lastSeenAgo(now - 86400, now))
        assertEquals(Ago.Days(3), lastSeenAgo(now - 3 * 86400, now))
    }

    @Test fun dayLabelsFollowLocale() {
        assertEquals("Mon", dayLabel("Mon", java.util.Locale.ENGLISH))
        assertEquals("Пн", dayLabel("Mon", java.util.Locale.forLanguageTag("ru")))
        assertEquals("Вс", dayLabel("Sun", java.util.Locale.forLanguageTag("ru")))
        assertEquals("Mo", dayLabel("Mon", java.util.Locale.GERMAN))
        assertEquals("Xyz", dayLabel("Xyz", java.util.Locale.ENGLISH))
        assertEquals(7, DayCodes.map { dayLabel(it, java.util.Locale.forLanguageTag("uk")) }.toSet().size)
        assertEquals("Po", dayLabel("Mon", java.util.Locale.forLanguageTag("cs")))
        assertEquals("Январь", monthName(1, java.util.Locale.forLanguageTag("ru")))
        assertEquals("Leden", monthName(1, java.util.Locale.forLanguageTag("cs")))
        assertEquals("März", monthName(3, java.util.Locale.GERMAN))
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
        assertEquals(ProfileError.NO_NAME, validateProfile("  ", 1, 1, "09:00", "10:00"))
        assertEquals(ProfileError.NO_DEVICES, validateProfile("a", 0, 1, "09:00", "10:00"))
        assertEquals(ProfileError.TOO_MANY_DEVICES, validateProfile("a", 33, 1, "09:00", "10:00"))
        assertEquals(ProfileError.NO_DAYS, validateProfile("a", 1, 0, "09:00", "10:00"))
        assertEquals(ProfileError.BAD_TIME, validateProfile("a", 1, 1, "x", "10:00"))
        assertEquals(ProfileError.START_NOT_BEFORE_END, validateProfile("a", 1, 1, "10:00", "10:00"))
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
        assertThrows(RouterException.SessionRefreshed::class.java) {
            withSessionRecovery<Unit>(false, { relogins++ }) { calls++; throw RouterException.SessionExpired }
        }
        assertEquals(1, calls)
        assertEquals(1, relogins)
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
        assertThrows(RouterException.ProfileNotFound::class.java) {
            withSessionRecovery<Unit>(true, { relogins++ }) { throw RouterException.ProfileNotFound }
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

    @Test fun lastSeenBoundaries() {
        val now = 1_000_000L
        assertEquals(Ago.JustNow, lastSeenAgo(now - 89, now))
        assertEquals(Ago.Minutes(1), lastSeenAgo(now - 90, now))
        assertEquals(Ago.Minutes(59), lastSeenAgo(now - 3599, now))
        assertEquals(Ago.Hours(1), lastSeenAgo(now - 3600, now))
        assertEquals(Ago.Days(1), lastSeenAgo(now - 86400, now))
    }

    @Test fun rangeUntilMidnight() {
        assertNull("конец 00:00 — до полуночи", validateProfile("n", 1, 1, "22:00", "00:00"))
        assertEquals("22:00-24:00", profileTimeRange("22:00", "0:00"))
        assertEquals("09:00-17:00", profileTimeRange("9:00", "17:00"))
        assertNull(profileTimeRange("25:00", "17:00"))
        assertNull("00:00–00:00 — весь день", validateProfile("n", 1, 1, "00:00", "00:00"))
        assertEquals(ProfileError.START_NOT_BEFORE_END, validateProfile("n", 1, 1, "10:00", "09:00"))
    }

    @Test fun multipleRangesKeptOnlyWhenFirstUnchanged() {
        val original = listOf("9:00-12:00", "14:00-17:00")
        assertEquals("сравнение нормализованное", "09:00-12:00,14:00-17:00", timeRangesToSave(original, "09:00-12:00"))
        assertEquals("08:00-12:00", timeRangesToSave(original, "08:00-12:00"))
        assertEquals("08:00-12:00", timeRangesToSave(listOf("09:00-12:00"), "08:00-12:00"))
        assertEquals("10:00-11:00", timeRangesToSave(emptyList(), "10:00-11:00"))
    }

    @Test fun backgroundReloginStopsOnAnyRouterRefusal() {
        assertTrue("роутер недоступен — пароль не отправлялся", keepPollingAfterReloginFailure(RouterException.Unreachable))
        for (e in listOf(RouterException.InvalidCredentials, RouterException.LoginRejected("x"), RouterException.Locked(60),
            RouterException.TooManySessions, RouterException.DuplicateSession, RouterException.BadLoginData, RouterException.LoginUnreadable, RouterException.SessionExpired)) {
            assertFalse(e.toString(), keepPollingAfterReloginFailure(e))
        }
    }

    @Test fun loginFailuresEndTheSession() {
        assertTrue(isLoginFailure(RouterException.LoginRejected("Wrong")))
        assertTrue(isLoginFailure(RouterException.CertificateChanged("AB")))
        assertFalse(isLoginFailure(RouterException.Unreachable))
        assertFalse(isLoginFailure(RouterException.NoData("rip", 404)))
        assertFalse(isLoginFailure(RouterException.SessionRefreshed))
        // Оборванный ответ на чтение раздела — ошибка запроса, а нечитаемый ответ на вход — отказ.
        assertFalse(isLoginFailure(RouterException.BadResponse))
        assertTrue(isLoginFailure(RouterException.LoginUnreadable))
        assertTrue("снимок после удачного входа не прочитался — опрос продолжается", keepPollingAfterReloginFailure(RouterException.BadResponse))
    }

    @Test fun logSeverityGroups() {
        assertEquals("err", severityGroup("crit"))
        assertEquals("err", severityGroup("emerg"))
        assertEquals("warning", severityGroup("warn"))
        assertEquals("info", severityGroup("INFO"))
    }
}
