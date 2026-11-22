package com.usharik.routermanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Phase { STARTING, LOGIN, MAIN }

/** Разделы, которые читаются отдельно от общего снимка, когда пользователь открывает вкладку. */
enum class Section { PARENTAL, ROUTING, LOG }

/** Ключ общего переключателя родительского контроля в [UiState.writingParental]; профили — по своему Index. */
const val PARENTAL_ALL = -1

sealed interface SaveState {
    data object Idle : SaveState
    data object InFlight : SaveState
    data object Done : SaveState
    data class Failed(val message: String) : SaveState
}

/** Опрос домашней сети: [Done.answered] — сколько устройств из списка роутера рассказали о себе. */
sealed interface DiscoveryStatus {
    data object Idle : DiscoveryStatus
    data object Running : DiscoveryStatus
    /** Телефон не в Wi-Fi или Ethernet: спрашивать некого. */
    data object NotOnLan : DiscoveryStatus
    data class Done(val answered: Int) : DiscoveryStatus
}

data class UiState(
    val phase: Phase = Phase.STARTING,
    val address: String = "192.168.1.1",
    val username: String = "admin",
    val password: String = "",
    val remember: Boolean = true,
    val routerName: String = "",
    /** Сертификат роутера по этому адресу уже закреплён; иначе экран входа предупреждает о первом подключении. */
    val knownRouter: Boolean = false,
    val busy: Boolean = false,
    /** Шаг идущего входа: экран показывает, чего ждёт, а не безымянный индикатор. */
    val loginStep: LoginStep? = null,
    val error: String? = null,
    val banner: String? = null,
    val snapshot: RouterSnapshot? = null,
    /** Устройства из снимка плюс запомненные офлайн. */
    val devices: List<RouterDevice> = emptyList(),
    /** Производитель по MAC (из встроенного реестра IEEE); частных адресов здесь нет. */
    val vendors: Map<String, String> = emptyMap(),
    /** Что устройства сообщили о себе в сети, по MAC; помнится между запусками. */
    val identities: Map<String, NetworkIdentity> = emptyMap(),
    val discovery: DiscoveryStatus = DiscoveryStatus.Idle,
    val updatedAt: Long = 0,
    val parental: ParentalControl? = null,
    val routing: Routing? = null,
    val log: List<LogEntry>? = null,
    /** Ошибка чтения раздела: экран показывает её с кнопкой «Повторить» вместо бесконечного индикатора. */
    val sectionErrors: Map<Section, String> = emptyMap(),
    /** Разделы, которые сейчас читаются; у каждого своя занятость, чтобы ответ одного не гасил индикатор другого. */
    val sectionsBusy: Set<Section> = emptySet(),
    /** Профили, которые удаляются: их карточки неактивны до ответа роутера. */
    val deletingProfiles: Set<Int> = emptySet(),
    /** Переключатели родительского контроля, запись которых ещё идёт ([PARENTAL_ALL] — общий): рядом с ними индикатор. */
    val writingParental: Set<Int> = emptySet(),
    /** Сколько разделов маршрутов уже прочитано из [ROUTING_PARTS]. */
    val routingProgress: Int = 0,
    val refreshing: Boolean = false,   // обновление по кнопке; фоновый опрос индикатор не показывает
    val saveState: SaveState = SaveState.Idle,
    val rebooting: Boolean = false,
    /** Вход не прошёл из-за сменившегося сертификата: SHA-256 нового, чтобы пользователь мог ему довериться. */
    val certificateFingerprint: String? = null,
    /** Встроенные данные без сети ([DemoRouter]). */
    val demo: Boolean = false,
)

/**
 * Состояние экранов и сессия с роутером. [connect] создаёт клиента по адресу; в тестах — подставной [RouterApi].
 */
private const val REBOOT_WAIT_MS = 180_000L

