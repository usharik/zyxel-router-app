package com.usharik.zyxelrouter

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// Цвета состояний зависят от темы: один оттенок на светлом фоне даёт контраст ~3:1, на тёмном — ~5:1,
// а мелкому тексту нужно не меньше 4,5:1.
private val Green: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FCF8A) else Color(0xFF1E7A3C)
private val Orange: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF2A65A) else Color(0xFF9A5A0E)

/** Стиль, при котором самая длинная из подписей входит в [available] одной строкой; размер общий для всех подписей. */
@Composable
internal fun fittedStyle(labels: List<String>, available: Dp, base: TextStyle): TextStyle {
    val measurer = rememberTextMeasurer()
    val availablePx = with(LocalDensity.current) { available.toPx() }
    var style = base
    // Несколько итераций: ширина глифов из-за округления масштабируется не строго линейно.
    repeat(3) {
        val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        if (widest <= availablePx) return style
        val ratio = availablePx / widest * 0.98f
        style = style.copy(fontSize = style.fontSize * ratio, letterSpacing = if (style.letterSpacing.isSp) style.letterSpacing * ratio else style.letterSpacing)
    }
    return style
}

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

/** Строка «подпись + переключатель», где нажимается вся строка, а не только сам переключатель. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, null)
    }
}

@Composable
private fun LoginScreen(s: UiState, vm: RouterViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val canSubmit = !s.busy && s.password.isNotEmpty() && s.username.isNotEmpty()
    // Box центрирует форму, а Column прокручивается, когда она не помещается (клавиатура, альбомная ориентация).
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), Alignment.Center) { Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Router, null, Modifier.size(64.dp), MaterialTheme.colorScheme.primary)
        Text("Zyxel Router", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(s.routerName.ifEmpty { "Роутер пока не найден" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            s.address, { v -> vm.edit { it.copy(address = v) }; vm.probe() }, Modifier.fillMaxWidth(), label = { FieldLabel("Адрес роутера") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            s.username, { v -> vm.edit { it.copy(username = v) } }, Modifier.fillMaxWidth(), label = { FieldLabel("Пользователь") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            s.password, { v -> vm.edit { it.copy(password = v) } }, Modifier.fillMaxWidth(), label = { FieldLabel("Пароль") }, singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) vm.submitLogin() }),
            trailingIcon = {
                IconButton({ showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "Скрыть пароль" else "Показать пароль")
                }
            },
        )
        SwitchRow("Запомнить пароль", s.remember) { v -> vm.edit { it.copy(remember = v) } }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(vm::submitLogin, Modifier.fillMaxWidth(), enabled = canSubmit) {
            if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Войти")
        }
    } }
}

/** Вкладка в верхней панели невысокого экрана: значок над подписью, высота не меньше 48 dp. */
@Composable
private fun CompactTab(selected: Boolean, label: String, icon: ImageVector, style: TextStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick).padding(vertical = 4.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(22.dp), color)
        Text(label, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** Подпись поля ввода: длинная не должна раздувать поле на две строки. */
@Composable
private fun FieldLabel(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(s: UiState, vm: RouterViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabs = listOf<Pair<String, ImageVector>>(
        "Обзор" to Icons.Default.Home, "Устройства" to Icons.Default.Devices, "Wi-Fi" to Icons.Default.Wifi,
        "Контроль" to Icons.Default.Shield, "Маршруты" to Icons.Default.Route, "Журнал" to Icons.Default.Article,
    )
    val configuration = LocalConfiguration.current
    // На невысоком экране вкладки перемещаются в верхнюю панель: так видны все шесть, и на содержимое остаётся вся высота.
    val compact = configuration.screenHeightDp < 480
    // В нижней панели между пунктами 8 dp; в верхней часть ширины занимают две кнопки действий по 48 dp.
    val navLabelWidth = if (compact) (configuration.screenWidthDp.dp - 96.dp) / tabs.size - 8.dp
        else (configuration.screenWidthDp.dp - 8.dp * (tabs.size - 1)) / tabs.size
    val navLabelStyle = fittedStyle(tabs.map { it.first }, navLabelWidth, MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp))
    LaunchedEffect(tab) {
        if (tab == 3) vm.loadParental()
        if (tab == 4) vm.loadRouting()
        if (tab == 5 && s.log == null) vm.loadLog()
    }
    val actions: @Composable RowScope.() -> Unit = {
        IconButton(vm::refreshNow) { Icon(Icons.Default.Refresh, "Обновить") }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Ещё") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Скопировать диагностику без личных данных") }, {
                    clipboard.setText(AnnotatedString(s.snapshot?.schema.orEmpty()))
                    menu = false
                    // С Android 13 система сама показывает уведомление о копировании.
                    if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar("Диагностика скопирована") }
                })
                DropdownMenuItem({ Text("Перезагрузить роутер…") }, { confirm = true; menu = false })
                DropdownMenuItem({ Text("Выйти") }, { menu = false; vm.logout() })
            }
        }
    }

    Scaffold(
        topBar = {
            if (compact) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp),
                        Arrangement.spacedBy(4.dp),
                        Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, (label, icon) ->
                            CompactTab(tab == index, label, icon, navLabelStyle, Modifier.weight(1f)) { tab = index }
                        }
                        actions()
                    }
                }
            } else {
                TopAppBar(title = { Text("Zyxel Router", maxLines = 1, overflow = TextOverflow.Ellipsis) }, actions = actions)
            }
        },
        bottomBar = {
            if (!compact) NavigationBar {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(tab == index, { tab = index }, { Icon(icon, null) }, label = { Text(label, style = navLabelStyle, maxLines = 1, softWrap = false) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Scaffold не учитывает клавиатуру: без imePadding она закрывает низ списков при поиске.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            // Полоска занимает место всегда, чтобы содержимое не прыгало при появлении индикатора.
            if (s.refreshing || s.sectionBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) else Spacer(Modifier.height(4.dp))
            s.banner?.let { Banner(it) { vm.edit { st -> st.copy(banner = null) } } }
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

/** Уведомление под панелью: ошибка опроса или сообщение о перезагрузке. Закрывается; при повторной ошибке появится снова. */
@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = scheme.tertiaryContainer) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = scheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp), color = scheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
            IconButton(onDismiss) { Icon(Icons.Default.Close, "Скрыть уведомление", tint = scheme.onTertiaryContainer) }
        }
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
        Text(label, Modifier.weight(2f, fill = false), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, Modifier.weight(3f, fill = false), color = valueColor, textAlign = TextAlign.End)
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
            InfoRow("Тип", listOf(s.linkType, s.encapsulation).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" })
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
internal fun ListRow(icon: ImageVector, active: Boolean, title: String, guest: Boolean, subtitle: String, status: String, detail: String, blocked: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            // FlowRow: метки уходят на следующую строку, а не отнимают ширину у длинного названия.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (guest) Text("гость", color = Orange, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                if (blocked) Text("заблок.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        // Ограничиваем ширину: длинное имя сети иначе вытеснит название устройства.
        Column(Modifier.widthIn(max = 128.dp), horizontalAlignment = Alignment.End) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (active) Green else MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
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

private fun deviceKey(d: RouterDevice) = d.mac.ifEmpty { d.name + d.ip }

@Composable
private fun Devices(s: RouterSnapshot, vm: RouterViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlineOnly by rememberSaveable { mutableStateOf(false) }   // по умолчанию показываем и офлайн
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val all = vm.displayDevices().distinctBy(::deviceKey)   // повторяющийся ключ уронил бы LazyColumn
    val devices = all.filter { d ->
        (!onlineOnly || d.online) && (query.isEmpty() || listOf(d.name, d.ip, d.mac).any { it.contains(query, ignoreCase = true) })
    }
    val (online, offline) = devices.partition { it.online }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, "Имя, IP или MAC", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        SwitchRow("Только в сети", onlineOnly, Modifier.padding(horizontal = 16.dp)) { onlineOnly = it }
        if (devices.isEmpty()) {
            Text(
                if (all.isEmpty()) "Роутер не сообщил ни об одном устройстве" else "Ничего не найдено",
                Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // Живые устройства и запомненные офлайн-устройства разделяем заголовками, когда есть и те и другие.
            fun group(title: String, list: List<RouterDevice>) {
                if (list.isEmpty()) return
                if (online.isNotEmpty() && offline.isNotEmpty()) item(key = "header:$title") {
                    Text("$title · ${list.size}", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                items(list, key = ::deviceKey) { d ->
                    Box(Modifier.clickable { selectedKey = deviceKey(d) }) {
                        ListRow(
                            deviceIcon(d), d.online, d.name, d.guest,
                            listOfNotNull(d.type.takeIf { it != "Неизвестно" }, listOf(d.ip, d.mac).filter { it.isNotEmpty() }.joinToString(" · ")).joinToString(" · "),
                            if (d.online) "В сети" else "Не в сети",
                            if (d.online) d.network.ifEmpty { d.connection } else "был: ${lastSeenLabel(d.lastSeen)}", blocked = d.blocked,
                        )
                    }
                }
            }
            group("В сети", online)
            group("Не в сети", offline)
        }
    }
    val liveMacs = s.devices.map { it.mac }.toSet()
    all.firstOrNull { deviceKey(it) == selectedKey }?.let { d ->
        // Пока устройство есть в ответе роутера, «забыть» бессмысленно: следующий опрос вернёт его в историю.
        DeviceDetailsDialog(d, onForget = if (!d.online && d.mac !in liveMacs) ({ vm.forgetDevice(d.mac); selectedKey = null }) else null) { selectedKey = null }
    }
}

/** Поле поиска с иконкой и кнопкой очистки. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), label = { FieldLabel(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Default.Clear, "Очистить") } },
        // Фильтр работает по мере ввода, поэтому кнопка «Поиск» на клавиатуре просто убирает её.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(2f, fill = false), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, Modifier.weight(3f, fill = false), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
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
        title = { Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) },
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
    if (s.wifi.isEmpty()) {
        SectionHint("Роутер не сообщил ни об одной сети Wi-Fi.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(s.wifi, key = { i, w -> "$i:${w.band}:${w.ssid}" }) { _, w ->
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
    // Храним индексы, а не сами профили: так открытые диалоги переживают поворот экрана.
    var editorIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deletingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    if (pc == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.toggleable(pc.enabled, role = Role.Switch, onValueChange = { vm.setParentalEnabled(it) }).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Родительский контроль", fontWeight = FontWeight.SemiBold)
                        Text(if (pc.enabled) "Включён" else "Выключен", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(pc.enabled, null)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pc.maxProfiles > 0) "Профили · ${pc.profiles.size} из ${pc.maxProfiles}" else "Профили",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
                TextButton({ creating = true }, enabled = canAddProfile(pc)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Добавить") }
            }
        }
        if (pc.profiles.isEmpty()) item { SectionHint("Профилей пока нет.") }
        if (!canAddProfile(pc)) item { SectionHint("Достигнут лимит роутера. Чтобы добавить профиль, удалите один из существующих.") }
        items(pc.profiles, key = { it.index }) { p ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name.ifEmpty { "Профиль ${p.index}" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Switch(p.enabled, { vm.setProfileEnabled(p.index, it) }, Modifier.padding(start = 8.dp, end = 12.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${plural(p.macs.size, "устройство", "устройства", "устройств")} · ${ruDays(p.days)} · ${p.times.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.blocksSites || p.blocksServices) {
                                Text(
                                    listOfNotNull(if (p.blocksSites) "сайты" else null, if (p.blocksServices) "сервисы" else null).joinToString(", ", prefix = "Ограничивает: "),
                                    style = MaterialTheme.typography.bodySmall, color = Orange,
                                )
                            }
                        }
                        IconButton({ editorIndex = p.index }) { Icon(Icons.Default.Edit, "Изменить") }
                        IconButton({ deletingIndex = p.index }) { Icon(Icons.Default.Delete, "Удалить", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    val editor = pc.profiles.firstOrNull { it.index == editorIndex }
    if (creating || editor != null) {
        ProfileEditorDialog(editor, vm.displayDevices(), s.saveState, vm) { creating = false; editorIndex = null }
    }
    pc.profiles.firstOrNull { it.index == deletingIndex }?.let { p ->
        AlertDialog(
            onDismissRequest = { deletingIndex = null },
            title = { Text("Удалить профиль?") },
            text = { Text("«${p.name.ifEmpty { "Профиль ${p.index}" }}» будет удалён.") },
            confirmButton = { TextButton({ vm.deleteProfile(p.index); deletingIndex = null }) { Text("Удалить") } },
            dismissButton = { TextButton({ deletingIndex = null }) { Text("Отмена") } },
        )
    }
}

/** Семь равных ячеек на всю ширину: FilterChip с минимальной шириной в диалог на узких экранах не помещаются. */
@Composable
internal fun DaySelector(selected: List<String>, onToggle: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 2.dp   // на 320 dp каждый dp идёт на ширину ячейки: она должна оставаться удобной для нажатия
        val cell = (maxWidth - gap * (RuDays.size - 1)) / RuDays.size
        val style = fittedStyle(RuDays.map { it.second }, cell - 4.dp, MaterialTheme.typography.labelLarge)
        val shape = RoundedCornerShape(8.dp)
        val scheme = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
            RuDays.forEach { (code, label) ->
                val on = code in selected
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                        .background(if (on) scheme.secondaryContainer else Color.Transparent)
                        .border(1.dp, if (on) Color.Transparent else scheme.outline, shape)
                        .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(code) }),
                    Alignment.Center,
                ) {
                    Text(label, style = style, color = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Кнопки начала и конца интервала; подпись подстраивается под ширину при крупном системном шрифте. */
@Composable
internal fun TimeRangeButtons(start: String, end: String, onStart: () -> Unit, onEnd: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val inset = 8.dp
        val labels = listOf("с $start" to onStart, "до $end" to onEnd)
        val style = fittedStyle(labels.map { it.first }, (maxWidth - gap) / 2 - inset * 2 - 2.dp, MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
            labels.forEach { (label, onClick) ->
                OutlinedButton(onClick, Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = inset)) {
                    Text(label, style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Строка выбора устройства: нажимается целиком, флажок без собственного обработчика — одна цель для TalkBack. */
@Composable
private fun DeviceCheckRow(label: String, checked: Boolean, online: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, null, Modifier.padding(horizontal = 12.dp))
        Text(
            label, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileEditorDialog(existing: ParentalProfile?, devices: List<RouterDevice>, saveState: SaveState, vm: RouterViewModel, onClose: () -> Unit) {
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
    var picking by rememberSaveable { mutableStateOf<String?>(null) }   // "start" или "end", пока открыт выбор времени
    val saving = saveState == SaveState.InFlight
    LaunchedEffect(saveState) {
        when (saveState) {
            SaveState.Done -> { vm.resetSaveState(); onClose() }
            is SaveState.Failed -> { error = saveState.message; vm.resetSaveState() }
            else -> Unit
        }
    }
    picking?.let { which ->
        val isStart = which == "start"
        TimeDialog(if (isStart) "Начало" else "Конец", if (isStart) start else end, { if (isStart) start = it else end = it; picking = null }) { picking = null }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Новый профиль" else "Изменить профиль") },
        text = {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { FieldLabel("Название") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Устройства", style = MaterialTheme.typography.labelLarge)
                    devices.forEach { d ->
                        DeviceCheckRow(d.name.ifEmpty { d.mac } + if (d.online) "" else " · не в сети", macs.contains(d.mac), d.online) {
                            if (it) macs.add(d.mac) else macs.remove(d.mac)
                        }
                    }
                    // Устройства не в сети (из профиля или добавленные вручную).
                    val knownMacs = devices.map { it.mac }.toSet()
                    macs.filter { it !in knownMacs }.forEach { mac ->
                        DeviceCheckRow("$mac · не в сети", true, false) { if (!it) macs.remove(mac) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(manualMac, { manualMac = it }, Modifier.weight(1f), label = { FieldLabel("MAC вручную") }, singleLine = true)
                        IconButton(enabled = manualMac.isNotEmpty(), onClick = {
                            val m = normalizeMAC(manualMac)
                            if (m == null) error = "MAC в формате AA:BB:CC:DD:EE:FF." else { if (m !in macs) macs.add(m); manualMac = ""; error = null }
                        }) { Icon(Icons.Default.Add, "Добавить MAC") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Дни", style = MaterialTheme.typography.labelLarge)
                    DaySelector(days) { code -> if (code in days) days.remove(code) else days.add(code) }
                    Spacer(Modifier.height(8.dp))
                    TimeRangeButtons(start, end, { picking = "start" }, { picking = "end" })
                    SwitchRow("Профиль включён", enabled, Modifier.padding(top = 4.dp)) { enabled = it }
                    if (originalTimes.size > 1) {
                        Text("У профиля несколько интервалов; сохранение оставит только первый.", color = Orange, style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Ошибка вне прокрутки: иначе после «Сохранить» она оказывается ниже видимой области.
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val orderedDays = dayCodes.filter { days.contains(it) }
                error = validateProfile(name, macs.size, orderedDays.size, start, end)
                if (error == null) {
                    val single = "${normalizeTime(start)}-${normalizeTime(end)}"
                    // Если у профиля было несколько интервалов и первый не менялся — сохраняем все.
                    val timeRangeList = if (originalTimes.size > 1 && originalTimes.first() == single) originalTimes.joinToString(",") else single
                    vm.saveProfile(existing?.index, name.trim(), macs.toList(), orderedDays, timeRangeList, enabled)
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClose) { Text("Отмена") } },
    )
}

/** Часы или минуты из выпадающего списка: на невысоком экране без клавиатуры, которая закрыла бы окно. */
@Composable
private fun TimePart(value: Int, range: IntRange, label: String, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "$label: $value" }) {
            Text(String.format(Locale.ROOT, "%02d", value), style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false)
        }
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 240.dp)) {
            range.forEach { v -> DropdownMenuItem({ Text(String.format(Locale.ROOT, "%02d", v)) }, { onChange(v); open = false }) }
        }
    }
}

/** Выбор времени: циферблат; где он не помещается — ввод цифрами, а на совсем невысоком экране — списки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val minutes = (minutesOfTime(value) ?: 0).coerceIn(0, 23 * 60 + 59)   // циферблат не умеет 24:00
    val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
    val height = LocalConfiguration.current.screenHeightDp
    val dialFits = height >= 560
    // В альбомной ориентации телефона клавиатура ввода цифрами закрывает всё окно.
    val useLists = height < 480
    var dial by rememberSaveable { mutableStateOf(dialFits) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dialFits) IconButton({ dial = !dial }) {
                    Icon(if (dial) Icons.Default.Keyboard else Icons.Default.Schedule, if (dial) "Ввести цифрами" else "Показать циферблат")
                }
            }
        },
        text = {
            Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), Alignment.Center) {
                when {
                    dial && dialFits -> TimePicker(state, layoutType = TimePickerLayoutType.Vertical)
                    useLists -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TimePart(state.hour, 0..23, "Часы", Modifier.width(96.dp)) { state.hour = it }
                        Text(":", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.headlineSmall)
                        TimePart(state.minute, 0..59, "Минуты", Modifier.width(96.dp)) { state.minute = it }
                    }
                    else -> TimeInput(state)
                }
            }
        },
        confirmButton = { TextButton({ onPick(formatTime(state.hour, state.minute)) }) { Text("Готово") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
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

@Composable
private fun severityColor(s: String): Color = when (s) {
    "emerg", "alert", "crit", "err" -> MaterialTheme.colorScheme.error
    "warning", "warn" -> Orange
    "debug" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurface
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
            SegmentedTwo("Статистика", "Записи", showStats, Modifier.weight(1f)) { showStats = it }
            IconButton({ vm.loadLog() }) { Icon(Icons.Default.Refresh, "Обновить") }
        }
        if (showStats) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { Text("Всего записей: ${log.size}", Modifier.padding(16.dp, 4.dp), fontWeight = FontWeight.SemiBold) }
                item {
                    // Переключатель периода стоит под заголовком раздела, которым он управляет.
                    Text("По периодам", Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogPeriod.values().forEach { p -> FilterChip(period == p, { period = p }, { Text(p.label) }) }
                    }
                }
                items(logAggregate(log, period)) { c ->
                    DetailRow(c.label, c.count.toString(), Modifier.padding(horizontal = 16.dp))
                    Divider(Modifier.padding(horizontal = 16.dp))
                }
                item { Text("По важности", Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(logCountsBy(log) { it.severity }) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.label, color = severityColor(c.label), style = MaterialTheme.typography.bodySmall)
                        Text(c.count.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text("Категории", Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(logCountsBy(log) { it.category }.take(10)) { c -> DetailRow(c.label, c.count.toString(), Modifier.padding(horizontal = 16.dp)) }
            }
        } else {
            val entries = log.filter { (severity.isEmpty() || it.severity == severity) && (query.isEmpty() || it.raw.contains(query, ignoreCase = true)) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SearchField(query, { query = it }, "Поиск по тексту")
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.category, color = scheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(e.severity, color = severityColor(e.severity), style = MaterialTheme.typography.labelSmall)
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
private fun SegmentedTwo(a: String, b: String, first: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(first, { onChange(true) }, { Text(a) })
        FilterChip(!first, { onChange(false) }, { Text(b) })
    }
}
