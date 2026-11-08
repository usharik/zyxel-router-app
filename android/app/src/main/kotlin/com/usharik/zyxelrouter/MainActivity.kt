package com.usharik.zyxelrouter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF2E9E4F)
private val Orange = Color(0xFFD9822B)

class MainActivity : ComponentActivity() {
    private val viewModel: RouterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Root(viewModel) }
            }
        }
    }
}

@Composable
private fun Root(vm: RouterViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) vm.setForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    when (state.phase) {
        Phase.STARTING -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Подключение к роутеру…")
        }
        Phase.LOGIN -> LoginScreen(state, vm)
        Phase.MAIN -> MainScreen(state, vm)
    }
}

@Composable
private fun LoginScreen(s: UiState, vm: RouterViewModel) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Router, null, Modifier.size(64.dp), MaterialTheme.colorScheme.primary)
        Text("Zyxel Router", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(s.routerName.ifEmpty { "Роутер пока не найден" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(s.address, { v -> vm.edit { it.copy(address = v) }; vm.probe() }, Modifier.fillMaxWidth(), label = { Text("Адрес роутера") }, singleLine = true)
        OutlinedTextField(s.username, { v -> vm.edit { it.copy(username = v) } }, Modifier.fillMaxWidth(), label = { Text("Пользователь") }, singleLine = true)
        OutlinedTextField(
            s.password, { v -> vm.edit { it.copy(password = v) } }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Запомнить пароль", Modifier.weight(1f))
            Switch(s.remember, { v -> vm.edit { it.copy(remember = v) } })
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(vm::submitLogin, Modifier.fillMaxWidth(), enabled = !s.busy && s.password.isNotEmpty() && s.username.isNotEmpty()) {
            if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Войти")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(s: UiState, vm: RouterViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val tabs = listOf<Pair<String, ImageVector>>(
        "Обзор" to Icons.Default.Home, "Устройства" to Icons.Default.Devices, "Wi-Fi" to Icons.Default.Wifi,
        "Контроль" to Icons.Default.Shield, "Маршруты" to Icons.Default.Route, "Журнал" to Icons.Default.Article,
    )
    LaunchedEffect(tab) {
        if (tab == 3) vm.loadParental()
        if (tab == 4) vm.loadRouting()
        if (tab == 5 && s.log == null) vm.loadLog()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Zyxel Router") },
                actions = {
                    IconButton(vm::refreshNow) { Icon(Icons.Default.Refresh, "Обновить") }
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Скопировать диагностику без личных данных") }, {
                            clipboard.setText(AnnotatedString(s.snapshot?.schema.orEmpty()))
                            menu = false
                        })
                        DropdownMenuItem({ Text("Перезагрузить роутер…") }, { confirm = true; menu = false })
                        DropdownMenuItem({ Text("Выйти") }, { menu = false; vm.logout() })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(tab == index, { tab = index }, { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            s.banner?.let {
                Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = Orange, style = MaterialTheme.typography.bodyMedium)
            }
            when (tab) {
                0 -> s.snapshot?.let { Overview(it, s.updatedAt) }
                1 -> s.snapshot?.let { Devices(it, vm) }
                2 -> s.snapshot?.let { Wifi(it) }
                3 -> ParentalScreen(s, vm)
                4 -> RoutingScreen(s, vm)
                else -> LogScreen(s, vm)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Перезагрузить роутер?") },
            text = { Text("Интернет и Wi-Fi пропадут на несколько минут.") },
            confirmButton = { TextButton({ confirm = false; vm.reboot() }) { Text("Перезагрузить") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } },
        )
    }
}

private fun duration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = seconds % 86400 / 3600
    val m = seconds % 3600 / 60
    return (if (d > 0) "$d д " else "") + (if (d > 0 || h > 0) "$h ч " else "") + "$m мин"
}

private fun speed(kbps: Long) = if (kbps > 0) String.format(Locale("ru"), "%.1f Мбит/с", kbps / 1000.0) else "—"

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), content = content) }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, color = valueColor)
    }
}

