package com.usharik.zyxelrouter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
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

/** Проверяет на устройстве, что элементы не вылезают за границы на узких экранах и с крупным шрифтом. */
@RunWith(Parameterized::class)
class LayoutFitTest(private val widthDp: Int, private val fontScale: Float) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}dp x{1}")
        fun cases(): List<Array<Any>> = listOf(232, 272, 400).flatMap { w -> listOf(1f, 1.3f, 2f).map { arrayOf<Any>(w, it) } }
    }

    @get:Rule val rule = createComposeRule()
    private val width: Dp get() = widthDp.dp
    private val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme { Box(Modifier.width(width).testTag("parent")) { content() } }
        }
    }

    private fun widthPx(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().size.width

    @Test fun allSevenDaysFitAndAreNotClipped() {
        show { DaySelector(emptyList()) {} }
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

    @Test fun daysToggleAndShareTheRow() {
        val selected = mutableStateListOf<String>()
        show { DaySelector(selected) { c -> if (c in selected) selected.remove(c) else selected.add(c) } }
        rule.onNodeWithText("Вс").assertIsOff().performClick()
        rule.onNodeWithText("Вс").assertIsOn()
        assertEquals(listOf("Sun"), selected.toList())
        val tops = days.map { rule.onNodeWithText(it).getBoundsInRoot().top }.toSet()
        assertEquals("все дни в одном ряду", 1, tops.size)
    }

    @Test fun longDeviceNameDoesNotPushStatusOut() {
        show { ListRow(Icons.Default.Wifi, true, "Очень-длинное-имя-устройства-".repeat(4), true, "Телефон · 192.168.1.2", "В сети", "Very-Long-Network-Name-5GHz", blocked = true) }
        val parent = rule.onNodeWithTag("parent").getBoundsInRoot()
        val status = rule.onNodeWithText("В сети").getBoundsInRoot()
        assertTrue("статус за правым краем", status.right <= parent.right)
        assertTrue("статусу не осталось ширины", status.right - status.left > 0.dp)
        assertTrue("метка потеряна", rule.onNodeWithText("заблок.").getBoundsInRoot().let { it.right <= parent.right && it.right - it.left > 0.dp })
    }

    @Test fun timeButtonsAreNotClipped() {
        show { TimeRangeButtons("00:00", "23:59", {}, {}) }
        val half = widthPx("parent") / 2
        listOf("с 00:00", "до 23:59").forEach {
            assertTrue("$it шире кнопки", rule.onNodeWithText(it).fetchSemanticsNode().size.width < half)
        }
        val parent = rule.onNodeWithTag("parent").getBoundsInRoot()
        assertTrue(rule.onNodeWithText("до 23:59").getBoundsInRoot().right <= parent.right)
    }

    @Test fun fittedStyleKeepsLongestLabelOnOneLine() {
        val available = width / 6
        val labels = listOf("Обзор", "Устройства", "Маршруты")
        show {
            val style = fittedStyle(labels, available, MaterialTheme.typography.labelMedium)
            Text(labels[1], Modifier.testTag("label"), style = style, maxLines = 1, softWrap = false)
        }
        val px = with(rule.density) { available.toPx() }
        assertTrue("подпись шире доступного", widthPx("label") <= px + 1)
    }
}
