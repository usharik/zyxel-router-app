package com.usharik.zyxelrouter

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
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
}
