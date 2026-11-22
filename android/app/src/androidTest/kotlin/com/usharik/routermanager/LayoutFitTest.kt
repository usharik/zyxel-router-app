package com.usharik.routermanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.test.platform.app.InstrumentationRegistry
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Проверяет на устройстве, что элементы не вылезают за границы на узких экранах, с крупным шрифтом и на всех языках. */
@RunWith(Parameterized::class)
class LayoutFitTest(private val widthDp: Int, private val fontScale: Float, private val language: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}dp x{1} {2}")
        fun cases(): List<Array<Any>> = listOf("en", "ru", "uk", "cs", "de").flatMap { lang ->
            listOf(232, 272, 400).flatMap { w -> listOf(1f, 1.3f, 2f).map { arrayOf<Any>(w, it, lang) } }
        }
    }

    @get:Rule val rule = createComposeRule()
    private val width: Dp get() = widthDp.dp
    private val testLocale: Locale = Locale.forLanguageTag(language)
    private val res: Resources by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(testLocale) }).resources
    }
    private val days get() = DayCodes.map { dayLabel(it, testLocale) }

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        val density = LocalDensity.current
        val context = LocalContext.current
        val config = Configuration(LocalConfiguration.current).apply { setLocale(testLocale) }
        val localized = remember(language) { context.createConfigurationContext(config) }
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalConfiguration provides config,
            LocalContext provides localized,
        ) {
            MaterialTheme { Box(Modifier.width(width).testTag("parent")) { content() } }
        }
    }

    private fun widthPx(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().size.width

    @Test fun allSevenDaysFitAndAreNotClipped() {
        var seen: Locale? = null
        show { seen = currentLocale(); DaySelector(emptyList()) {} }
        assertEquals("язык композиции", language, seen?.language)
        val parent = rule.onNodeWithTag("parent").getBoundsInRoot()
        val cell = widthPx("parent").toFloat() / days.size
        days.forEach { d ->
            val node = rule.onNodeWithText(d)
            val bounds = node.getBoundsInRoot()
            assertTrue("$d за правым краем", bounds.right <= parent.right + 1.dp)
            assertTrue("$d за левым краем", bounds.left >= parent.left - 1.dp)
            assertTrue("$d шире ячейки", node.fetchSemanticsNode().size.width <= cell)
        }
    }

    /** На Android java.time для части форм возвращает номер вместо названия — подписи должны быть словами. */
    @Test fun dayAndMonthNamesAreWords() {
        days.forEach { assertTrue("день «$it» — не название", it.any(Char::isLetter)) }
        (1..12).map { monthName(it, testLocale) }.forEach { assertTrue("месяц «$it» — не название", it.any(Char::isLetter)) }
        // Чешский шаблон даты цифровой («12. 1.»): проверяем только, что день и месяц на месте.
        dayMonthText(12, 1, testLocale).let { assertTrue("дата «$it»", it.contains("12") && it != "—") }
    }

    @Test fun daysToggleAndShareTheRow() {
        val selected = mutableStateListOf<String>()
        show { DaySelector(selected) { c -> if (c in selected) selected.remove(c) else selected.add(c) } }
        val sunday = dayLabel("Sun", testLocale)
        rule.onNodeWithText(sunday).assertIsOff().performClick()
        rule.onNodeWithText(sunday).assertIsOn()
        assertEquals(listOf("Sun"), selected.toList())
        val tops = days.map { rule.onNodeWithText(it).getBoundsInRoot().top }.toSet()
        assertEquals("все дни в одном ряду", 1, tops.size)
    }

    @Test fun longDeviceNameDoesNotPushStatusOut() {
        val online = res.getString(R.string.online)
        show { ListRow(Icons.Default.Wifi, true, "Очень-длинное-имя-устройства-".repeat(4), true, "${res.getString(R.string.type_phone)} · 192.168.1.2", online, "Very-Long-Network-Name-5GHz", blocked = true) }
        val parent = rule.onNodeWithTag("parent").getBoundsInRoot()
        val status = rule.onNodeWithText(online).getBoundsInRoot()
        assertTrue("статус за правым краем", status.right <= parent.right)
        assertTrue("статусу не осталось ширины", status.right - status.left > 0.dp)
        assertTrue("метка потеряна", rule.onNodeWithText(res.getString(R.string.label_blocked)).getBoundsInRoot().let { it.right <= parent.right && it.right - it.left > 0.dp })
    }

    @Test fun timeButtonsAreNotClipped() {
        show { TimeRangeButtons("00:00", "23:59", {}, {}) }
        val half = widthPx("parent") / 2
        val from = res.getString(R.string.time_from, "00:00")
        val to = res.getString(R.string.time_to, "23:59")
        listOf(from, to).forEach {
            assertTrue("$it шире кнопки", rule.onNodeWithText(it).fetchSemanticsNode().size.width < half)
        }
        val parent = rule.onNodeWithTag("parent").getBoundsInRoot()
        assertTrue(rule.onNodeWithText(to).getBoundsInRoot().right <= parent.right)
    }

    @Test fun fittedStyleKeepsLongestLabelOnOneLine() {
        val available = width / 6
        val labels = listOf(R.string.tab_overview, R.string.tab_devices, R.string.tab_wifi, R.string.tab_parental, R.string.tab_routes, R.string.tab_log).map(res::getString)
        val longest = labels.maxBy { it.length }
        show {
            val style = fittedStyle(labels, available, MaterialTheme.typography.labelMedium)
            Text(longest, Modifier.testTag("label"), style = style, maxLines = 1, softWrap = false)
        }
        val px = with(rule.density) { available.toPx() }
        assertTrue("подпись шире доступного", widthPx("label") <= px + 1)
    }

    private val dropNow = 1_795_000_000L

    /** Подпись внутри строки обрыва (строка — одна кнопка со слитой семантикой, подписи видны в неслитом дереве). */
    private fun assertFits(text: String) {
        val parent = rule.onNodeWithTag("parent").getBoundsInRoot()
        val bounds = rule.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("«$text» за правым краем", bounds.right <= parent.right + 1.dp)
        assertTrue("«$text» без ширины", bounds.right - bounds.left > 0.dp)
    }

    /** Блок «Обрывы связи»: длинные подписи на всех языках не выталкивают длительность за край. */
    @Test fun connectionDropsCardFits() {
        val line = LineStats(dropNow, true, "VDSL2", 184_364, 55_613, 6.3, 7.5, 9.7, 1.9, 12.3, 7.5, 45, 0, 17_390_643, 3449, 1_228_521)
        val drop = ConnectionDrop(dropNow - 7200, dropNow - 3540, restoredInferred = true, kind = DropKind.DSL_LINE, iface = "VDSL", flaps = 12, cause = DropCause.NO_ECHO, lineHeld = false, line = line)
        show { DropsSummaryCard(listOf(drop), dropNow, {}) {} }
        assertFits(res.getString(R.string.drop_approx, res.getString(R.string.duration_hm, 1, 1)))
        assertFits(res.getString(R.string.drops_title))
        assertFits(res.getString(R.string.drops_counts, 1, 1, 1))
        val cause = rule.onNodeWithText(res.getString(R.string.drop_cause_no_echo), useUnmergedTree = true).getBoundsInRoot()
        assertTrue("причина за правым краем", cause.right <= rule.onNodeWithTag("parent").getBoundsInRoot().right)
        assertTrue("названию обрыва не осталось ширины", cause.right - cause.left > 40.dp)
    }

    /** Обрыв без восстановления: состояние вместо длительности помещается и у последнего, и у старого обрыва. */
    @Test fun unrestoredDropRowFits() {
        val drop = ConnectionDrop(dropNow - 90_000, null, kind = DropKind.PPP_SESSION, iface = "VDSL", flaps = 3)
        show { androidx.compose.foundation.layout.Column { DropRow(drop, latest = true) {}; DropRow(drop.copy(lostAt = dropNow - 180_000), latest = false) {} } }
        assertFits(res.getString(R.string.drop_ongoing))
        assertFits(res.getString(R.string.drop_not_logged))
    }
}
