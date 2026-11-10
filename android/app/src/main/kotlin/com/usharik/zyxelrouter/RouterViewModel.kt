package com.usharik.zyxelrouter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { STARTING, LOGIN, MAIN }

sealed interface SaveState {
    data object Idle : SaveState
    data object InFlight : SaveState
    data object Done : SaveState
    data class Failed(val message: String) : SaveState
}

data class UiState(
    val phase: Phase = Phase.STARTING,
    val address: String = "192.168.1.1",
    val username: String = "admin",
    val password: String = "",
    val remember: Boolean = true,
    val routerName: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val banner: String? = null,
    val snapshot: RouterSnapshot? = null,
    val updatedAt: Long = 0,
    val parental: ParentalControl? = null,
    val routing: Routing? = null,
    val log: List<LogEntry>? = null,
    val sectionBusy: Boolean = false,
    val refreshing: Boolean = false,   // обновление по кнопке; фоновый опрос индикатор не показывает
    val saveState: SaveState = SaveState.Idle,
)

class RouterViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CredentialStore(app)
    val state = MutableStateFlow(UiState(address = store.address, username = store.username))
    private var client: RouterClient? = null
    private var pollJob: Job? = null
    private var foreground = false
    private var rebootNoticeUntil = 0L
    private var parentalWrites = 0   // только с главного потока
    private var writeError: String? = null   // текст ошибки записи; держится, пока запись не пройдёт или баннер не закроют

    /** Действие с записью; при устаревшем токене входит заново. */
    private suspend fun <T> recovering(c: RouterClient, retry: Boolean = true, block: () -> T): T {
        val s = state.value
        return withContext(Dispatchers.IO) {
            val generation = c.sessionGeneration
            withSessionRecovery(retry, {
                if (s.password.isEmpty()) throw RouterException.SessionExpired
                c.reloginIfUnchanged(generation, s.username, s.password)
            }, block)
        }
    }

    /** Отказ в повторном входе нельзя глушить баннером: дальнейшие попытки могут заблокировать вход на роутере. */
    private fun sessionLost(e: RouterException): Boolean {
        if (e !is RouterException.InvalidCredentials && e !is RouterException.Locked) return false
        loginFailed(e)
        return true
    }

    // Background refreshes must not wipe the reboot notice while the router is going down.
    private fun bannerAfterRefresh(text: String?, current: String?) = bannerAfterPoll(text, current, System.currentTimeMillis() < rebootNoticeUntil, writeError)

    // Локальная история устройств: роутер забывает отключившиеся устройства, а мы их помним.
    private val devPrefs = app.getSharedPreferences("devices", android.content.Context.MODE_PRIVATE)
    private fun knownKey() = "known:" + state.value.address
    private var historyRev = 0   // чтобы перерисовать список после «забыть»

    private fun loadKnown(): MutableMap<String, org.json.JSONObject> {
        val map = linkedMapOf<String, org.json.JSONObject>()
        val raw = devPrefs.getString(knownKey(), null) ?: return map
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return map
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { map[it.optString("mac")] = it }
        return map
    }

    private fun recordDevices(devices: List<RouterDevice>) {
        if (devices.isEmpty()) return
        val known = loadKnown()
        val now = System.currentTimeMillis() / 1000
        for (d in devices) if (d.mac.isNotEmpty()) {
            known[d.mac] = org.json.JSONObject().put("mac", d.mac).put("name", d.name).put("type", d.type).put("connection", d.connection)
                .put("lastSeen", mergedLastSeen(d.online, known[d.mac]?.optLong("lastSeen") ?: 0, now))
        }
        val trimmed = known.values.sortedByDescending { it.optLong("lastSeen") }.take(200)
        devPrefs.edit().putString(knownKey(), org.json.JSONArray(trimmed).toString()).apply()
    }

    /** Устройства для показа: живые из роутера плюс известные ранее, которых сейчас нет (как офлайн). */
    fun displayDevices(): List<RouterDevice> {
        historyRev  // чтение, чтобы Compose пересобирал после forgetDevice
        val known = loadKnown()
        // Роутер ещё перечисляет недавно отключившиеся устройства; время берём из нашей истории.
        val live = (state.value.snapshot?.devices ?: emptyList()).map { d -> if (d.online) d else d.copy(lastSeen = known[d.mac]?.optLong("lastSeen") ?: 0) }
        val liveMacs = live.map { it.mac }.toSet()
        val offline = known.values.filter { it.optString("mac") !in liveMacs }
            .map { offlineDevice(it.optString("mac"), it.optString("name"), it.optString("type"), it.optString("connection"), it.optLong("lastSeen")) }
            .sortedBy { it.name.lowercase() }
        return live + offline
    }

    fun forgetDevice(mac: String) {
        val known = loadKnown()
        known.remove(mac)
        devPrefs.edit().putString(knownKey(), org.json.JSONArray(known.values.toList()).toString()).apply()
        historyRev++
        state.update { it.copy(updatedAt = System.currentTimeMillis()) }
    }

    init {
        viewModelScope.launch {
            probe()
            val saved = withContext(Dispatchers.IO) { store.readPassword() }
            if (saved != null) {
                state.update { it.copy(password = saved) }
                login()
            } else {
                state.update { it.copy(phase = Phase.LOGIN) }
            }
        }
    }

    fun edit(transform: (UiState) -> UiState) = state.update(transform)

    fun probe() {
        val address = state.value.address
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { RouterClient(address).basicInfo().let { "${it.model} · ${it.firmware}" } }.getOrDefault("")
            }
            if (state.value.address == address) state.update { it.copy(routerName = name) }
        }
    }

    suspend fun login() {
        val s = state.value
        state.update { it.copy(busy = true, error = null) }
        try {
            val snapshot = withContext(Dispatchers.IO) {
                val c = RouterClient(s.address)
                c.login(s.username, s.password)
                store.address = s.address
                store.username = s.username
                // Сбой Keystore не должен срывать удачный вход; пароль просто не запомнится.
                if (s.remember) runCatching { store.savePassword(s.password) } else store.clearPassword()
                client = c
                c.snapshot()
            }
            state.update { it.copy(phase = Phase.MAIN, snapshot = snapshot, updatedAt = System.currentTimeMillis(), banner = null, busy = false) }
            recordDevices(snapshot.devices)
            restartPolling()
        } catch (e: RouterException) {
            loginFailed(e)
        } catch (e: Exception) {
            // Keystore или разбор RSA-ключа могут бросить не-RouterException — не роняем приложение.
            loginFailed(RouterException.Failed("Не удалось войти: ${e.message}"))
        }
    }

    fun submitLogin() { if (!state.value.busy) viewModelScope.launch { login() } }

    // Saved credentials are dropped after a rejected login, so the app can never cause a router lockout by retrying.
    private fun loginFailed(e: RouterException) {
        if (e is RouterException.InvalidCredentials) {
            store.clearPassword()
            state.update { it.copy(password = "") }
        }
        pollJob?.cancel()
        client = null
        // Сбрасываем данные другого роутера, чтобы после перелогина не мелькали чужие профили/маршруты.
        state.update { it.copy(phase = Phase.LOGIN, snapshot = null, parental = null, routing = null, log = null, error = e.message, busy = false) }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        restartPolling()
    }

    private fun restartPolling() {
        pollJob?.cancel()
        if (!foreground || client == null) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                refresh()
            }
        }
    }

    fun refreshNow() {
        if (state.value.refreshing) return
        viewModelScope.launch {
            state.update { it.copy(refreshing = true) }
            try { refresh() } finally { state.update { it.copy(refreshing = false) } }
        }
    }

    private suspend fun refresh() {
        val c = client ?: return
        val generation = c.sessionGeneration
        try {
            val snapshot = withContext(Dispatchers.IO) { c.snapshot() }
            state.update { it.copy(snapshot = snapshot, updatedAt = System.currentTimeMillis(), banner = bannerAfterRefresh(null, it.banner)) }
            recordDevices(snapshot.devices)
        } catch (_: RouterException.SessionExpired) {
            relogin(c, generation)
        } catch (e: RouterException) {
            state.update { it.copy(banner = bannerAfterRefresh(e.message, it.banner)) }
        }
    }

    private suspend fun relogin(c: RouterClient, generation: Int) {
        val s = state.value
        if (s.password.isEmpty()) return loginFailed(RouterException.SessionExpired)
        try {
            val snapshot = withContext(Dispatchers.IO) {
                c.reloginIfUnchanged(generation, s.username, s.password)
                c.snapshot()
            }
            state.update { it.copy(snapshot = snapshot, updatedAt = System.currentTimeMillis(), banner = bannerAfterRefresh(null, it.banner)) }
            recordDevices(snapshot.devices)
        } catch (e: RouterException.InvalidCredentials) {
            loginFailed(e)
        } catch (e: RouterException.Locked) {
            loginFailed(e)
        } catch (e: RouterException) {
            state.update { it.copy(banner = bannerAfterRefresh(e.message, it.banner)) }
        }
    }

    fun loadParental(keepBanner: Boolean = false) {
        val c = client ?: return
        if (parentalWrites > 0) return   // запись сама перечитает состояние; ранний ответ сбросил бы переключатель
        viewModelScope.launch {
            state.update { it.copy(sectionBusy = true) }
            try {
                val pc = withContext(Dispatchers.IO) { c.parentalControl() }
                state.update { it.copy(parental = if (parentalWrites == 0) pc else it.parental, sectionBusy = false, banner = if (keepBanner) it.banner else bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                state.update { it.copy(sectionBusy = false, banner = bannerAfterRefresh(e.message, it.banner)) }
            }
        }
    }

    fun loadRouting() {
        val c = client ?: return
        viewModelScope.launch {
            state.update { it.copy(sectionBusy = true) }
            try {
                val r = withContext(Dispatchers.IO) { c.routing() }
                state.update { it.copy(routing = r, sectionBusy = false, banner = bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                state.update { it.copy(sectionBusy = false, banner = bannerAfterRefresh(e.message, it.banner)) }
            }
        }
    }

    fun loadLog() {
        val c = client ?: return
        viewModelScope.launch {
            state.update { it.copy(sectionBusy = true) }
            try {
                val l = withContext(Dispatchers.IO) { c.systemLog() }
                state.update { it.copy(log = l, sectionBusy = false, banner = bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                state.update { it.copy(sectionBusy = false, banner = bannerAfterRefresh(e.message, it.banner)) }
            }
        }
    }

    /**
     * Запись родительского контроля с мгновенным откликом. Откатывается только изменённое поле, а ответ роутера
     * применяется после последней из параллельных записей, чтобы соседние переключатели не прыгали.
     */
    private fun writeParental(optimistic: (ParentalControl) -> ParentalControl, rollback: (ParentalControl) -> ParentalControl, op: (RouterClient) -> Unit) {
        val c = client ?: return
        state.update { it.copy(parental = it.parental?.let(optimistic)) }
        parentalWrites++
        viewModelScope.launch {
            try {
                val pc = recovering(c) { op(c); c.parentalControl() }
                parentalWrites--
                writeError = null
                if (parentalWrites == 0) state.update { it.copy(parental = pc, banner = bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                parentalWrites--
                if (sessionLost(e)) return@launch
                writeError = e.message
                state.update { it.copy(parental = it.parental?.let(rollback), banner = e.message) }
                loadParental(keepBanner = true)
            }
        }
    }

    fun setParentalEnabled(on: Boolean) = writeParental({ it.copy(enabled = on) }, { it.copy(enabled = !on) }) { it.setParentalEnabled(on) }

    fun setProfileEnabled(index: Int, on: Boolean) =
        writeParental({ it.withProfileEnabled(index, on) }, { it.withProfileEnabled(index, !on) }) { it.setProfileEnabled(index, on) }

    /** Результат хранится здесь, а не в колбэках диалога: иначе после поворота экрана ответ уйдёт в уничтоженный экран. */
    fun saveProfile(index: Int?, name: String, macs: List<String>, days: List<String>, timeRangeList: String, enabled: Boolean) {
        if (state.value.saveState == SaveState.InFlight) return
        val c = client ?: return state.update { it.copy(saveState = SaveState.Failed("Сессия потеряна, войдите снова.")) }
        state.update { it.copy(saveState = SaveState.InFlight) }
        viewModelScope.launch {
            try {
                // Создание не повторяем автоматически: при 504 профиль мог уже создаться, и повтор сделал бы дубль.
                recovering(c, retry = index != null) { c.saveProfile(index, name, macs, days, timeRangeList, enabled) }
            } catch (e: RouterException) {
                if (!sessionLost(e)) state.update { it.copy(saveState = SaveState.Failed(e.message ?: "Ошибка")) }   // диалог открыт, можно повторить
                return@launch
            }
            // Запись прошла. Ошибку перечитывания не показываем в диалоге, иначе повтор создаст дубль.
            writeError = null
            state.update { it.copy(saveState = SaveState.Done) }
            try {
                val pc = withContext(Dispatchers.IO) { c.parentalControl() }
                state.update { it.copy(parental = pc, banner = bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                state.update { it.copy(banner = e.message) }
            }
        }
    }

    fun resetSaveState() = state.update { it.copy(saveState = SaveState.Idle) }

    fun deleteProfile(index: Int) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                val pc = recovering(c) { c.deleteProfile(index); c.parentalControl() }
                writeError = null
                state.update { it.copy(parental = pc, banner = bannerAfterRefresh(null, it.banner)) }
            } catch (e: RouterException) {
                if (sessionLost(e)) return@launch
                writeError = e.message
                state.update { it.copy(banner = e.message) }
                loadParental(keepBanner = true)
            }
        }
    }

    fun reboot() {
        val c = client ?: return
        viewModelScope.launch {
            try {
                recovering(c, retry = false) { c.reboot() }
                rebootNoticeUntil = System.currentTimeMillis() + 180_000
                state.update { it.copy(banner = "Роутер перезагружается. Интернет вернётся через несколько минут.") }
            } catch (e: RouterException) {
                if (sessionLost(e)) return@launch
                writeError = e.message
                state.update { it.copy(banner = e.message) }
            }
        }
    }

    fun logout() {
        val c = client
        pollJob?.cancel()
        client = null
        store.clearPassword()
        state.update { it.copy(phase = Phase.LOGIN, password = "", snapshot = null, parental = null, routing = null, log = null) }
        if (c != null) viewModelScope.launch(Dispatchers.IO) { c.logout() }
    }
}