@Composable
private fun Overview(s: RouterSnapshot, updatedAt: Long) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionCard("Интернет") {
            InfoRow("Состояние", if (s.internetUp) "Подключён" else "Нет подключения", if (s.internetUp) Green else MaterialTheme.colorScheme.error)
            InfoRow("Внешний IP", s.wanIP.ifEmpty { "—" })
            InfoRow("Загрузка", speed(s.downKbps))
            InfoRow("Отдача", speed(s.upKbps))
            InfoRow("Тип", listOf(s.linkType, s.encapsulation).filter { it.isNotEmpty() }.joinToString(" · "))
            InfoRow("DNS", s.dns.ifEmpty { "—" })
            InfoRow("Подключён", duration(s.internetUptime))
        }
        SectionCard("Сеть") {
            InfoRow("Устройств в сети", s.devices.count { it.online }.toString())
            InfoRow("Включено сетей Wi-Fi", "${s.wifi.count { it.enabled }} из ${s.wifi.size}")
        }
        SectionCard("Роутер") {
            InfoRow("Модель", s.model)
            InfoRow("Прошивка", s.firmware)
            InfoRow("Работает", duration(s.uptime))
            InfoRow("Обновлено", DateFormat.getTimeInstance().format(Date(updatedAt)))
        }
    }
}

@Composable
private fun ListRow(icon: ImageVector, active: Boolean, title: String, guest: Boolean, subtitle: String, status: String, detail: String, blocked: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold)
                if (guest) Text("  гость", color = Orange, style = MaterialTheme.typography.labelSmall)
                if (blocked) Text("  заблок.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (active) Green else MaterialTheme.colorScheme.outline)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun deviceIcon(d: RouterDevice): ImageVector = when (d.type) {
    "Телефон или планшет" -> Icons.Default.Smartphone
    "Компьютер" -> Icons.Default.Computer
    "Принтер" -> Icons.Default.Print
    "ТВ-приставка" -> Icons.Default.Tv
    "IoT-устройство" -> Icons.Default.Sensors
    else -> if (d.connection.contains("wi-fi", ignoreCase = true)) Icons.Default.Wifi else Icons.Default.SettingsEthernet
}

private fun lastSeenLabel(epoch: Long): String {
    if (epoch <= 0) return "давно"
    val diff = System.currentTimeMillis() / 1000 - epoch
    return when {
        diff < 90 -> "только что"
        diff < 3600 -> "${diff / 60} мин назад"
        diff < 86400 -> "${diff / 3600} ч назад"
        else -> "${diff / 86400} дн назад"
    }
}