class RouterViewModel(
    app: Application,
    private val discovery: LocalDiscovery = NetworkDiscovery(app),
    private val connect: (address: String, pins: CertificatePins) -> RouterApi,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, connect = ::RouterClient)

    private val store = CredentialStore(app)
    val state = MutableStateFlow(UiState(address = store.address, username = store.username))
    private var client: RouterApi? = null
    // Объявлен до session: инициализаторы выполняются по порядку, и область сессии создаётся уже с ним.
    /** Неожиданное исключение в запросе (незнакомая прошивка сломала разбор) не роняет приложение, а видно в баннере. */
    private val unexpected = CoroutineExceptionHandler { _, e ->
        Telemetry.error("session", e)
        // Сбрасываем всё, что могло остаться «в работе»: иначе раздел, кнопка «Сохранить» или переключатели зависнут до
        // нового входа. Обработчик вызывается на главном потоке (область сессии — viewModelScope).
        parentalWrites = 0
        writing.clear()
        state.update {
            it.copy(
                banner = text(RouterException.BadResponse), sectionsBusy = emptySet(), refreshing = false, deletingProfiles = emptySet(),
                writingParental = emptySet(), saveState = if (it.saveState == SaveState.InFlight) SaveState.Failed(text(RouterException.BadResponse)) else it.saveState,
                discovery = if (it.discovery == DiscoveryStatus.Running) DiscoveryStatus.Idle else it.discovery,
            )
        }
        restartPolling()   // опрос мог упасть вместе с этим исключением
    }

    /** Все запросы текущего входа: выход и потеря сессии отменяют их, и поздний ответ не попадёт на экран входа. */
    private var session: CoroutineScope = newSessionScope()
    private var pollJob: Job? = null
    private var foreground = false
    private var rebootNoticeUntil = 0L
    private var parentalWrites = 0   // только с главного потока
    /** Растёт при каждой записи родительского контроля: чтение, во время которого началась запись, устарело. Только с главного потока. */
    private var parentalWriteSeq = 0
    /** Сколько записей ждёт каждый переключатель: две подряд не должны погасить индикатор после первой. Главный поток. */
    private val writing = mutableMapOf<Int, Int>()
    /** Номер попытки входа: отменённая попытка, вернувшись, не трогает экран. Главный поток. */
    private var loginAttempt = 0
    /** Клиент идущего входа — чтобы «Отмена» могла его закрыть. */
    private var pendingLogin: RouterApi? = null
    /**
     * Записи родительского контроля идут по одной: профили адресуются по Index, и запись, вклинившаяся между
     * удалением и перечитыванием списка, могла бы попасть в другой профиль.
     */
    private val parentalQueue = Mutex()
    private var writeError: String? = null   // текст ошибки записи; держится, пока запись не пройдёт или баннер не закроют

    private val breaks = MutableSharedFlow<AdBreakEvent>(extraBufferCapacity = 4)
    /** Паузы, когда пользователь закончил дело; интерфейс решает, показать ли рекламу. */
    val adBreaks: SharedFlow<AdBreakEvent> = breaks.asSharedFlow()

    private fun actionStarted() = android.os.SystemClock.elapsedRealtime()

    private fun newSessionScope() = viewModelScope + SupervisorJob(viewModelScope.coroutineContext[Job]) + unexpected

    private fun endSession() {
        pollJob?.cancel()
        session.cancel()
        session = newSessionScope()
        // Запросы, ещё ждущие очереди к роутеру, после этого не войдут заново с паролем из замыкания.
        client?.close()
        client = null
        parentalWrites = 0
        writing.clear()
        rebootNoticeUntil = 0
        writeError = null
        lastSavedHistory = null
    }

    /** Запрос с восстановлением сессии: при устаревшем токене входит заново один раз. */
    private suspend fun <T> recovering(c: RouterApi, retry: Boolean = true, block: () -> T): T {
        val s = state.value
        return withContext(Dispatchers.IO) {
            val generation = c.sessionGeneration
            withSessionRecovery(retry, {
                if (s.password.isEmpty() && !s.demo) throw RouterException.SessionExpired
                c.reloginIfUnchanged(generation, s.username, s.password)
            }, block)
        }
    }

    /** Отказ в повторном входе нельзя глушить баннером: дальнейшие попытки могут заблокировать вход на роутере. */
    private fun sessionLost(e: RouterException): Boolean {
        if (!isLoginFailure(e)) return false
        loginFailed(e)
        return true
    }

    // Background refreshes must not wipe the reboot notice while the router is going down.
    private fun bannerAfterRefresh(text: String?, current: String?) = bannerAfterPoll(text, current, System.currentTimeMillis() < rebootNoticeUntil, writeError)

    // Локальная история устройств: роутер забывает отключившиеся устройства, а мы их помним. В демо-режиме не ведётся.
    private val devPrefs = app.getSharedPreferences("devices", android.content.Context.MODE_PRIVATE)
    private var history: DeviceHistory? = null
    private var historyKey = ""

    private fun historyFor(address: String): DeviceHistory {
        val key = "known:" + address.trim()
        if (history == null || historyKey != key) {
            history = DeviceHistory(devPrefs.getString(key, null))
            historyKey = key
        }
        return history!!
    }

    private var lastSavedHistory: String? = null

    /** Пишет историю на диск в фоне и только если она изменилась: опрос идёт каждые 15 секунд. */
    private fun persistHistory(json: String?) {
        if (json == null || json == lastSavedHistory) return
        lastSavedHistory = json
        val key = historyKey
        viewModelScope.launch(Dispatchers.IO) { devPrefs.edit().putString(key, json).apply() }
    }

    /** Новый снимок: устройства — вместе с историей. */
    private fun applySnapshot(snapshot: RouterSnapshot) {
        val s = state.value
        val devices = if (s.demo) snapshot.devices else historyFor(s.address).let { h ->
            persistHistory(h.record(snapshot.devices, System.currentTimeMillis() / 1000))
            h.merge(snapshot.devices)
        }
        state.update { it.copy(snapshot = snapshot, devices = devices, updatedAt = System.currentTimeMillis(), banner = bannerAfterRefresh(null, it.banner)) }
        updateVendors()
    }

    fun forgetDevice(mac: String) {
        val s = state.value
        if (s.demo) return
        val h = historyFor(s.address)
        persistHistory(h.forget(mac))
        state.update { it.copy(devices = h.merge(it.snapshot?.devices.orEmpty()), identities = it.identities - mac) }
        saveIdentities()
    }

    private val oui = OuiDatabase.fromAssets(app)

    /** Производители для новых MAC; справочник читается один раз в фоне. */
    private fun updateVendors() {
        val missing = state.value.devices.map { it.mac }.filter { it.isNotEmpty() && it !in state.value.vendors }
        if (missing.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (!oui.load()) return@launch
            val found = missing.mapNotNull { mac -> oui.vendor(mac)?.let { mac to it } }.toMap()
            if (found.isNotEmpty()) state.update { it.copy(vendors = it.vendors + found) }
        }
    }

    private fun identitiesKey(address: String) = "identities:" + address.trim()

    private fun saveIdentities() {
        val s = state.value
        if (s.demo) return
        val key = identitiesKey(s.address)
        val json = identitiesToJson(s.identities)
        viewModelScope.launch(Dispatchers.IO) { devPrefs.edit().putString(key, json).apply() }
    }

    private var discoveredThisSession = false
    private var discoveryJob: Job? = null

    /**
     * Спрашивает устройства в сети о них самих. [auto] — при первом открытии списка устройств: один раз за вход,
     * а повтор — только по кнопке. Ответы сопоставляются с устройствами роутера по IP.
     */
    fun discoverDevices(auto: Boolean = false) {
        val s = state.value
        if (s.discovery == DiscoveryStatus.Running || client == null || (auto && discoveredThisSession)) return
        discoveredThisSession = true
        val byIp = s.snapshot?.devices.orEmpty().filter { it.online && it.ip.isNotEmpty() && it.mac.isNotEmpty() }.associate { it.ip to it.mac }
        val source = if (s.demo) DemoDiscovery() else discovery
        state.update { it.copy(discovery = DiscoveryStatus.Running) }
        val c = client
        discoveryJob = session.launch {
            val result = try {
                source.discover(byIp.keys.toList())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // выход или конец сессии: результат никому не нужен
            } catch (e: Exception) {
                Telemetry.error("discovery", e)
                DiscoveryResult(emptyMap())
            }
            if (client !== c) return@launch
            val found = result.identities.mapNotNull { (ip, id) -> byIp[ip]?.let { it to id } }.toMap()
            state.update {
                it.copy(
                    identities = it.identities + found.mapValues { (mac, id) -> id + (it.identities[mac] ?: NetworkIdentity()) },
                    discovery = if (result.onLan) DiscoveryStatus.Done(found.size) else DiscoveryStatus.NotOnLan,
                )
            }
            if (found.isNotEmpty()) saveIdentities()
            Telemetry.event("devices_discovered", mapOf("answered" to found.size, "on_lan" to if (result.onLan) "yes" else "no"))
        }
    }

    init {
        viewModelScope.launch {
            probe()
            val saved = withContext(Dispatchers.IO) { store.readPassword() }
            val known = store.pins.get(RouterClient.hostOf(state.value.address)) != null
            if (saved != null) state.update { it.copy(password = saved) }
            // Без закреплённого сертификата пароль автоматически не отправляется: в чужой сети по тому же адресу
            // может ответить чужое устройство. Пользователь сам нажмёт «Войти» после предупреждения.
            // Пока читался пароль, пользователь мог уже открыть демо-режим: тогда экран не трогаем.
            if (state.value.phase != Phase.STARTING) return@launch
            val locked = System.currentTimeMillis() < store.lockedUntil
            if (saved != null && known && !locked) login() else state.update { it.copy(phase = Phase.LOGIN) }
        }
    }

    /** Текст ошибки на языке пользователя. */
    private fun text(e: Throwable): String = routerErrorText(getApplication<Application>().resources, e)

    fun edit(transform: (UiState) -> UiState) = state.update(transform)

    fun setAddress(value: String) {
        if (state.value.busy) return   // адрес входа, который уже идёт, менять нельзя: история и сведения легли бы не туда
        state.update { it.copy(address = value, certificateFingerprint = null) }
        probe(debounceMs = 700)
    }

    private var probeJob: Job? = null

    /**
     * Ищет роутер по адресу: модель для экрана входа. Отпечаток при этом не закрепляется (см. [SessionPins]).
     * При вводе адреса — с паузой и отменой прошлой проверки: иначе каждая цифра запускала бы запрос к роутеру.
     */
    fun probe(debounceMs: Long = 0) {
        val address = state.value.address
        val known = runCatching { store.pins.get(RouterClient.hostOf(address)) != null }.getOrDefault(false)
        state.update { it.copy(knownRouter = known) }
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            delay(debounceMs)
            val name = withContext(Dispatchers.IO) {
                runCatching { connect(address, store.pins).basicInfo().let { "${it.model} · ${it.firmware}" } }.getOrDefault("")
            }
            if (state.value.address == address) state.update { it.copy(routerName = name) }
        }
    }

    suspend fun login() {
        val s = state.value
        val attempt = ++loginAttempt
        state.update { it.copy(busy = true, error = null, certificateFingerprint = null, loginStep = LoginStep.CONNECTING) }
        var c: RouterApi? = null
        // Отменённый вход доходит до конца в фоне (блокирующий запрос не прервать) — его результат не нужен,
        // а сессию, если роутер успел её открыть, закрываем, чтобы не занимать место для следующего входа.
        fun abandoned(): Boolean {
            if (attempt == loginAttempt) return false
            c?.let { viewModelScope.launch(Dispatchers.IO) { runCatching { it.logout() } } }
            return true
        }
        val step = { next: LoginStep -> state.update { if (attempt == loginAttempt && it.busy) it.copy(loginStep = next) else it } }
        try {
            val trusted = trustOnce?.takeIf { it.first == RouterClient.hostOf(s.address) }
            trustOnce = null
            val client = connect(s.address, trusted?.let { (host, fp) -> TrustedPins(store.pins, host, fp) } ?: store.pins)
            c = client
            pendingLogin = client
            val snapshot = withContext(Dispatchers.IO) {
                client.login(s.username, s.password, step)
                step(LoginStep.LOADING)
                client.snapshot()
            }
            if (abandoned()) return
            pendingLogin = null
            // Сохраняем только вход, от которого пользователь не отказался: отменённый не должен ни запомнить роутер
            // для автовхода, ни стереть пароль, сохранённый более новой попыткой.
            withContext(Dispatchers.IO) {
                store.address = s.address
                store.username = s.username
                // Сбой Keystore не должен срывать удачный вход; пароль просто не запомнится.
                if (s.remember) runCatching { store.savePassword(s.password) } else store.clearPassword()
            }
            if (attempt != loginAttempt) return abandonedAfterSave(client)
            startSession(client, snapshot, demo = false)
            Telemetry.router(snapshot.model, snapshot.firmware)
            Telemetry.event("login_succeeded", mapOf("devices" to snapshot.devices.count { it.online }, "remembered" to if (s.remember) "yes" else "no"))
        } catch (e: RouterException) {
            if (abandoned()) return
            pendingLogin = null
            loginFailed(e)
        } catch (e: Exception) {
            if (abandoned()) return
            pendingLogin = null
            // Keystore или разбор RSA-ключа могут бросить не-RouterException — не роняем приложение.
            Telemetry.error("login", e)
            loginFailed(RouterException.BadLoginData)
        }
    }

    /** Отмену нажали, пока сохранялись данные входа: сессию на роутере закрываем, экран не трогаем. */
    private fun abandonedAfterSave(c: RouterApi) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { c.logout() } }
    }

    /** «Отмена» долгого входа: клиент закрывается, экран сразу возвращается к форме входа. */
    fun cancelLogin() {
        if (!state.value.busy) return
        loginAttempt++
        pendingLogin?.close()
        pendingLogin = null
        Telemetry.event("login_cancelled", mapOf("step" to (state.value.loginStep?.name?.lowercase() ?: "none")))
        state.update { it.copy(phase = Phase.LOGIN, busy = false, loginStep = null) }
    }

    private fun startSession(c: RouterApi, snapshot: RouterSnapshot, demo: Boolean) {
        endSession()
        client = c
        discoveredThisSession = false
        val identities = if (demo) emptyMap() else identitiesFromJson(devPrefs.getString(identitiesKey(state.value.address), null))
        state.update {
            it.copy(
                phase = Phase.MAIN, demo = demo, busy = false, loginStep = null, banner = null, identities = identities, discovery = DiscoveryStatus.Idle, knownRouter = !demo || it.knownRouter,
                parental = null, routing = null, log = null, sectionErrors = emptyMap(), saveState = SaveState.Idle, rebooting = false,
                sectionsBusy = emptySet(), deletingProfiles = emptySet(), writingParental = emptySet(), refreshing = false,
            )
        }
        applySnapshot(snapshot)
        restartPolling()
    }

    fun submitLogin() { if (!state.value.busy) viewModelScope.launch { login() } }

    /** Демо-режим: те же экраны на встроенных данных, без сети и без сохранённого пароля. */
    fun startDemo() {
        if (state.value.busy) return
        val demo = DemoRouter()
        startSession(demo, demo.snapshot(), demo = true)
        Telemetry.event("demo_started")
    }

    /**
     * Пользователь решил, что сертификат сменился законно (сброс, прошивка): закрепляем именно тот отпечаток, который
     * он видел на экране, и входим. Другой подменённый сертификат при этом всё равно не пройдёт.
     */
    fun trustNewCertificate() {
        val fingerprint = state.value.certificateFingerprint ?: return
        trustOnce = RouterClient.hostOf(state.value.address) to fingerprint
        Telemetry.event("certificate_retrusted")
        submitLogin()
    }

    /** Отпечаток, которому пользователь решил доверять; сохраняется только если вход с ним удастся. */
    private var trustOnce: Pair<String, String>? = null

    // Saved credentials are dropped after a rejected login, so the app can never cause a router lockout by retrying.
    private fun loginFailed(e: RouterException) {
        Telemetry.event("login_failed", mapOf("reason" to loginFailureReason(e), "phase" to state.value.phase.name.lowercase()))
        if (e is RouterException.Locked) store.lockedUntil = System.currentTimeMillis() + e.seconds.coerceAtLeast(60) * 1000L
        if (e is RouterException.InvalidCredentials) {
            store.clearPassword()
            state.update { it.copy(password = "") }
        }
        endSession()
        // Сбрасываем данные другого роутера, чтобы после перелогина не мелькали чужие профили/маршруты.
        state.update {
            it.copy(
                phase = Phase.LOGIN, snapshot = null, devices = emptyList(), identities = emptyMap(), discovery = DiscoveryStatus.Idle, parental = null, routing = null, log = null, sectionErrors = emptyMap(),
                error = text(e), busy = false, loginStep = null, saveState = SaveState.Idle, rebooting = false, demo = false,
                sectionsBusy = emptySet(), deletingProfiles = emptySet(), writingParental = emptySet(), refreshing = false,
                certificateFingerprint = (e as? RouterException.CertificateChanged)?.fingerprint,
            )
        }
    }

    fun setForeground(value: Boolean) {
        val wasForeground = foreground
        foreground = value
        restartPolling()
        // Возврат на экран: данные могли устареть, а сессия — истечь; не ждём первого такта опроса.
        if (value && !wasForeground && client != null && System.currentTimeMillis() - state.value.updatedAt > 5_000) {
            session.launch { refresh() }
        }
    }

    private fun restartPolling() {
        pollJob?.cancel()
        if (!foreground || client == null) return
        pollJob = session.launch {
            while (isActive) {
                delay(15_000)
                refresh()
            }
        }
    }

    /** Кнопка «Обновить»: снимок и раздел открытой вкладки. */
    fun refreshNow(section: Section? = null) {
        if (state.value.refreshing) return
        session.launch {
            state.update { it.copy(refreshing = true) }
            val c = client
            // После выхода и нового входа поздний ответ не должен гасить индикатор уже новой сессии.
            try { refresh() } finally { if (client === c) state.update { it.copy(refreshing = false) } }
        }
        section?.let { load(it) }
    }

    private suspend fun refresh() {
        val c = client ?: return
        val generation = c.sessionGeneration
        try {
            val snapshot = withContext(Dispatchers.IO) { c.snapshot() }
            if (client === c) applySnapshot(snapshot)
        } catch (_: RouterException.SessionExpired) {
            relogin(c, generation)
        } catch (e: RouterException.CertificateChanged) {
            loginFailed(e)   // не опрашивать дальше чужой роутер; решение — на экране входа
        } catch (e: RouterException) {
            state.update { it.copy(banner = bannerAfterRefresh(text(e), it.banner)) }
        }
    }

    private suspend fun relogin(c: RouterApi, generation: Int) {
        val s = state.value
        if (s.password.isEmpty() && !s.demo) return loginFailed(RouterException.SessionExpired)
        try {
            val snapshot = withContext(Dispatchers.IO) {
                c.reloginIfUnchanged(generation, s.username, s.password)
                c.snapshot()
            }
            if (client === c) applySnapshot(snapshot)
        } catch (e: RouterException) {
            if (keepPollingAfterReloginFailure(e)) state.update { it.copy(banner = bannerAfterRefresh(text(e), it.banner)) } else loginFailed(e)
        }
    }

    /** Читает раздел для открытой вкладки; ошибка остаётся у раздела, чтобы экран мог предложить повтор. */
    fun load(section: Section, keepBanner: Boolean = false) {
        val c = client ?: return
        if (section == Section.PARENTAL && parentalWrites > 0) return   // запись сама перечитает состояние
        val writeSeq = parentalWriteSeq
        session.launch {
            state.update {
                it.copy(sectionsBusy = it.sectionsBusy + section, sectionErrors = it.sectionErrors - section, routingProgress = if (section == Section.ROUTING) 0 else it.routingProgress)
            }
            try {
                val result: Any = recovering(c) {
                    when (section) {
                        Section.PARENTAL -> c.parentalControl()
                        Section.ROUTING -> c.routing { done -> if (client === c) state.update { it.copy(routingProgress = done) } }
                        Section.LOG -> c.systemLog()
                    }
                }
                if (client !== c) return@launch
                state.update {
                    val banner = if (keepBanner) it.banner else bannerAfterRefresh(null, it.banner)
                    @Suppress("UNCHECKED_CAST")
                    when (section) {
                        // Чтение идёт мимо очереди записей: если запись началась, пока оно шло, ответ мог застать
                        // промежуточное состояние (между двумя быстрыми переключениями) и затёр бы итог записи.
                        Section.PARENTAL -> it.copy(parental = if (parentalWrites == 0 && parentalWriteSeq == writeSeq) result as ParentalControl else it.parental, banner = banner)
                        Section.ROUTING -> it.copy(routing = result as Routing, banner = banner)
                        Section.LOG -> it.copy(log = result as List<LogEntry>, banner = banner)
                    }
                }
            } catch (e: RouterException) {
                if (sessionLost(e)) return@launch
                state.update { it.copy(sectionErrors = it.sectionErrors + (section to text(e))) }
            } finally {
                if (client === c) state.update { it.copy(sectionsBusy = it.sectionsBusy - section) }
            }
        }
    }

    fun loadParental(keepBanner: Boolean = false) = load(Section.PARENTAL, keepBanner)
    fun loadLog() = load(Section.LOG)

    /**
     * Пароль сети для QR-кода. Возвращается вызывающему и нигде не хранится: ни в [UiState], ни в истории.
     * Ошибка — текст для пользователя; потерянную сессию разбирает экран входа.
     */
    suspend fun wifiCredentials(network: WifiNetwork): Result<WifiCredentials> {
        val c = client ?: return Result.failure(IllegalStateException(text(RouterException.SessionExpired)))
        return try {
            Result.success(recovering(c) { c.wifiCredentials(network) })
        } catch (e: RouterException) {
            sessionLost(e)
            Result.failure(IllegalStateException(text(e)))
        }
    }

    /**
     * Запись родительского контроля с мгновенным откликом. Откатывается только изменённое поле, а ответ роутера
     * применяется после последней из параллельных записей, чтобы соседние переключатели не прыгали.
     */
    private fun writeParental(key: Int, optimistic: (ParentalControl) -> ParentalControl, rollback: (ParentalControl) -> ParentalControl, op: (RouterApi) -> Unit) {
        val c = client ?: return
        writing[key] = (writing[key] ?: 0) + 1
        parentalWriteSeq++
        state.update { it.copy(parental = it.parental?.let(optimistic), writingParental = writing.keys.toSet()) }
        parentalWrites++
        session.launch {
            try {
                // Итог записи учитывается внутри очереди: после её освобождения продолжения двух записей могут
                // вернуться на главный поток в обратном порядке, и последним лёг бы ответ более ранней записи.
                parentalQueue.withLock {
                    val pc = try { recovering(c) { op(c); c.parentalControl() } } catch (e: RouterException) { parentalWrites--; throw e }
                    parentalWrites--
                    writeError = null
                    if (parentalWrites == 0) state.update { it.copy(parental = pc, banner = bannerAfterRefresh(null, it.banner)) }
                }
                Telemetry.event("parental_toggled")
            } catch (e: RouterException) {
                if (sessionLost(e)) return@launch
                writeError = text(e)
                state.update { it.copy(parental = it.parental?.let(rollback), banner = text(e)) }
                loadParental(keepBanner = true)
            } finally {
                // После конца сессии карта уже очищена; новая сессия чужие счётчики не наследует.
                if (client === c) {
                    val left = (writing[key] ?: 1) - 1
                    if (left > 0) writing[key] = left else writing.remove(key)
                    state.update { it.copy(writingParental = writing.keys.toSet()) }
                }
            }
        }
    }

    fun setParentalEnabled(on: Boolean) = writeParental(PARENTAL_ALL, { it.copy(enabled = on) }, { it.copy(enabled = !on) }) { it.setParentalEnabled(on) }

    fun setProfileEnabled(index: Int, on: Boolean) =
        writeParental(index, { it.withProfileEnabled(index, on) }, { it.withProfileEnabled(index, !on) }) { it.setProfileEnabled(index, on) }

    /** Результат хранится здесь, а не в колбэках диалога: иначе после поворота экрана ответ уйдёт в уничтоженный экран. */
    fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean) {
        if (state.value.saveState == SaveState.InFlight) return
        val c = client ?: return state.update { it.copy(saveState = SaveState.Failed(text(RouterException.SessionExpired))) }
        state.update { it.copy(saveState = SaveState.InFlight) }
        parentalWriteSeq++
        val actionAt = actionStarted()
        session.launch {
            try {
                // Создание не повторяем автоматически: при 504 профиль мог уже создаться, и повтор сделал бы дубль.
                parentalQueue.withLock { recovering(c, retry = index != null) { c.saveProfile(index, name, macs, days, timeRangeList, enabled) } }
            } catch (e: RouterException) {
                if (!sessionLost(e)) state.update { it.copy(saveState = SaveState.Failed(text(e))) }   // диалог открыт, можно повторить
                return@launch
            }
            // Запись прошла. Ошибку перечитывания не показываем в диалоге, иначе повтор создаст дубль.
            writeError = null
            state.update { it.copy(saveState = SaveState.Done) }
            Telemetry.event("profile_saved", mapOf("mode" to if (index == null) "created" else "updated", "devices" to macs.size, "days" to days.size))
            breaks.tryEmit(AdBreakEvent(AdBreak.CHANGE_SAVED, actionAt))
            loadParental(keepBanner = true)
        }
    }

    /** Редактор открыт или закрыт: результат прошлого сохранения ему уже не относится. Не трогает идущую запись. */
    fun resetSaveState() = state.update { if (it.saveState == SaveState.InFlight) it else it.copy(saveState = SaveState.Idle) }

    fun deleteProfile(index: Int) {
        val c = client ?: return
        if (index in state.value.deletingProfiles) return
        state.update { it.copy(deletingProfiles = it.deletingProfiles + index) }
        parentalWriteSeq++
        val actionAt = actionStarted()
        session.launch {
            try {
                val pc = parentalQueue.withLock { recovering(c) { c.deleteProfile(index); c.parentalControl() } }
                writeError = null
                Telemetry.event("profile_deleted")
                breaks.tryEmit(AdBreakEvent(AdBreak.CHANGE_SAVED, actionAt))
                state.update { it.copy(parental = pc, banner = bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                if (sessionLost(e)) return@launch
                writeError = text(e)
                state.update { it.copy(banner = text(e)) }
                loadParental(keepBanner = true)
            } finally {
                if (client === c) state.update { it.copy(deletingProfiles = it.deletingProfiles - index) }
            }
        }
    }

    fun reboot() {
        val c = client ?: return
        if (state.value.rebooting) return
        state.update { it.copy(rebooting = true) }
        val actionAt = actionStarted()
        session.launch {
            try {
                recovering(c, retry = false) { c.reboot() }
                rebootNoticeUntil = System.currentTimeMillis() + REBOOT_WAIT_MS
                state.update { it.copy(banner = getApplication<Application>().getString(R.string.banner_rebooting)) }
                Telemetry.event("router_rebooted")
                breaks.tryEmit(AdBreakEvent(AdBreak.REBOOT, actionAt))
                // Пока роутер перезагружается, повторная команда только запутает: кнопка ждёт, как и баннер.
                delay(REBOOT_WAIT_MS)
            } catch (e: RouterException) {
                if (sessionLost(e)) return@launch
                writeError = text(e)
                state.update { it.copy(banner = text(e)) }
            } finally {
                if (client === c) state.update { it.copy(rebooting = false) }
            }
        }
    }

    fun logout() {
        val s = state.value
        Telemetry.event(if (s.demo) "demo_closed" else "logout")
        val c = client
        endSession()
        // Из демо-режима выходим без последствий для настоящего входа: сохранённый пароль остаётся.
        if (!s.demo) store.clearPassword()
        state.update {
            it.copy(
                phase = Phase.LOGIN, password = if (s.demo) it.password else "", snapshot = null, devices = emptyList(), identities = emptyMap(), discovery = DiscoveryStatus.Idle, parental = null, routing = null,
                log = null, sectionErrors = emptyMap(), saveState = SaveState.Idle, rebooting = false, banner = null, error = null, demo = false,
                sectionsBusy = emptySet(), deletingProfiles = emptySet(), writingParental = emptySet(), refreshing = false,
            )
        }
        if (c != null && !s.demo) viewModelScope.launch(Dispatchers.IO) { c.logout() }
    }
}
