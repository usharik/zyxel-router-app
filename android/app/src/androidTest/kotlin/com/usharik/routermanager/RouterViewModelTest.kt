package com.usharik.routermanager

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Сессия с роутером глазами ViewModel: подставной [RouterApi] вместо сети, настоящие настройки и Keystore.
 * Проверяет то, что опасно для роутера (повторные входы, отправка пароля) и что подвешивает интерфейс.
 */
@RunWith(AndroidJUnit4::class)
class RouterViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val host = "192.168.1.1"
    private lateinit var vm: RouterViewModel

    /** Роутер, поведение которого задаёт тест. */
    private class FakeRouter : RouterApi {
        val logins = AtomicInteger()
        /** Хранилище отпечатков, которое ViewModel передала клиенту; при удачном входе фиксируется, как в RouterClient. */
        @Volatile var pins: CertificatePins? = null
        @Volatile var loginError: RouterException? = null
        @Volatile var snapshotError: RouterException? = null
        @Volatile var routingError: RouterException? = null
        @Volatile var saveError: RouterException? = null
        /** Вход ждёт, пока тест его не отпустит: так видно шаги и можно нажать «Отмена». */
        @Volatile var loginGate: java.util.concurrent.CountDownLatch? = null
        /** Запись родительского контроля ждёт, пока тест её не отпустит. */
        @Volatile var writeGate: java.util.concurrent.CountDownLatch? = null
        /** Одно следующее чтение родительского контроля снимает состояние и ждёт отпускания перед ответом: устаревший ответ. */
        @Volatile var staleReadGate: java.util.concurrent.CountDownLatch? = null
        @Volatile var staleReadTaken: java.util.concurrent.CountDownLatch? = null
        val steps = java.util.Collections.synchronizedList(mutableListOf<LoginStep>())
        val logouts = AtomicInteger()
        @Volatile var closed = false
        private val demo = DemoRouter()
        override val sessionGeneration get() = logins.get()
        override fun basicInfo() = RouterBasic("VMG8623-T50B", "test", "en")
        override fun login(username: String, password: String, onStep: (LoginStep) -> Unit) {
            onStep(LoginStep.CONNECTING).also { steps += LoginStep.CONNECTING }
            onStep(LoginStep.SIGNING_IN).also { steps += LoginStep.SIGNING_IN }
            loginGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            logins.incrementAndGet()
            loginError?.let { throw it }
            (pins as? TrustedPins)?.commit()
        }
        override fun reloginIfUnchanged(generation: Int, username: String, password: String) {
            if (generation == sessionGeneration) login(username, password)
        }
        override fun snapshot(): RouterSnapshot { if (closed) throw RouterException.SessionExpired; snapshotError?.let { throw it }; return demo.snapshot() }
        override fun wifiCredentials(network: WifiNetwork) = demo.wifiCredentials(network)
        override fun parentalControl(): ParentalControl {
            val pc = demo.parentalControl()
            synchronized(this) { staleReadGate.also { staleReadGate = null } }?.let { gate ->
                staleReadTaken?.countDown()
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            return pc
        }
        override fun setParentalEnabled(on: Boolean) = demo.setParentalEnabled(on)
        override fun setProfileEnabled(index: Int, on: Boolean) {
            writeGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            demo.setProfileEnabled(index, on)
        }
        override fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean) {
            saveError?.let { throw it }
            demo.saveProfile(index, name, macs, days, timeRangeList, enabled)
        }
        override fun deleteProfile(index: Int) = demo.deleteProfile(index)
        override fun routing(onProgress: (done: Int) -> Unit): Routing {
            routingError?.let { throw it }
            (1..ROUTING_PARTS).forEach(onProgress)
            return demo.routing()
        }
        override fun systemLog(security: Boolean) = demo.systemLog(security)
        override fun reboot() = Unit
        override fun logout() { logouts.incrementAndGet() }
        override fun close() { closed = true }
    }

    private val router = FakeRouter()

    /** Сеть, ответы которой задаёт тест. */
    private class FakeDiscovery : LocalDiscovery {
        @Volatile var result = DiscoveryResult(emptyMap())
        @Volatile var delayMs = 0L
        val asked = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        override suspend fun discover(ips: List<String>): DiscoveryResult { asked += ips; kotlinx.coroutines.delay(delayMs); return result }
    }

    private val network = FakeDiscovery()

    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun clean() {
        listOf("router", "devices").forEach { prefs(it).edit().clear().commit() }
    }

    @After fun tearDown() = clean()

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun createViewModel() = onMain { vm = RouterViewModel(app, network) { _, pins -> router.also { it.pins = pins } } }

    private fun waitFor(message: String, condition: (UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition(vm.state.value)) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("$message; состояние: ${vm.state.value}")
            Thread.sleep(20)
        }
    }

    private fun signIn() {
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        onMain { vm.edit { it.copy(address = host, password = "secret") }; vm.submitLogin() }
        waitFor("вход") { it.phase == Phase.MAIN }
    }

    @Test fun savedPasswordIsNotSentToAnUnpinnedRouter() {
        CredentialStore(app).savePassword("secret")
        createViewModel()
        waitFor("экран входа вместо автовхода") { it.phase == Phase.LOGIN }
        assertEquals("пароль подставлен, но не отправлен", "secret", vm.state.value.password)
        assertEquals(0, router.logins.get())
        assertFalse(vm.state.value.knownRouter)
    }

    @Test fun savedPasswordSignsInToPinnedRouter() {
        CredentialStore(app).apply { savePassword("secret"); pins.put(host, "AB") }
        createViewModel()
        waitFor("автовход") { it.phase == Phase.MAIN }
        assertEquals(1, router.logins.get())
    }

    @Test fun reloginIsNotRepeatedWhenRouterStillRefusesData() {
        signIn()
        router.snapshotError = RouterException.SessionExpired   // роутер принимает вход, но данных не отдаёт (403/504)
        repeat(3) {
            if (vm.state.value.phase == Phase.MAIN) onMain { vm.refreshNow() }
            Thread.sleep(200)
        }
        waitFor("опрос остановлен на экране входа") { it.phase == Phase.LOGIN }
        assertEquals("начальный вход и один повторный", 2, router.logins.get())
    }

    @Test fun unreachableRouterKeepsSessionWithoutLogin() {
        signIn()
        router.snapshotError = RouterException.Unreachable
        onMain { vm.refreshNow() }
        waitFor("баннер") { it.banner != null }
        assertEquals(Phase.MAIN, vm.state.value.phase)
        assertEquals("пароль не отправлялся", 1, router.logins.get())
    }

    @Test fun refusedBackgroundReloginStopsPolling() {
        signIn()
        router.snapshotError = RouterException.SessionExpired
        router.loginError = RouterException.LoginRejected("Wrong password")
        onMain { vm.refreshNow() }
        waitFor("экран входа после отказа") { it.phase == Phase.LOGIN }
        assertEquals(2, router.logins.get())
        assertTrue(vm.state.value.error!!.isNotEmpty())
    }

    @Test fun sectionErrorOffersRetry() {
        signIn()
        router.routingError = RouterException.NoData("static_route", 404)
        onMain { vm.load(Section.ROUTING) }
        waitFor("ошибка раздела") { Section.ROUTING in it.sectionErrors }
        assertNull(vm.state.value.routing)
        router.routingError = null
        onMain { vm.load(Section.ROUTING) }
        waitFor("повтор удался") { it.routing != null && Section.ROUTING !in it.sectionErrors }
    }

    @Test fun lostSessionDuringSaveResetsSaveState() {
        signIn()
        router.saveError = RouterException.SessionExpired
        router.loginError = RouterException.InvalidCredentials
        onMain { vm.saveProfile(null, "Kids", listOf("02:00:5E:10:00:03"), listOf("Mon"), "20:00-24:00", true) }
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        assertEquals("кнопка «Сохранить» не зависнет после нового входа", SaveState.Idle, vm.state.value.saveState)
        assertEquals("неверный пароль стёрт", "", vm.state.value.password)
        assertNull("и из хранилища", CredentialStore(app).readPassword())
    }

    @Test fun trustingCertificatePinsTheShownFingerprint() {
        router.loginError = RouterException.CertificateChanged("FEED")
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        onMain { vm.edit { it.copy(address = host, password = "secret") }; vm.submitLogin() }
        waitFor("отпечаток показан") { it.certificateFingerprint == "FEED" }
        CredentialStore(app).pins.put(host, "OLD")
        router.loginError = RouterException.InvalidCredentials
        onMain { vm.trustNewCertificate() }
        waitFor("вход не удался") { it.phase == Phase.LOGIN && !it.busy && it.certificateFingerprint == null }
        assertEquals("неудачный вход не меняет закреплённый отпечаток", "OLD", CredentialStore(app).pins.get(host))
        router.loginError = RouterException.CertificateChanged("FEED")
        onMain { vm.edit { it.copy(password = "secret") }; vm.submitLogin() }
        waitFor("отпечаток показан снова") { it.certificateFingerprint == "FEED" }
        router.loginError = null
        onMain { vm.trustNewCertificate() }
        waitFor("вход") { it.phase == Phase.MAIN }
        assertEquals("FEED", CredentialStore(app).pins.get(host))
    }

    @Test fun demoModeWorksWithoutNetworkAndKeepsSavedPassword() {
        CredentialStore(app).savePassword("secret")
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        onMain { vm.startDemo() }
        waitFor("демо") { it.phase == Phase.MAIN && it.demo }
        assertEquals(0, router.logins.get())
        onMain { vm.saveProfile(null, "Kids", listOf("02:00:5E:10:00:03"), listOf("Mon"), "20:00-24:00", true) }
        waitFor("сохранено") { it.saveState == SaveState.Done }
        onMain { vm.load(Section.PARENTAL) }
        waitFor("профиль появился") { s -> s.parental?.profiles?.any { it.name == "Kids" } == true }
        onMain { vm.logout() }
        waitFor("выход из демо") { it.phase == Phase.LOGIN && !it.demo }
        assertEquals("выход из демо не стирает настоящий пароль", "secret", CredentialStore(app).readPassword())
        assertTrue(prefs("devices").all.isEmpty())
    }

    @Test fun logoutCancelsAndClearsSection() {
        signIn()
        onMain { vm.load(Section.ROUTING); vm.logout() }
        Thread.sleep(300)
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        assertNull("поздний ответ не вернул данные на экран входа", vm.state.value.routing)
        assertNull(vm.state.value.error)
    }

    @Test fun lockedRouterIsNotRetriedOnNextStart() {
        CredentialStore(app).apply { savePassword("secret"); pins.put(host, "AB"); lockedUntil = System.currentTimeMillis() + 60_000 }
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        assertEquals("автовход не продлевает блокировку", 0, router.logins.get())
    }

    @Test fun deletingProfileIsVisibleAndNotRepeated() {
        signIn()
        onMain { vm.load(Section.PARENTAL) }
        waitFor("профили") { it.parental != null }
        val index = vm.state.value.parental!!.profiles.first().index
        onMain { vm.deleteProfile(index); vm.deleteProfile(index) }
        waitFor("удалён") { s -> s.parental?.profiles?.none { it.index == index } == true && index !in s.deletingProfiles }
        assertTrue(vm.state.value.banner == null)
    }

    @Test fun loginShowsStepsAndCanBeCancelled() {
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        router.loginGate = java.util.concurrent.CountDownLatch(1)
        onMain { vm.edit { it.copy(address = host, password = "secret") }; vm.submitLogin() }
        waitFor("шаг входа на экране") { it.busy && it.loginStep == LoginStep.SIGNING_IN }
        onMain { vm.cancelLogin() }
        assertEquals(Phase.LOGIN, vm.state.value.phase)
        assertFalse("форма снова доступна", vm.state.value.busy)
        assertNull(vm.state.value.loginStep)
        assertTrue("клиент закрыт", router.closed)
        // Отменённый вход доходит до конца в фоне: на экран не попадает, сессия на роутере закрывается.
        router.loginGate!!.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (router.logouts.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, router.logouts.get())
        assertEquals(Phase.LOGIN, vm.state.value.phase)
        assertNull("отмена — не ошибка", vm.state.value.error)
    }

    @Test fun cancellingAutoLoginOpensLoginForm() {
        CredentialStore(app).apply { savePassword("secret"); pins.put(host, "AB") }
        router.loginGate = java.util.concurrent.CountDownLatch(1)
        createViewModel()
        waitFor("автовход идёт") { it.busy && it.loginStep != null }
        onMain { vm.cancelLogin() }
        assertEquals(Phase.LOGIN, vm.state.value.phase)
        router.loginGate!!.countDown()
    }

    /** Две быстрые записи много раз подряд: их итоги возвращаются на главный поток в произвольном порядке. */
    @Test fun rapidDoubleToggleEndsInLastState() { repeat(100) { router.writeGate = null; parentalSwitchShowsWriteInProgress() } }

    @Test fun parentalSwitchShowsWriteInProgress() {
        signIn()
        onMain { vm.load(Section.PARENTAL) }
        waitFor("профили") { it.parental != null }
        val p = vm.state.value.parental!!.profiles.first()
        router.writeGate = java.util.concurrent.CountDownLatch(1)
        onMain { vm.setProfileEnabled(p.index, !p.enabled); vm.setProfileEnabled(p.index, p.enabled) }
        waitFor("запись видна") { p.index in it.writingParental }
        router.writeGate!!.countDown()
        waitFor("обе записи закончились") { it.writingParental.isEmpty() }
        assertEquals(p.enabled, vm.state.value.parental!!.profiles.first { it.index == p.index }.enabled)
    }

    /** Чтение вкладки шло во время записи и ответило позже неё: его ответ не должен затереть итог записи. */
    @Test fun staleParentalReadDoesNotOverwriteWrite() {
        signIn()
        onMain { vm.load(Section.PARENTAL) }
        waitFor("профили") { it.parental != null && Section.PARENTAL !in it.sectionsBusy }
        val p = vm.state.value.parental!!.profiles.first()
        val release = java.util.concurrent.CountDownLatch(1)
        router.staleReadTaken = java.util.concurrent.CountDownLatch(1)
        router.staleReadGate = release
        onMain { vm.load(Section.PARENTAL) }
        assertTrue("чтение началось", router.staleReadTaken!!.await(5, java.util.concurrent.TimeUnit.SECONDS))
        onMain { vm.setProfileEnabled(p.index, !p.enabled) }
        waitFor("запись закончилась") { it.writingParental.isEmpty() && it.parental!!.profiles.first { x -> x.index == p.index }.enabled == !p.enabled }
        release.countDown()
        waitFor("устаревшее чтение закончилось") { Section.PARENTAL !in it.sectionsBusy }
        assertEquals(!p.enabled, vm.state.value.parental!!.profiles.first { it.index == p.index }.enabled)
    }

    @Test fun routingReportsProgress() {
        signIn()
        onMain { vm.load(Section.ROUTING) }
        waitFor("маршруты") { it.routing != null }
        assertEquals(ROUTING_PARTS, vm.state.value.routingProgress)
    }

    @Test fun discoveryAnswersAreMatchedByIpAndRemembered() {
        signIn()
        val tv = vm.state.value.snapshot!!.devices.first { it.online && it.ip.isNotEmpty() }
        network.result = DiscoveryResult(mapOf(tv.ip to NetworkIdentity(name = "Гостиная", manufacturer = "LG"), "192.168.1.250" to NetworkIdentity(name = "чужой")))
        onMain { vm.discoverDevices(auto = true) }
        waitFor("опрос закончен") { it.discovery is DiscoveryStatus.Done }
        assertEquals("ответ без устройства в списке роутера не считается", DiscoveryStatus.Done(1), vm.state.value.discovery)
        assertEquals("Гостиная", vm.state.value.identities[tv.mac]?.name)
        assertTrue("спрашиваем только про устройства из ответа роутера", tv.ip in network.asked.single())
        onMain { vm.discoverDevices(auto = true) }
        Thread.sleep(200)
        assertEquals("автоопрос — один раз за вход", 1, network.asked.size)
        // После нового входа сведения на месте, хотя сеть больше не отвечает.
        network.result = DiscoveryResult(emptyMap())
        onMain { vm.logout() }
        waitFor("выход") { it.phase == Phase.LOGIN }
        assertTrue(vm.state.value.identities.isEmpty())
        router.closed = false   // подставной роутер один на тест; настоящий клиент на каждый вход новый
        onMain { vm.edit { it.copy(password = "secret") }; vm.submitLogin() }
        waitFor("вход") { it.phase == Phase.MAIN }
        assertEquals("LG", vm.state.value.identities[tv.mac]?.manufacturer)
    }

    @Test fun discoveryOffTheLanSaysSo() {
        signIn()
        network.result = DiscoveryResult(emptyMap(), onLan = false)
        onMain { vm.discoverDevices() }
        waitFor("подсказка") { it.discovery == DiscoveryStatus.NotOnLan }
    }

    @Test fun vendorsComeFromTheBuiltInRegistry() {
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        onMain { vm.startDemo() }
        waitFor("производители") { it.vendors["04:6F:00:3A:10:04"] == "LG Electronics" }
        assertTrue("у частного адреса производителя нет", "DA:A1:19:5B:10:08" !in vm.state.value.vendors)
    }

    @Test fun brokenSectionResponseKeepsTheSession() {
        signIn()
        // Ответ оборвался посреди передачи: это ошибка раздела, а не отказ во входе.
        router.routingError = RouterException.BadResponse
        onMain { vm.load(Section.ROUTING) }
        waitFor("ошибка раздела") { Section.ROUTING in it.sectionErrors }
        assertEquals(Phase.MAIN, vm.state.value.phase)
        assertEquals("пароль не отправлялся повторно", 1, router.logins.get())
    }

    @Test fun cancelledLoginRemembersNothing() {
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        router.loginGate = java.util.concurrent.CountDownLatch(1)
        onMain { vm.edit { it.copy(address = host, password = "secret", remember = true) }; vm.submitLogin() }
        waitFor("вход идёт") { it.busy && it.loginStep == LoginStep.SIGNING_IN }
        onMain { vm.cancelLogin() }
        router.closed = false   // роутер успеет ответить на вход и снимок, как будто отмену нажали в последний момент
        router.loginGate!!.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (router.logouts.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("сессия на роутере закрыта", 1, router.logouts.get())
        assertNull("пароль от отменённого входа не сохранён", CredentialStore(app).readPassword())
    }

    @Test fun loginFormIsFrozenWhileSigningIn() {
        createViewModel()
        waitFor("экран входа") { it.phase == Phase.LOGIN }
        router.loginGate = java.util.concurrent.CountDownLatch(1)
        onMain { vm.edit { it.copy(address = host, password = "secret") }; vm.submitLogin() }
        waitFor("вход идёт") { it.busy }
        onMain { vm.setAddress("192.168.1.2") }
        assertEquals("адрес идущего входа не меняется", host, vm.state.value.address)
        router.loginGate!!.countDown()
        waitFor("вход") { it.phase == Phase.MAIN }
    }

    @Test fun discoveryInterruptedByLogoutLeavesNoTrace() {
        signIn()
        network.delayMs = 2_000
        network.result = DiscoveryResult(mapOf("192.168.1.52" to NetworkIdentity(name = "старый")))
        onMain { vm.discoverDevices() }
        waitFor("опрос идёт") { it.discovery == DiscoveryStatus.Running }
        onMain { vm.logout() }
        waitFor("выход") { it.phase == Phase.LOGIN }
        Thread.sleep(2_500)
        assertEquals(DiscoveryStatus.Idle, vm.state.value.discovery)
        assertTrue(vm.state.value.identities.isEmpty())
    }
}
