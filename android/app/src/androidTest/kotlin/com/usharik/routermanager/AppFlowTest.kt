package com.usharik.routermanager

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Приложение целиком, как его увидит проверяющий Google Play: запуск MainActivity, демо-режим, все вкладки, меню,
 * «О приложении» и выход. Роутер и форма согласия подменены до запуска: тест не ходит в сеть эмулятора.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<ZyxelApp>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun launch() {
        listOf("router", "devices").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        app.connectRouter = { _, _ -> DemoRouter() }
        app.ads.consent.formsEnabled = false
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() {
        scenario?.close()
        app.connectRouter = ::RouterClient
        app.ads.consent.formsEnabled = true
    }

    private fun s(id: Int) = app.getString(id)

    private fun waitForText(text: String) =
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun demoWalkthrough() {
        waitForText(s(R.string.demo_try))
        rule.onNodeWithText(s(R.string.demo_try)).performClick()
        waitForText(s(R.string.demo_label))

        val tabs = listOf(
            R.string.tab_devices to "Laptop",
            R.string.tab_wifi to "Home-5G",
            R.string.tab_parental to "TV at night",
            R.string.tab_routes to "office",
            R.string.tab_log to s(R.string.log_by_period),
            R.string.tab_overview to "203.0.113.7",
        )
        for ((tab, expected) in tabs) {
            rule.onNode(hasText(s(tab)) and androidx.compose.ui.test.hasClickAction()).performClick()
            waitForText(expected)
        }

        rule.onNodeWithContentDescription(s(R.string.more_options)).performClick()
        rule.onNodeWithText(s(R.string.menu_about)).performClick()
        waitForText(s(R.string.privacy_policy))
        rule.onNodeWithText(s(R.string.close)).performClick()

        rule.onNodeWithContentDescription(s(R.string.more_options)).performClick()
        rule.onNodeWithText(s(R.string.demo_exit)).performClick()
        waitForText(s(R.string.sign_in))
    }

    /** Обрывы связи в демо-режиме: блок на «Журнале», подробности со строками роутера, список, подсветка записей, «Обзор». */
    @Test fun demoShowsConnectionDrops() {
        waitForText(s(R.string.demo_try))
        rule.onNodeWithText(s(R.string.demo_try)).performClick()
        waitForText(s(R.string.demo_label))
        fun tab(id: Int) = rule.onNode(hasText(s(id)) and hasClickAction()).performClick()
        tab(R.string.tab_log)
        waitForText(s(R.string.drops_title))
        rule.onNodeWithTag("drops_card").assertIsDisplayed()
        // Два обрыва из демо-журнала; свежий — обрыв сессии PPP с двумя потерями подряд, старый — обрыв линии DSL.
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("drop").fetchSemanticsNodes().size == 2 }
        rule.onAllNodesWithTag("drop")[0].assert(hasText(s(R.string.drop_kind_ppp), substring = true)).assert(hasText("VDSL", substring = true))
        rule.onAllNodesWithTag("drop")[1].assert(hasText(s(R.string.drop_kind_dsl), substring = true)).assert(hasText(s(R.string.drop_cause_no_echo)))
        rule.onAllNodesWithTag("drop")[0].performClick()
        waitForText(s(R.string.drop_router_log))
        rule.onNodeWithText(s(R.string.drop_line_held)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.drop_snr_margin)).assertExists()
        rule.onNodeWithText("Wan interface VDSL connection lost", substring = true).assertExists()
        rule.onNodeWithText(s(R.string.close)).performClick()

        // Отдельный список обрывов.
        tab(R.string.log_drops)
        waitForText(s(R.string.drops_hint))
        assertEquals(2, rule.onAllNodesWithTag("drop").fetchSemanticsNodes().size)

        // В записях строки о связи подписаны и отбираются фильтром.
        tab(R.string.log_entries)
        // Фильтр — последний в прокручиваемом ряду уровней важности.
        rule.onNode(hasText(s(R.string.log_filter_link)) and hasClickAction()).performScrollTo().performClick()
        waitForText(s(R.string.label_link_lost))
        assertTrue(rule.onAllNodesWithText(s(R.string.label_link_restored)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("посторонних записей под фильтром нет", rule.onAllNodesWithText("dnsmasq", substring = true).fetchSemanticsNodes().isEmpty())

        tab(R.string.tab_overview)
        waitForText(s(R.string.drops_title))
    }
}
