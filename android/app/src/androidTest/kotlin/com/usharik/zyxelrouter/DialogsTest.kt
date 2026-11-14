package com.usharik.zyxelrouter

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Окна, где ошибка стоила бы пользователю данных: «Поделиться сетью» и редактор профиля, на демо-роутере. */
@RunWith(AndroidJUnit4::class)
class DialogsTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: RouterViewModel
    private val ui = UiActivity()

    @Before fun demo() {
        listOf("router", "devices").forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Без сети: настоящий клиент обратился бы к роутеру 192.168.1.1 в сети эмулятора.
            vm = RouterViewModel(app) { _, _ -> DemoRouter() }
            vm.startDemo()
        }
    }

    private fun string(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test fun wifiShareShowsQrAndCleansUp() {
        val network = vm.state.value.snapshot!!.wifi.first()
        var open by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalUiActivity provides ui) {
                MaterialTheme { if (open) WifiShareDialog(network, vm) { open = false } }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(string(R.string.share_wifi_qr_description, network.ssid)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(string(R.string.share_wifi_qr_description, network.ssid)).assertIsDisplayed()
        assertEquals("окно считается открытым — реклама не появится", 1, ui.overlays)
        rule.onNodeWithText("demo-home-24").assertDoesNotExist()
        rule.onNodeWithContentDescription(string(R.string.show_password)).performClick()
        rule.onNodeWithText("demo-home-24").assertIsDisplayed()
        // Картинка для отправки удаляется, когда окно закрыто.
        val png = File(File(app.cacheDir, ZyxelApp.SHARED_CACHE_DIR), "wifi-qr.png").apply { parentFile?.mkdirs(); writeText("x") }
        rule.onNodeWithText(string(R.string.close)).performClick()
        rule.waitForIdle()
        assertFalse(png.exists())
        assertEquals(0, ui.overlays)
    }

    @Test fun manualMacIsSavedWithoutPressingPlus() {
        var open by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            val s by vm.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalUiActivity provides ui) {
                MaterialTheme { if (open) ProfileEditorDialog(null, emptyList(), s.saveState, vm) { open = false } }
            }
        }
        rule.onNode(hasSetTextAction() and hasText(string(R.string.field_name))).performTextInput("Homework")
        rule.onNode(hasSetTextAction() and hasText(string(R.string.field_manual_mac))).performTextInput(" aabb.ccdd.eeff ")
        rule.onNodeWithText(dayLabel("Mon", java.util.Locale.forLanguageTag(string(R.string.locale_tag)))).performClick()
        rule.onNodeWithText(string(R.string.save)).performClick()
        // Запись идёт в фоновом потоке; окно закрывается, когда ViewModel сообщит об успехе.
        rule.waitUntil(10_000) { !open }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.load(Section.PARENTAL) }
        rule.waitUntil(5_000) { vm.state.value.parental?.profiles?.any { it.name == "Homework" } == true }
        val saved = vm.state.value.parental!!.profiles.first { it.name == "Homework" }
        assertEquals(listOf("AA-BB-CC-DD-EE-FF"), saved.macs)
        assertEquals("новый профиль — весь день, до 24:00", listOf("00:00-24:00"), saved.times)
    }

    @Test fun badManualMacBlocksSaving() {
        rule.setContent {
            val s by vm.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalUiActivity provides ui) {
                MaterialTheme { ProfileEditorDialog(null, emptyList(), s.saveState, vm) {} }
            }
        }
        rule.onNode(hasSetTextAction() and hasText(string(R.string.field_name))).performTextInput("X")
        rule.onNode(hasSetTextAction() and hasText(string(R.string.field_manual_mac))).performTextInput("not-a-mac")
        rule.onNodeWithText(string(R.string.save)).performClick()
        rule.onNodeWithText(string(R.string.err_profile_bad_mac)).assertIsDisplayed()
        assertTrue(vm.state.value.saveState == SaveState.Idle)
    }

    @Test fun cancelDisabledWhileSaving() {
        rule.setContent {
            CompositionLocalProvider(LocalUiActivity provides ui) {
                MaterialTheme { ProfileEditorDialog(null, emptyList(), SaveState.InFlight, vm) {} }
            }
        }
        rule.onNodeWithText(string(R.string.cancel)).assertIsNotEnabled()
    }

    @Test fun loginProgressShowsStepsSlowHintAndCancel() {
        var cancelled = false
        rule.mainClock.autoAdvance = false
        rule.setContent { MaterialTheme { LoginProgress(LoginStep.SIGNING_IN) { cancelled = true } } }
        rule.mainClock.advanceTimeByFrame()
        listOf(R.string.login_step_connecting, R.string.login_step_signing_in, R.string.login_step_loading).forEach { rule.onNodeWithText(string(it)).assertIsDisplayed() }
        rule.onNodeWithContentDescription(string(R.string.step_done)).assertIsDisplayed()
        rule.onNodeWithContentDescription(string(R.string.step_current)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.router_slow)).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(5_000)
        rule.onNodeWithText(string(R.string.router_slow)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.cancel)).performClick()
        assertTrue(cancelled)
    }

    @Test fun deviceCardExplainsPrivateAddressAndShowsWhatTheDeviceSaid() {
        val d = offlineDevice("DA:A1:19:5B:10:08", "", DeviceType.UNKNOWN, "Wi-Fi 5GHz", 0)
        val id = NetworkIdentity(name = "Anna's iPhone", hostName = "Annas-iPhone", manufacturer = "Apple", model = "iPhone", services = setOf(NetService.AIRPLAY, NetService.APPLE))
        rule.setContent { CompositionLocalProvider(LocalUiActivity provides ui) { MaterialTheme { DeviceDetailsDialog(d, null, id) {} } } }
        rule.onNodeWithText(string(R.string.private_mac)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.private_mac_hint)).assertIsDisplayed()
        rule.onNodeWithText("Anna's iPhone").assertIsDisplayed()
        rule.onNodeWithText("Apple iPhone").assertIsDisplayed()
        rule.onNodeWithText("AirPlay, " + string(R.string.service_apple)).assertIsDisplayed()
    }
}