@Composable
private fun Devices(s: RouterSnapshot, vm: RouterViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlineOnly by rememberSaveable { mutableStateOf(false) }   // по умолчанию показываем и офлайн
    var selected by remember { mutableStateOf<RouterDevice?>(null) }
    val all = vm.displayDevices()
    val devices = all.filter { d ->
        (!onlineOnly || d.online) && (query.isEmpty() || listOf(d.name, d.ip, d.mac).any { it.contains(query, ignoreCase = true) })
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), label = { Text("Поиск по имени, IP или MAC") }, singleLine = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Только в сети", Modifier.weight(1f))
            Switch(onlineOnly, { onlineOnly = it })
        }
        if (devices.isEmpty()) {
            Text("Устройства не найдены", Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(devices, key = { it.mac.ifEmpty { it.name + it.ip } }) { d ->
                Box(Modifier.clickable { selected = d }) {
                    ListRow(
                        deviceIcon(d), d.online, d.name, d.guest,
                        listOfNotNull(d.type.takeIf { it != "Неизвестно" }, listOf(d.ip, d.mac).filter { it.isNotEmpty() }.joinToString(" · ")).joinToString(" · "),
                        if (d.online) "В сети" else "Не в сети",
                        if (d.online) d.network.ifEmpty { d.connection } else "был: ${lastSeenLabel(d.lastSeen)}", blocked = d.blocked,
                    )
                }
            }
        }
    }
    selected?.let { d ->
        DeviceDetailsDialog(d, onForget = if (!d.online) ({ vm.forgetDevice(d.mac); selected = null }) else null) { selected = null }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DeviceDetailsDialog(d: RouterDevice, onForget: (() -> Unit)? = null, onClose: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("Закрыть") } },
        dismissButton = { if (onForget != null) TextButton(onForget) { Text("Забыть", color = MaterialTheme.colorScheme.error) } },
        icon = { Icon(deviceIcon(d), null) },
        title = { Text(d.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(d.type, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                DetailRow("Состояние", if (d.online) "В сети" else "Не в сети")
                if (!d.online) DetailRow("Был в сети", lastSeenLabel(d.lastSeen))
                DetailRow("Где", if (d.network.isEmpty()) d.connection else "${d.network} (${d.connection})")
                if (d.ip.isNotEmpty()) DetailRow("IP-адрес", d.ip)
                DetailRow("MAC", d.mac)
                if (d.ipv6) DetailRow("IPv6", "есть")
                DetailRow("Адрес", if (d.staticIP) "статический" else "по DHCP")
                if (d.blocked) DetailRow("Родительский контроль", "заблокирован")
                if (d.connection.contains("wi-fi", ignoreCase = true)) {
                    if (d.signal > 0) DetailRow("Сигнал", "▮".repeat(d.signal) + "▯".repeat((5 - d.signal).coerceAtLeast(0)))
                    if (d.snr > 0) DetailRow("Сигнал/шум", "${d.snr} дБ")
                    if (d.phyRate > 0) DetailRow("Скорость канала", "${d.phyRate} Мбит/с")
                    if (d.wifiStandard.isNotEmpty()) DetailRow("Стандарт", "802.11${d.wifiStandard}")
                }
                DetailRow("Принято", formatBytes(d.bytesDown))
                DetailRow("Отправлено", formatBytes(d.bytesUp))
                if (d.vendorClass.isNotEmpty()) DetailRow("Производитель (DHCP)", d.vendorClass)
                Spacer(Modifier.height(4.dp))
                TextButton({ showAll = !showAll }) { Text(if (showAll) "Скрыть все поля" else "Все поля (${d.details.size})") }
                if (showAll) d.details.toSortedMap().forEach { (k, v) -> DetailRow(k, v) }
            }
        },
    )
}

@Composable
private fun Wifi(s: RouterSnapshot) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(s.wifi, key = { it.band + it.ssid }) { w ->
            ListRow(
                if (w.enabled) Icons.Default.Wifi else Icons.Default.WifiOff, w.enabled, w.ssid, w.guest,
                listOfNotNull(w.band, w.channel.takeIf { it.isNotEmpty() }?.let { "канал $it" }, w.security.takeIf { it.isNotEmpty() }).joinToString(" · "),
                if (w.enabled) "Включена" else "Выключена", "",
            )
        }
    }
}

private val RuDays = listOf("Mon" to "Пн", "Tue" to "Вт", "Wed" to "Ср", "Thu" to "Чт", "Fri" to "Пт", "Sat" to "Сб", "Sun" to "Вс")
private fun ruDays(days: List<String>) = days.joinToString(" ") { d -> RuDays.firstOrNull { it.first == d }?.second ?: d }

@Composable
private fun SectionHint(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ParentalScreen(s: UiState, vm: RouterViewModel) {
    val pc = s.parental
    var editor by remember { mutableStateOf<ParentalProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ParentalProfile?>(null) }
    if (pc == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Родительский контроль", fontWeight = FontWeight.SemiBold)
                        Text(if (pc.enabled) "Включён" else "Выключен", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(pc.enabled, { vm.setParentalEnabled(it) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Профили", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton({ creating = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Добавить") }
            }
        }
        if (pc.profiles.isEmpty()) item { SectionHint("Профилей пока нет.") }
        items(pc.profiles, key = { it.index }) { p ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifEmpty { "Профиль ${p.index}" }, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${p.macs.size} устройств · ${ruDays(p.days)} · ${p.times.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (p.blocksSites || p.blocksServices) {
                            Text(
                                listOfNotNull(if (p.blocksSites) "сайты" else null, if (p.blocksServices) "сервисы" else null).joinToString(", ", prefix = "Ограничивает: "),
                                style = MaterialTheme.typography.bodySmall, color = Orange,
                            )
                        }
                    }
                    Switch(p.enabled, { vm.setProfileEnabled(p.index, it) })
                    IconButton({ editor = p }) { Icon(Icons.Default.Edit, "Изменить") }
                    IconButton({ deleting = p }) { Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (creating || editor != null) {
        ProfileEditorDialog(editor, vm.displayDevices(), vm) { creating = false; editor = null }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить профиль?") },
            text = { Text("«${p.name.ifEmpty { "Профиль ${p.index}" }}» будет удалён.") },
            confirmButton = { TextButton({ vm.deleteProfile(p.index); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProfileEditorDialog(existing: ParentalProfile?, devices: List<RouterDevice>, vm: RouterViewModel, onClose: () -> Unit) {
    val dayCodes = RuDays.map { it.first }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    val initialMacs = existing?.macs?.map { it.replace("-", ":") } ?: emptyList()
    // listSaver — чтобы выбор устройств и дней переживал поворот экрана.
    val macs = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { initialMacs.toMutableStateList() }
    val days = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { (existing?.days ?: emptyList()).toMutableStateList() }
    val originalTimes = existing?.times ?: emptyList()
    val range = (originalTimes.firstOrNull() ?: "00:00-23:59").split("-")
    var start by rememberSaveable { mutableStateOf(normalizeTime(range.getOrElse(0) { "00:00" }) ?: "00:00") }
    var end by rememberSaveable { mutableStateOf(normalizeTime(range.getOrElse(1) { "23:59" }) ?: "23:59") }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var manualMac by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Новый профиль" else "Изменить профиль") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Устройства", style = MaterialTheme.typography.labelLarge)
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { if (macs.contains(d.mac)) macs.remove(d.mac) else macs.add(d.mac) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(macs.contains(d.mac), { if (it) macs.add(d.mac) else macs.remove(d.mac) })
                        Text(
                            d.name.ifEmpty { d.mac } + if (d.online) "" else " · не в сети",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (d.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Устройства не в сети (из профиля или добавленные вручную).
                val knownMacs = devices.map { it.mac }.toSet()
                macs.filter { it !in knownMacs }.forEach { mac ->
                    Row(Modifier.fillMaxWidth().clickable { macs.remove(mac) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(true, { if (!it) macs.remove(mac) })
                        Text("$mac · не в сети", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(manualMac, { manualMac = it }, Modifier.weight(1f), label = { Text("Добавить MAC вручную") }, singleLine = true)
                    TextButton(enabled = manualMac.isNotEmpty(), onClick = {
                        val m = normalizeMAC(manualMac)
                        if (m == null) error = "MAC в формате AA:BB:CC:DD:EE:FF." else { if (m !in macs) macs.add(m); manualMac = "" }
                    }) { Text("Добавить") }
                }
                Spacer(Modifier.height(8.dp))
                Text("Дни", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    dayCodes.forEach { code ->
                        val on = days.contains(code)
                        FilterChip(on, { if (on) days.remove(code) else days.add(code) }, { Text(RuDays.first { it.first == code }.second) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text("с") }, singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text("до") }, singleLine = true)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Профиль включён", Modifier.weight(1f))
                    Switch(enabled, { enabled = it })
                }
                if (originalTimes.size > 1) {
                    Text("У профиля несколько интервалов; сохранение оставит только первый.", color = Orange, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val orderedDays = dayCodes.filter { days.contains(it) }
                val sNorm = normalizeTime(start); val eNorm = normalizeTime(end)
                val sMin = sNorm?.let { minutesOfTime(it) }; val eMin = eNorm?.let { minutesOfTime(it) }
                error = when {
                    name.isBlank() -> "Укажите название профиля."
                    macs.isEmpty() -> "Выберите хотя бы одно устройство."
                    macs.size > 32 -> "Не больше 32 устройств."
                    orderedDays.isEmpty() -> "Выберите хотя бы один день."
                    sNorm == null || eNorm == null || sMin == null || eMin == null -> "Время в формате ЧЧ:ММ."
                    sMin >= eMin -> "Начало должно быть раньше конца. Ночной интервал задайте двумя профилями."
                    else -> null
                }
                if (error == null) {
                    val single = "$sNorm-$eNorm"
                    // Если у профиля было несколько интервалов и первый не менялся — сохраняем все.
                    val timeRangeList = if (originalTimes.size > 1 && originalTimes.first() == single) originalTimes.joinToString(",") else single
                    saving = true
                    vm.saveProfile(existing?.index, name.trim(), macs.toList(), orderedDays, timeRangeList, enabled, onSaved = onClose, onError = { saving = false; error = it })
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClose) { Text("Отмена") } },
    )
}

@Composable
private fun RouteGroup(title: String, items: List<RouteItem>) {
    if (items.isEmpty()) return
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    items.forEach { r ->
        ListRow(
            if (r.enabled) Icons.Default.Route else Icons.Default.Block, r.enabled, r.title, false, r.subtitle,
            if (r.enabled) "Активен" else "Выключен", "",
        )
    }
}

@Composable
private fun RoutingScreen(s: UiState, vm: RouterViewModel) {
    val r = s.routing
    if (r == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val empty = r.staticRoutes.isEmpty() && r.dnsRoutes.isEmpty() && r.policyRoutes.isEmpty() && r.rip.isEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (empty) SectionHint("Маршруты не настроены.")
        RouteGroup("Статические маршруты", r.staticRoutes)
        RouteGroup("Маршруты DNS", r.dnsRoutes)
        RouteGroup("Маршруты по политикам", r.policyRoutes)
        RouteGroup("RIP", r.rip)
        SectionHint("Раздел только для чтения: изменение маршрутов может оборвать связь, добавим его отдельно и очень осторожно.")
    }
}

private fun severityColor(s: String, scheme: androidx.compose.material3.ColorScheme): Color = when (s) {
    "emerg", "alert", "crit", "err" -> scheme.error
    "warning", "warn" -> Orange
    "debug" -> scheme.outline
    else -> scheme.onSurface
}

@Composable
private fun LogScreen(s: UiState, vm: RouterViewModel) {
    val log = s.log
    var showStats by rememberSaveable { mutableStateOf(true) }
    var period by rememberSaveable { mutableStateOf(LogPeriod.DAY) }
    var query by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf("") }
    if (log == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedTwo("Статистика", "Записи", showStats) { showStats = it }
            Spacer(Modifier.weight(1f))
            IconButton({ vm.loadLog() }) { Icon(Icons.Default.Refresh, "Обновить") }
        }
        if (showStats) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text("Всего записей: ${log.size}", Modifier.padding(16.dp, 4.dp), fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogPeriod.values().forEach { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
                    }
                }
                item { Text("По периодам", Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(logAggregate(log, period)) { c -> DetailRow(c.label, c.count.toString()).also { Divider(Modifier.padding(horizontal = 16.dp)) } }
                item { Text("По важности", Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(logCountsBy(log) { it.severity }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.label, color = severityColor(c.label, scheme), style = MaterialTheme.typography.bodySmall)
                        Text(c.count.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text("Категории", Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(logCountsBy(log) { it.category }.take(10)) { c -> DetailRow(c.label, c.count.toString()) }
            }
        } else {
            val entries = log.filter { (severity.isEmpty() || it.severity == severity) && (query.isEmpty() || it.raw.contains(query, ignoreCase = true)) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Поиск по тексту") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("" to "Все", "err" to "err", "warning" to "warning", "notice" to "notice", "info" to "info", "debug" to "debug").forEach { (v, l) ->
                        FilterChip(severity == v, { severity = v }, { Text(l) })
                    }
                }
            }
            if (entries.isEmpty()) SectionHint("Записей нет")
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.category, color = scheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(e.severity, color = severityColor(e.severity, scheme), style = MaterialTheme.typography.labelSmall)
                            Text("${logDayLabel(e)} ${e.time}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(e.message.ifEmpty { e.raw }, style = MaterialTheme.typography.bodySmall)
                    }
                    Divider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SegmentedTwo(a: String, b: String, first: Boolean, onChange: (Boolean) -> Unit) {
    Row {
        FilterChip(first, { onChange(true) }, { Text(a) })
        Spacer(Modifier.width(6.dp))
        FilterChip(!first, { onChange(false) }, { Text(b) })
    }
}
