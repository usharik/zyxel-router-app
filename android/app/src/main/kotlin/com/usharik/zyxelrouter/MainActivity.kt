package com.usharik.zyxelrouter

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.material.icons.automirrored.filled.Article
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Цвета состояний зависят от темы: один оттенок на светлом фоне даёт контраст ~3:1, на тёмном — ~5:1,
// а мелкому тексту нужно не меньше 4,5:1.
// Светлые оттенки проверены на самом тёмном фоне, где они встречаются (карточка, диалог): не ниже 4,5:1.
private val Green: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FCF8A) else Color(0xFF186530)
private val Orange: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFF2A65A) else Color(0xFF7F4808)

/** Самый мелкий кегль, до которого ужимаются подписи; дальше подписи вкладок скрываются, остаются значки. */
internal val MIN_LABEL_SIZE = 10.sp

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

const val PRIVACY_POLICY_URL = "https://usharik.github.io/zyxelrouter/privacy-policy.html"

class MainActivity : ComponentActivity() {
    private val viewModel: RouterViewModel by viewModels {
        viewModelFactory { initializer { (application as ZyxelApp).let { RouterViewModel(it, connect = it.connectRouter) } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ads = (application as ZyxelApp).ads
        ads.start(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalUiActivity provides ads.ui) {
                    // Касания главного окна: реклама не появится, если пользователь уже занят следующим делом.
                    Surface(Modifier.fillMaxSize().pointerInput(ads.ui) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                ads.ui.lastTouchAt = SystemClock.elapsedRealtime()
                            }
                        }
                    }) { Root(viewModel, ads) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Покупки с другого устройства, прошедшая отложенная оплата, возврат денег.
        (application as ZyxelApp).purchases.refresh()
    }
}

@Composable
private fun Root(vm: RouterViewModel, ads: AdsController) {
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
        Phase.STARTING -> Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), Alignment.Center) {
            // Автовход с сохранённым паролем: те же шаги и «Отмена», что на экране входа.
            val step = state.loginStep
            if (step != null) Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
                Text(state.routerName.ifEmpty { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                LoginProgress(step, vm::cancelLogin)
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.connecting))
            }
        }
        Phase.LOGIN -> LoginScreen(state, vm)
        Phase.MAIN -> MainScreen(state, vm, ads)
    }
}

/** Через сколько ожидание считается долгим и экран объясняет, что роутер просто медленный. */
private const val SLOW_AFTER_MS = 4_000L

/** true, если ожидание длится дольше [SLOW_AFTER_MS]; отсчёт идёт с появления на экране. */
@Composable
private fun rememberSlow(): Boolean {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(SLOW_AFTER_MS); slow = true }
    return slow
}

@Composable
private fun SlowRouterHint() {
    Text(
        stringResource(R.string.router_slow), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
    )
}

/** Шаги входа: пройденные с галочкой, текущий с индикатором, будущие бледные. Долгое ожидание — с пояснением и «Отменой». */
@Composable
internal fun LoginProgress(step: LoginStep, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val slow = rememberSlow()
    Column(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp), Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            LoginStep.entries.forEach { st ->
                val order = st.ordinal.compareTo(step.ordinal)
                val label = stringResource(
                    when (st) {
                        LoginStep.CONNECTING -> R.string.login_step_connecting
                        LoginStep.SIGNING_IN -> R.string.login_step_signing_in
                        LoginStep.LOADING -> R.string.login_step_loading
                    },
                )
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp), Alignment.Center) {
                        when {
                            order < 0 -> Icon(Icons.Default.CheckCircle, stringResource(R.string.step_done), tint = scheme.primary)
                            order == 0 -> {
                                val current = stringResource(R.string.step_current)
                                CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = current }, strokeWidth = 2.dp)
                            }
                            else -> Icon(Icons.Default.RadioButtonUnchecked, null, tint = scheme.outline)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        if (order == 0) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                        color = if (order > 0) scheme.onSurfaceVariant else scheme.onSurface,
                        fontWeight = if (order == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (slow) SlowRouterHint()
        OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}

/** Небольшой индикатор у переключателя, пока его запись идёт на роутер. */
@Composable
private fun WritingIndicator() {
    val description = stringResource(R.string.writing_to_router)
    CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp).semantics { contentDescription = description }, strokeWidth = 2.dp)
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
    val canSubmit = !s.busy && s.password.isNotEmpty() && s.username.isNotEmpty() && s.address.isNotBlank()
    val context = LocalContext.current
    // Box центрирует форму, а Column прокручивается, когда она не помещается (клавиатура, альбомная ориентация).
    Box(Modifier.fillMaxSize().safeDrawingPadding(), Alignment.Center) { Column(
        Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        Arrangement.spacedBy(12.dp),
        Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Router, null, Modifier.size(64.dp), MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
        Text(s.routerName.ifEmpty { stringResource(R.string.router_not_found) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            // Пока идёт вход, поля не меняются: повторный вход взял бы из них уже не тот пароль, а история — не тот адрес.
            s.address, vm::setAddress, Modifier.fillMaxWidth(), enabled = !s.busy, label = { FieldLabel(stringResource(R.string.field_address)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            s.username, { v -> vm.edit { it.copy(username = v) } }, Modifier.fillMaxWidth(), enabled = !s.busy, label = { FieldLabel(stringResource(R.string.field_user)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            s.password, { v -> vm.edit { it.copy(password = v) } }, Modifier.fillMaxWidth(), enabled = !s.busy, label = { FieldLabel(stringResource(R.string.field_password)) }, singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) vm.submitLogin() }),
            trailingIcon = {
                IconButton({ showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) stringResource(R.string.hide_password) else stringResource(R.string.show_password))
                }
            },
        )
        SwitchRow(stringResource(R.string.remember_password), s.remember) { v -> vm.edit { it.copy(remember = v) } }
        // TalkBack зачитывает ошибку входа сразу, как она появилась.
        s.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        s.certificateFingerprint?.let { fingerprint ->
            // Отпечаток виден до решения: его можно сверить с веб-интерфейсом роутера.
            Text(stringResource(R.string.certificate_fingerprint, fingerprint.chunked(4).joinToString(" ")), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            OutlinedButton(vm::trustNewCertificate, Modifier.fillMaxWidth(), enabled = !s.busy && s.password.isNotEmpty()) { Text(stringResource(R.string.trust_new_certificate)) }
        }
        if (!s.knownRouter && s.certificateFingerprint == null) {
            Text(stringResource(R.string.first_connection_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val step = s.loginStep
        if (s.busy && step != null) LoginProgress(step, vm::cancelLogin)
        else Button(vm::submitLogin, Modifier.fillMaxWidth(), enabled = canSubmit) {
            if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.sign_in))
        }
        // Посмотреть приложение без роутера; так же его проверяют в Google Play.
        TextButton(vm::startDemo, enabled = !s.busy) { Text(stringResource(R.string.demo_try)) }
        TextButton({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri())) } }) {
            Text(stringResource(R.string.privacy_policy), style = MaterialTheme.typography.bodySmall)
        }
    } }
}

/** Вкладка в верхней панели невысокого экрана: значок над подписью, высота не меньше 48 dp. */
@Composable
private fun CompactTab(selected: Boolean, label: String, icon: ImageVector, style: TextStyle, showLabel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Column(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .selectable(selected, role = Role.Tab, onClick = onClick).padding(vertical = 4.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally,
    ) {
        Icon(icon, if (showLabel) null else label, Modifier.size(22.dp), color)
        if (showLabel) Text(label, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** Подпись поля ввода: длинная не должна раздувать поле на две строки. */
@Composable
private fun FieldLabel(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(s: UiState, vm: RouterViewModel, ads: AdsController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var reportedTab by rememberSaveable { mutableStateOf<Int?>(null) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val adFree by ads.purchases.adFree.collectAsStateWithLifecycle()
    val offer by ads.purchases.offer.collectAsStateWithLifecycle()
    val pacing by ads.pacing.state.collectAsStateWithLifecycle()
    val adsActive = ads.active(adFree)
    // Пока оплата ожидается, предлагать купить ещё раз незачем.
    val canOffer = !adFree && !offer.pending
    val currentAdsActive by rememberUpdatedState(adsActive)
    val keyboardVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val internetUp by rememberUpdatedState(s.snapshot?.internetUp == true)
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tabs = listOf<Pair<String, ImageVector>>(
        stringResource(R.string.tab_overview) to Icons.Default.Home, stringResource(R.string.tab_devices) to Icons.Default.Devices, stringResource(R.string.tab_wifi) to Icons.Default.Wifi,
        stringResource(R.string.tab_parental) to Icons.Default.Shield, stringResource(R.string.tab_routes) to Icons.Default.Route, stringResource(R.string.tab_log) to Icons.AutoMirrored.Filled.Article,
    )
    val configuration = LocalConfiguration.current
    // На невысоком экране вкладки перемещаются в верхнюю панель: так видны все шесть, и на содержимое остаётся вся высота.
    val compact = configuration.screenHeightDp < 480
    // В нижней панели между пунктами 8 dp; в верхней часть ширины занимают две кнопки действий по 48 dp.
    val navLabelWidth = if (compact) (configuration.screenWidthDp.dp - 96.dp) / tabs.size - 8.dp
        else (configuration.screenWidthDp.dp - 8.dp * (tabs.size - 1)) / tabs.size
    val navLabelStyle = fittedStyle(tabs.map { it.first }, navLabelWidth, MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp))
    // Мельче 10 sp подпись не прочесть (узкий экран, крупный системный шрифт): тогда только значки, а подпись — для TalkBack.
    val showNavLabels = navLabelStyle.fontSize >= MIN_LABEL_SIZE
    LaunchedEffect(Unit) { if (!s.demo) ads.pacing.sessionStarted() }
    LaunchedEffect(adsActive) { if (adsActive) ads.preload() }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, activity, lifecycleOwner) {
        // Только пока экран на виду: повод, пришедший в фоне, пропадает, а не ждёт возвращения пользователя.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.adBreaks.collect { event ->
                // Окно, после которого пришла пауза (редактор профиля), закрывается в следующих кадрах: ждём его, но недолго.
                withTimeoutOrNull(1_000) { snapshotFlow { ads.ui.overlays }.first { it == 0 } }
                if (activity != null) ads.onBreak(activity, event, currentAdsActive, keyboardVisible)
            }
        }
    }
    LaunchedEffect(Unit) {
        // Просьба оценить — в спокойный момент: роутер на связи и интернет работает, окна закрыты.
        delay(8_000)
        if (internetUp && !s.demo && activity != null) ads.maybeAskReview(activity)
    }
    val playUnavailable = stringResource(R.string.play_unavailable)
    val diagnosticsCopied = stringResource(R.string.diagnostics_copied)
    val buyRemoveAds: (String) -> Unit = { source ->
        if (activity == null || !ads.buyRemoveAds(activity, source)) scope.launch { snackbar.showSnackbar(playUnavailable) }
    }
    val section = TabSections[tab]
    LaunchedEffect(tab) {
        // После поворота экрана тот же экран не считается новым просмотром.
        if (reportedTab != tab) Telemetry.screen(TabScreens[tab])
        reportedTab = tab
        when (section) {
            Section.PARENTAL, Section.ROUTING -> vm.load(section)
            Section.LOG -> if (s.log == null) vm.load(section)
            null -> Unit
        }
    }
    val actions: @Composable RowScope.() -> Unit = {
        IconButton({ vm.refreshNow(section) }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
            DropdownMenu(menu, { menu = false }) {
                TrackOverlay()
                if (!s.demo) DropdownMenuItem({ Text(stringResource(R.string.menu_copy_diagnostics)) }, {
                    clipboard.setText(AnnotatedString(s.snapshot?.schema.orEmpty()))
                    menu = false
                    // С Android 13 система сама показывает уведомление о копировании.
                    if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.showSnackbar(diagnosticsCopied) }
                })
                DropdownMenuItem({ Text(stringResource(R.string.menu_reboot)) }, { confirm = true; menu = false }, enabled = !s.rebooting)
                if (canOffer) DropdownMenuItem({ Text(offer.formattedPrice?.let { stringResource(R.string.menu_remove_ads_price, it) } ?: stringResource(R.string.menu_remove_ads)) }, { menu = false; buyRemoveAds("menu") })
                if (ads.consent.privacyOptionsRequired) DropdownMenuItem({ Text(stringResource(R.string.menu_privacy_options)) }, { menu = false; activity?.let(ads::showPrivacyOptions) })
                DropdownMenuItem({ Text(stringResource(R.string.menu_about)) }, { menu = false; about = true })
                DropdownMenuItem({ Text(stringResource(if (s.demo) R.string.demo_exit else R.string.menu_logout)) }, { menu = false; vm.logout() })
            }
        }
    }

    Scaffold(
        // Вырез экрана в альбомной ориентации: содержимое не должно уходить под него.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        topBar = {
            if (compact) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(
                        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp),
                        Arrangement.spacedBy(4.dp),
                        Alignment.CenterVertically,
                    ) {
                        tabs.forEachIndexed { index, (label, icon) ->
                            CompactTab(tab == index, label, icon, navLabelStyle, showNavLabels, Modifier.weight(1f)) { tab = index }
                        }
                        actions()
                    }
                }
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.demo) Text(stringResource(R.string.demo_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    actions = actions,
                )
            }
        },
        bottomBar = {
            // На невысоком экране (альбомная ориентация) баннера нет: там каждая строка содержимого на счету.
            if (!compact) Column {
                if (!adFree && ads.consent.reservesAdSpace) {
                    // Заметный промежуток до вкладок, чтобы по рекламе не попадали, переключая их.
                    HorizontalDivider()
                    AdBanner(adsActive, Modifier.padding(top = 4.dp, bottom = 16.dp))
                }
                NavigationBar {
                    tabs.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            tab == index, { tab = index },
                            { Icon(icon, if (showNavLabels) null else label) },
                            label = if (showNavLabels) ({ Text(label, style = navLabelStyle, maxLines = 1, softWrap = false) }) else null,
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Scaffold не учитывает клавиатуру: без imePadding она закрывает низ списков при поиске.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(), Alignment.TopCenter) {
            // На планшете строки не растягиваются на всю ширину.
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                // Полоска занимает место всегда, чтобы содержимое не прыгало при появлении индикатора.
                // Первую загрузку раздела показывает сам раздел; здесь — только обновление того, что уже на экране.
                val reloading = s.sectionsBusy.any {
                    when (it) {
                        Section.PARENTAL -> s.parental != null
                        Section.ROUTING -> s.routing != null
                        Section.LOG -> s.log != null
                    }
                }
                if (s.refreshing || reloading) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp)) else Spacer(Modifier.height(4.dp))
                s.banner?.let { Banner(it) { vm.edit { st -> st.copy(banner = null) } } }
                when (tab) {
                    0 -> s.snapshot?.let {
                        val showOffer = adsActive && canOffer && offer.formattedPrice != null && AdPacing.offerDue(pacing, AdPacingStore.now())
                        Overview(it, s.updatedAt, if (showOffer) ({ RemoveAdsCard(offer.formattedPrice, { buyRemoveAds("card") }, ads::dismissOffer) }) else null)
                    }
                    1 -> s.snapshot?.let { Devices(s, vm) }
                    2 -> s.snapshot?.let { Wifi(it, vm) }
                    3 -> ParentalScreen(s, vm)
                    4 -> RoutingScreen(s, vm)
                    else -> LogScreen(s, vm)
                }
            }
        }
    }
    if (confirm) {
        TrackOverlay()
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.reboot_title)) },
            text = { Text(stringResource(R.string.reboot_text)) },
            confirmButton = { TextButton({ confirm = false; vm.reboot() }, enabled = !s.rebooting) { Text(stringResource(R.string.reboot_confirm)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (ads.afterAdOffer && canOffer) AfterAdOfferDialog(offer.formattedPrice, { buyRemoveAds("after_ad") }, ads::dismissOffer)
    // Покупка из «О приложении» сначала закрывает окно, чтобы сообщение о недоступном Play не оказалось под затемнением.
    val restore by ads.purchases.restore.collectAsStateWithLifecycle()
    if (about) AboutDialog(adFree, offer, restore, { about = false; buyRemoveAds("about") }, ads.purchases::restore) { about = false; ads.purchases.clearRestore() }
}

private val TabScreens = listOf("overview", "devices", "wifi", "parental", "routing", "log")
private val TabSections = listOf(null, null, null, Section.PARENTAL, Section.ROUTING, Section.LOG)

/** Уведомление под панелью: ошибка опроса или сообщение о перезагрузке. Закрывается; при повторной ошибке появится снова. */
@Composable
private fun Banner(text: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.medium, color = scheme.tertiaryContainer) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = scheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = scheme.onTertiaryContainer, style = MaterialTheme.typography.bodyMedium)
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.dismiss_notice), tint = scheme.onTertiaryContainer) }
        }
    }
}


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
private fun Overview(s: RouterSnapshot, updatedAt: Long, offerCard: (@Composable () -> Unit)?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionCard(stringResource(R.string.section_internet)) {
            InfoRow(stringResource(R.string.status), if (s.internetUp) stringResource(R.string.connected) else stringResource(R.string.not_connected), if (s.internetUp) Green else MaterialTheme.colorScheme.error)
            InfoRow(stringResource(R.string.external_ip), s.wanIP.ifEmpty { "—" })
            InfoRow(stringResource(R.string.downstream), speedText(s.downKbps))
            InfoRow(stringResource(R.string.upstream), speedText(s.upKbps))
            InfoRow(stringResource(R.string.link_type), listOf(s.linkType, s.encapsulation).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "—" })
            InfoRow(stringResource(R.string.dns), s.dns.ifEmpty { "—" })
            InfoRow(stringResource(R.string.connected_for), durationText(s.internetUptime))
        }
        SectionCard(stringResource(R.string.section_network)) {
            InfoRow(stringResource(R.string.devices_online), s.devices.count { it.online }.toString())
            InfoRow(stringResource(R.string.wifi_enabled), stringResource(R.string.count_of, s.wifi.count { it.enabled }, s.wifi.size))
        }
        SectionCard(stringResource(R.string.section_router)) {
            InfoRow(stringResource(R.string.model), s.model)
            InfoRow(stringResource(R.string.firmware), s.firmware)
            InfoRow(stringResource(R.string.router_uptime), durationText(s.uptime))
            // Формат времени по системной настройке 12/24 часа.
            InfoRow(stringResource(R.string.updated), android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(Date(updatedAt)))
        }
        offerCard?.invoke()
    }
}

/** Предложение отключить рекламу внизу «Обзора» постоянному пользователю; любая кнопка убирает его на месяц. */
@Composable
private fun RemoveAdsCard(price: String?, onBuy: () -> Unit, onDismiss: () -> Unit) {
    // Повороты экрана и возвраты на вкладку не считаются новыми показами.
    var reported by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!reported) Telemetry.event("remove_ads_offer_shown", mapOf("placement" to "card")); reported = true }
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = scheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.offer_title), style = MaterialTheme.typography.titleMedium, color = scheme.onSecondaryContainer)
            Text(stringResource(R.string.offer_text), style = MaterialTheme.typography.bodyMedium, color = scheme.onSecondaryContainer)
            Button(onBuy, Modifier.fillMaxWidth()) { Text(price?.let { stringResource(R.string.remove_ads_for, it) } ?: stringResource(R.string.menu_remove_ads)) }
            TextButton(onDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.not_now)) }
        }
    }
}

/** Сразу после закрытой рекламы повод заплатить очевиден; не чаще раза в неделю (см. [AdPacing]). */
@Composable
private fun AfterAdOfferDialog(price: String?, onBuy: () -> Unit, onDismiss: () -> Unit) {
    var reported by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!reported) Telemetry.event("remove_ads_offer_shown", mapOf("placement" to "after_ad")); reported = true }
    TrackOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.after_ad_title)) },
        text = { Text(stringResource(R.string.after_ad_text)) },
        confirmButton = { Button(onBuy) { Text(price?.let { stringResource(R.string.remove_ads_for, it) } ?: stringResource(R.string.menu_remove_ads)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.not_now)) } },
    )
}

@Composable
private fun AboutDialog(adFree: Boolean, offer: RemoveAdsOffer, restore: RestoreResult?, onBuy: () -> Unit, onRestore: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    var reported by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!reported) Telemetry.screen("about"); reported = true }
    TrackOverlay()
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(Icons.Default.Router, null) },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    adFree -> Text(stringResource(R.string.ads_removed_thanks), color = Green)
                    offer.pending -> Text(stringResource(R.string.purchase_pending), color = Orange)
                    else -> Button(onBuy, Modifier.fillMaxWidth()) { Text(offer.formattedPrice?.let { stringResource(R.string.remove_ads_for, it) } ?: stringResource(R.string.menu_remove_ads)) }
                }
                if (!adFree) TextButton(onRestore, enabled = restore != RestoreResult.CHECKING) { Text(stringResource(R.string.restore_purchase)) }
                // Ответ на «Восстановить»: без него нажатие выглядело бы так, будто ничего не произошло.
                restore?.let { r ->
                    Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                        if (r == RestoreResult.CHECKING) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                when (r) {
                                    RestoreResult.CHECKING -> R.string.restore_checking
                                    RestoreResult.RESTORED -> R.string.restore_done
                                    RestoreResult.PENDING -> R.string.purchase_pending
                                    RestoreResult.NOT_FOUND -> R.string.restore_not_found
                                    RestoreResult.FAILED -> R.string.restore_failed
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = when (r) {
                                RestoreResult.RESTORED -> Green
                                RestoreResult.NOT_FOUND, RestoreResult.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                TextButton({ open("https://play.google.com/store/apps/details?id=${context.packageName}") }) { Text(stringResource(R.string.rate_app)) }
                TextButton({ open(PRIVACY_POLICY_URL) }) { Text(stringResource(R.string.privacy_policy)) }
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun ListRow(
    icon: ImageVector, active: Boolean, title: String, guest: Boolean, subtitle: String, status: String, detail: String, blocked: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            // FlowRow: метки уходят на следующую строку, а не отнимают ширину у длинного названия.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (guest) Text(stringResource(R.string.label_guest), color = Orange, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                if (blocked) Text(stringResource(R.string.label_blocked), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        // Ограничиваем ширину: длинное имя сети иначе вытеснит название устройства.
        Column(Modifier.widthIn(max = 128.dp), horizontalAlignment = Alignment.End) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (active) Green else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

private fun deviceIcon(d: RouterDevice): ImageVector = when (d.type) {
    DeviceType.PHONE -> Icons.Default.Smartphone
    DeviceType.COMPUTER -> Icons.Default.Computer
    DeviceType.PRINTER -> Icons.Default.Print
    DeviceType.TV -> Icons.Default.Tv
    DeviceType.IOT -> Icons.Default.Sensors
    else -> if (d.connection.contains("wi-fi", ignoreCase = true)) Icons.Default.Wifi else Icons.Default.SettingsEthernet
}

private fun deviceKey(d: RouterDevice) = d.mac.ifEmpty { d.name + d.ip }

@Composable
private fun Devices(state: UiState, vm: RouterViewModel) {
    val s = state.snapshot ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var onlineOnly by rememberSaveable { mutableStateOf(false) }   // по умолчанию показываем и офлайн
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Первое открытие списка за вход — спрашиваем устройства о них самих; повтор — по кнопке.
    LaunchedEffect(Unit) { vm.discoverDevices(auto = true) }
    // Имя и тип дополняем тем, что устройство сообщило о себе и что известно о производителе по MAC.
    val all = state.devices.distinctBy(::deviceKey).map { d ->
        val id = state.identities[d.mac]
        d.copy(name = displayName(d, id), type = refinedType(d, id, state.vendors[d.mac]))
    }   // повторяющийся ключ уронил бы LazyColumn
    val devices = all.filter { d ->
        val id = state.identities[d.mac]
        val text = listOfNotNull(d.name, d.ip, d.mac, state.vendors[d.mac], id?.hostName, id?.model, id?.manufacturer)
        (!onlineOnly || d.online) && (query.isEmpty() || text.any { it.contains(query, ignoreCase = true) })
    }
    val (online, offline) = devices.partition { it.online }
    Column(Modifier.fillMaxSize()) {
        SearchField(query, { query = it }, stringResource(R.string.search_devices), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
        SwitchRow(stringResource(R.string.online_only), onlineOnly, Modifier.padding(horizontal = 16.dp)) { onlineOnly = it }
        DiscoveryRow(state.discovery, vm::discoverDevices)
        if (devices.isEmpty()) {
            Text(
                if (all.isEmpty()) stringResource(R.string.no_devices) else stringResource(R.string.nothing_found),
                Modifier.fillMaxWidth().padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        val onlineTitle = stringResource(R.string.online)
        val offlineTitle = stringResource(R.string.offline)
        LazyColumn(Modifier.fillMaxSize()) {
            // Живые устройства и запомненные офлайн-устройства разделяем заголовками, когда есть и те и другие.
            fun group(title: String, list: List<RouterDevice>) {
                if (list.isEmpty()) return
                if (online.isNotEmpty() && offline.isNotEmpty()) item(key = "header:${list === online}") {
                    Text(stringResource(R.string.group_header, title, list.size), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                items(list, key = ::deviceKey) { d ->
                    Box(Modifier.clickable { selectedKey = deviceKey(d) }) {
                        ListRow(
                            deviceIcon(d), d.online, d.name, d.guest,
                            listOfNotNull(
                                d.type.takeIf { it != DeviceType.UNKNOWN }?.let { deviceTypeLabel(it) },
                                // Производитель или пометка о частном адресе — главное, что отличает «неизвестное» устройство.
                                state.vendors[d.mac] ?: if (isPrivateMac(d.mac)) stringResource(R.string.private_mac) else null,
                                d.ip.ifEmpty { d.mac }.takeIf { it.isNotEmpty() },
                            ).joinToString(" · "),
                            if (d.online) onlineTitle else offlineTitle,
                            if (d.online) networkLabel(d).ifEmpty { d.connection } else stringResource(R.string.last_seen_short, agoText(lastSeenAgo(d.lastSeen))), blocked = d.blocked,
                        )
                    }
                }
            }
            group(onlineTitle, online)
            group(offlineTitle, offline)
        }
    }
    val liveMacs = s.devices.map { it.mac }.toSet()
    all.firstOrNull { deviceKey(it) == selectedKey }?.let { d ->
        // Пока устройство есть в ответе роутера, «забыть» бессмысленно: следующий опрос вернёт его в историю.
        DeviceDetailsDialog(d, state.vendors[d.mac], state.identities[d.mac], onForget = if (!d.online && d.mac !in liveMacs && !state.demo) ({ vm.forgetDevice(d.mac); selectedKey = null }) else null) { selectedKey = null }
    }
}

/** Строка опроса сети над списком: что происходит и кнопка повтора. */
@Composable
private fun DiscoveryRow(status: DiscoveryStatus, onDiscover: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val running = status == DiscoveryStatus.Running
        if (running) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        }
        val text = when (status) {
            DiscoveryStatus.Running -> stringResource(R.string.discovering)
            DiscoveryStatus.NotOnLan -> stringResource(R.string.discovery_not_on_lan)
            is DiscoveryStatus.Done -> if (status.answered == 0) stringResource(R.string.discovery_none) else pluralStringResource(R.plurals.discovery_answered, status.answered, status.answered)
            DiscoveryStatus.Idle -> ""
        }
        Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onDiscover, enabled = !running) { Text(stringResource(R.string.discover_devices)) }
    }
}

/** Что устройство рассказало о себе в сети. */
@Composable
private fun IdentityRows(id: NetworkIdentity) {
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.identity_title), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    if (id.name.isNotEmpty()) DetailRow(stringResource(R.string.network_name), id.name)
    if (id.hostName.isNotEmpty() && id.hostName != id.name) DetailRow(stringResource(R.string.host_name), id.hostName)
    val model = listOf(id.manufacturer, id.model).filter { it.isNotEmpty() }.distinct().joinToString(" ")
    if (model.isNotEmpty()) DetailRow(stringResource(R.string.device_model), model)
    if (id.workgroup.isNotEmpty()) DetailRow(stringResource(R.string.workgroup), id.workgroup)
    if (id.services.isNotEmpty()) DetailRow(stringResource(R.string.services_label), id.services.sortedBy { it.ordinal }.map { serviceLabel(it) }.joinToString(", "))
}

@Composable
private fun serviceLabel(s: NetService): String = when (s) {
    // Названия технологий не переводятся.
    NetService.AIRPLAY -> "AirPlay"
    NetService.CAST -> "Chromecast"
    NetService.HOMEKIT -> "HomeKit"
    NetService.SPOTIFY -> "Spotify Connect"
    NetService.SSH -> "SSH"
    NetService.ANDROID_TV -> "Android TV"
    NetService.FIRE_TV -> "Fire TV"
    NetService.PRINTER -> stringResource(R.string.service_printer)
    NetService.SCANNER -> stringResource(R.string.service_scanner)
    NetService.FILES -> stringResource(R.string.service_files)
    NetService.WEB -> stringResource(R.string.service_web)
    NetService.MEDIA_SERVER -> stringResource(R.string.service_media_server)
    NetService.MEDIA_PLAYER -> stringResource(R.string.service_media_player)
    NetService.APPLE -> stringResource(R.string.service_apple)
    NetService.ROUTER -> stringResource(R.string.service_router)
}

/** Поле поиска с иконкой и кнопкой очистки. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value, onChange, modifier.fillMaxWidth(), label = { FieldLabel(label) }, singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Default.Clear, stringResource(R.string.clear)) } },
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
internal fun DeviceDetailsDialog(d: RouterDevice, vendor: String?, identity: NetworkIdentity?, onForget: (() -> Unit)? = null, onClose: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    TrackOverlay()
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.close)) } },
        dismissButton = { if (onForget != null) TextButton(onForget) { Text(stringResource(R.string.forget), color = MaterialTheme.colorScheme.error) } },
        icon = { Icon(deviceIcon(d), null) },
        title = { Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(deviceTypeLabel(d.type), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                DetailRow(stringResource(R.string.status), if (d.online) stringResource(R.string.online) else stringResource(R.string.offline))
                if (!d.online) DetailRow(stringResource(R.string.last_online), agoText(lastSeenAgo(d.lastSeen)))
                DetailRow(stringResource(R.string.connected_via), networkLabel(d).let { n -> if (n.isEmpty() || d.connection.isEmpty()) n.ifEmpty { d.connection } else "$n (${d.connection})" })
                if (d.ip.isNotEmpty()) DetailRow(stringResource(R.string.ip_address), d.ip)
                DetailRow(stringResource(R.string.mac), d.mac)
                if (vendor != null) DetailRow(stringResource(R.string.manufacturer_by_mac), vendor)
                if (isPrivateMac(d.mac)) {
                    DetailRow(stringResource(R.string.manufacturer_by_mac), stringResource(R.string.private_mac))
                    Text(stringResource(R.string.private_mac_hint), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (d.ipv6) DetailRow(stringResource(R.string.ipv6), stringResource(R.string.yes))
                DetailRow(stringResource(R.string.address_kind), if (d.staticIP) stringResource(R.string.address_static) else stringResource(R.string.address_dhcp))
                if (d.blocked) DetailRow(stringResource(R.string.parental_control), stringResource(R.string.blocked_full))
                if (d.connection.contains("wi-fi", ignoreCase = true)) {
                    if (d.signal > 0) DetailRow(stringResource(R.string.signal), "▮".repeat(d.signal) + "▯".repeat((5 - d.signal).coerceAtLeast(0)))
                    if (d.snr > 0) DetailRow(stringResource(R.string.snr), stringResource(R.string.snr_value, d.snr))
                    if (d.phyRate > 0) DetailRow(stringResource(R.string.phy_rate), stringResource(R.string.speed_mbps, d.phyRate.toString()))
                    if (d.wifiStandard.isNotEmpty()) DetailRow(stringResource(R.string.standard), "802.11${d.wifiStandard}")
                }
                DetailRow(stringResource(R.string.received), bytesText(d.bytesDown))
                DetailRow(stringResource(R.string.sent), bytesText(d.bytesUp))
                if (d.vendorClass.isNotEmpty()) DetailRow(stringResource(R.string.vendor), d.vendorClass)
                // Отдельным блоком в конце: всё выше — сведения роутера, ниже — что сказало само устройство.
                if (identity != null && !identity.isEmpty) IdentityRows(identity)
                Spacer(Modifier.height(4.dp))
                TextButton({ showAll = !showAll }) { Text(if (showAll) stringResource(R.string.hide_all_fields) else stringResource(R.string.all_fields, d.details.size)) }
                if (showAll) d.details.toSortedMap().forEach { (k, v) -> DetailRow(k, v) }
            }
        },
    )
}

/** Ключ сети для окна «Поделиться»: место в списке роутера и имя — по ним же сверяется пароль. */
private fun shareKey(w: WifiNetwork) = "${w.index}:${w.ssid}"

@Composable
private fun Wifi(s: RouterSnapshot, vm: RouterViewModel) {
    if (s.wifi.isEmpty()) {
        SectionHint(stringResource(R.string.no_wifi))
        return
    }
    // Сохраняется только ключ сети; пароль запрашивается заново при каждом открытии окна.
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(s.wifi, key = ::shareKey) { w ->
            val shareable = WifiSecurity.parse(w.security) != WifiSecurity.ENTERPRISE
            Box(if (shareable) Modifier.clickable { sharing = shareKey(w) } else Modifier) {
                ListRow(
                    if (w.enabled) Icons.Default.Wifi else Icons.Default.WifiOff, w.enabled, w.ssid, w.guest,
                    listOfNotNull(bandLabel(w.band), w.channel.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.channel, it) }, w.security.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    if (w.enabled) stringResource(R.string.wifi_on) else stringResource(R.string.wifi_off), "",
                    trailing = if (shareable) ({
                        IconButton({ sharing = shareKey(w) }) { Icon(Icons.Default.QrCode2, stringResource(R.string.share_wifi, w.ssid)) }
                    }) else null,
                )
            }
        }
    }
    sharing?.let { key ->
        // Сеть ищется по ключу при каждом опросе: смена канала окно не перезагружает, а пропавшая сеть — честно сообщается.
        val network = s.wifi.firstOrNull { shareKey(it) == key }
        if (network != null) WifiShareDialog(network, vm) { sharing = null } else WifiGoneDialog { sharing = null }
    }
}

/** Раздел ещё не прочитан: индикатор, а если чтение не удалось — ошибка и «Повторить» (иначе экран крутился бы вечно). */
@Composable
private fun SectionPlaceholder(error: String?, loading: String, progress: Float? = null, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        if (error == null) Column(Modifier.widthIn(max = 360.dp), Arrangement.spacedBy(12.dp), Alignment.CenterHorizontally) {
            // Что именно грузится и, где запросов несколько, сколько уже прочитано.
            if (progress != null) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth()) else CircularProgressIndicator()
            Text(loading, textAlign = TextAlign.Center)
            if (rememberSlow()) SlowRouterHint()
        }
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            OutlinedButton(onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

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
    if (pc == null) return SectionPlaceholder(s.sectionErrors[Section.PARENTAL], stringResource(R.string.loading_parental)) { vm.load(Section.PARENTAL) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.toggleable(pc.enabled, role = Role.Switch, onValueChange = { vm.setParentalEnabled(it) }).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.parental_control), fontWeight = FontWeight.SemiBold)
                        Text(if (pc.enabled) stringResource(R.string.parental_on) else stringResource(R.string.parental_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (PARENTAL_ALL in s.writingParental) WritingIndicator()
                    Spacer(Modifier.width(8.dp))
                    Switch(pc.enabled, null)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pc.maxProfiles > 0) stringResource(R.string.profiles_count, pc.profiles.size, pc.maxProfiles) else stringResource(R.string.profiles),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
                TextButton({ creating = true }, enabled = canAddProfile(pc)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.add)) }
            }
        }
        if (pc.profiles.isEmpty()) item { SectionHint(stringResource(R.string.no_profiles)) }
        if (!canAddProfile(pc)) item { SectionHint(stringResource(R.string.profile_limit)) }
        // Ключ с позицией: прошивка без Index (у всех 0) или с повтором не уронит список.
        itemsIndexed(pc.profiles, key = { i, p -> "$i:${p.index}" }) { _, p ->
            val title = p.name.ifEmpty { stringResource(R.string.profile_n, p.index) }
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val switchLabel = stringResource(R.string.profile_switch, title)
                        val busy = p.index in s.deletingProfiles
                        if (busy || p.index in s.writingParental) WritingIndicator()
                        Switch(p.enabled, { vm.setProfileEnabled(p.index, it) }, enabled = !busy, modifier = Modifier.padding(start = 8.dp, end = 12.dp).semantics { contentDescription = switchLabel })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${pluralStringResource(R.plurals.devices_count, p.macs.size, p.macs.size)} · ${daysText(p.days, currentLocale())} · ${p.times.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.blocksSites || p.blocksServices) {
                                Text(
                                    stringResource(R.string.restricts, listOfNotNull(if (p.blocksSites) stringResource(R.string.sites) else null, if (p.blocksServices) stringResource(R.string.services) else null).joinToString(", ")),
                                    style = MaterialTheme.typography.bodySmall, color = Orange,
                                )
                            }
                        }
                        val busy = p.index in s.deletingProfiles
                        IconButton({ editorIndex = p.index }, enabled = !busy) { Icon(Icons.Default.Edit, stringResource(R.string.edit_named, title)) }
                        IconButton({ deletingIndex = p.index }, enabled = !busy) { Icon(Icons.Default.Delete, stringResource(R.string.delete_named, title), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    val editor = pc.profiles.firstOrNull { it.index == editorIndex }
    if (creating || editor != null) {
        ProfileEditorDialog(editor, s.devices.filter { it.mac.isNotEmpty() }, s.saveState, vm) { creating = false; editorIndex = null }
    }
    pc.profiles.firstOrNull { it.index == deletingIndex }?.let { p ->
        TrackOverlay()
        AlertDialog(
            onDismissRequest = { deletingIndex = null },
            title = { Text(stringResource(R.string.delete_profile_title)) },
            text = { Text(stringResource(R.string.delete_profile_text, p.name.ifEmpty { stringResource(R.string.profile_n, p.index) })) },
            confirmButton = { TextButton({ vm.deleteProfile(p.index); deletingIndex = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ deletingIndex = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Семь равных ячеек на всю ширину: FilterChip с минимальной шириной в диалог на узких экранах не помещаются. */
@Composable
internal fun DaySelector(selected: List<String>, onToggle: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 2.dp   // на 320 dp каждый dp идёт на ширину ячейки: она должна оставаться удобной для нажатия
        val locale = currentLocale()
        val labels = DayCodes.map { it to dayLabel(it, locale) }
        val cell = (maxWidth - gap * (labels.size - 1)) / labels.size
        val style = fittedStyle(labels.map { it.second }, cell - 4.dp, MaterialTheme.typography.labelLarge)
        val shape = RoundedCornerShape(8.dp)
        val scheme = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap)) {
            labels.forEach { (code, label) ->
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
        val labels = listOf(stringResource(R.string.time_from, start) to onStart, stringResource(R.string.time_to, end) to onEnd)
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
internal fun ProfileEditorDialog(existing: ParentalProfile?, devices: List<RouterDevice>, saveState: SaveState, vm: RouterViewModel, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    val initialMacs = existing?.macs?.map { it.replace("-", ":") } ?: emptyList()
    // listSaver — чтобы выбор устройств и дней переживал поворот экрана.
    val macs = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { initialMacs.toMutableStateList() }
    val days = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) { (existing?.days ?: emptyList()).toMutableStateList() }
    val originalTimes = existing?.times ?: emptyList()
    // Новый профиль — на весь день; конец 24:00 показывается как «до 24:00», в выборе времени это 00:00.
    val range = (originalTimes.firstOrNull() ?: "00:00-24:00").split("-")
    var start by rememberSaveable { mutableStateOf(normalizeTime(range.getOrElse(0) { "00:00" }) ?: "00:00") }
    var end by rememberSaveable { mutableStateOf(normalizeTime(range.getOrElse(1) { "24:00" }) ?: "24:00") }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    // Ошибка проверки формы — кодом (текст на языке пользователя строится при показе), ошибка роутера — готовым текстом.
    var formError by rememberSaveable { mutableStateOf<ProfileError?>(null) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    var manualMac by rememberSaveable { mutableStateOf("") }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }   // "start" или "end", пока открыт выбор времени
    val saving = saveState == SaveState.InFlight
    TrackOverlay()
    // Результат прошлого сохранения (закрытое во время записи окно) к новому окну не относится.
    DisposableEffect(Unit) {
        vm.resetSaveState()
        onDispose { vm.resetSaveState() }
    }
    LaunchedEffect(saveState) {
        when (saveState) {
            SaveState.Done -> { vm.resetSaveState(); onClose() }
            is SaveState.Failed -> { saveError = saveState.message; formError = null; vm.resetSaveState() }
            else -> Unit
        }
    }
    picking?.let { which ->
        val isStart = which == "start"
        TimeDialog(if (isStart) stringResource(R.string.time_start) else stringResource(R.string.time_end), if (isStart) start else end, { if (isStart) start = it else end = endOfDay(it); picking = null }) { picking = null }
    }

    // Пока идёт запись, окно не закрывается: иначе результат придёт без адресата.
    AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text(if (existing == null) stringResource(R.string.new_profile) else stringResource(R.string.edit_profile)) },
        text = {
            Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { FieldLabel(stringResource(R.string.field_name)) }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.devices), style = MaterialTheme.typography.labelLarge)
                    devices.forEach { d ->
                        DeviceCheckRow(d.name.ifEmpty { d.mac }.let { n -> if (d.online) n else stringResource(R.string.offline_suffix, n) }, macs.contains(d.mac), d.online) {
                            if (it) macs.add(d.mac) else macs.remove(d.mac)
                        }
                    }
                    // Устройства не в сети (из профиля или добавленные вручную).
                    val knownMacs = devices.map { it.mac }.toSet()
                    macs.filter { it !in knownMacs }.forEach { mac ->
                        DeviceCheckRow(stringResource(R.string.offline_suffix, mac), true, false) { if (!it) macs.remove(mac) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val addManual = {
                            when (val m = normalizeMAC(manualMac)) {
                                null -> formError = ProfileError.BAD_MAC
                                else -> { if (m !in macs) macs.add(m); manualMac = ""; formError = null }
                            }
                        }
                        OutlinedTextField(
                            manualMac, { manualMac = it }, Modifier.weight(1f), label = { FieldLabel(stringResource(R.string.field_manual_mac)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { if (manualMac.isNotBlank()) addManual() }),
                        )
                        IconButton(enabled = manualMac.isNotBlank(), onClick = addManual) { Icon(Icons.Default.Add, stringResource(R.string.add_mac)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.days), style = MaterialTheme.typography.labelLarge)
                    DaySelector(days) { code -> if (code in days) days.remove(code) else days.add(code) }
                    Spacer(Modifier.height(8.dp))
                    TimeRangeButtons(start, if (end == "00:00") "24:00" else end, { picking = "start" }, { picking = "end" })
                    SwitchRow(stringResource(R.string.profile_enabled), enabled, Modifier.padding(top = 4.dp)) { enabled = it }
                    if (originalTimes.size > 1) {
                        Text(stringResource(R.string.multi_interval_warning), color = Orange, style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Ошибка вне прокрутки: иначе после «Сохранить» она оказывается ниже видимой области.
                (formError?.let { profileErrorText(it) } ?: saveError)?.let { Text(it, Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saveError = null
                // MAC, введённый без «+», не теряется: добавляется при сохранении или останавливает его с ошибкой.
                if (manualMac.isNotBlank()) {
                    val m = normalizeMAC(manualMac) ?: run { formError = ProfileError.BAD_MAC; return@TextButton }
                    if (m !in macs) macs.add(m)
                    manualMac = ""
                }
                val orderedDays = DayCodes.filter { days.contains(it) }
                formError = validateProfile(name, macs.size, orderedDays.size, start, end)
                val single = profileTimeRange(start, end)
                if (formError == null && single != null) {
                    vm.saveProfile(existing?.index, name.trim(), macs.toList(), orderedDays, timeRangesToSave(originalTimes, single), enabled)
                }
            }) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.saving))
                } else Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClose, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
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
    // Циферблат не умеет 24:00 — это полночь, 00:00; конец интервала 00:00 потом снова становится 24:00.
    val minutes = (minutesOfTime(value) ?: 0) % (24 * 60)
    val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour = true)
    val height = LocalConfiguration.current.screenHeightDp
    val dialFits = height >= 560
    // В альбомной ориентации телефона клавиатура ввода цифрами закрывает всё окно.
    val useLists = height < 480
    var dial by rememberSaveable { mutableStateOf(dialFits) }
    TrackOverlay()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dialFits) IconButton({ dial = !dial }) {
                    Icon(if (dial) Icons.Default.Keyboard else Icons.Default.Schedule, if (dial) stringResource(R.string.enter_digits) else stringResource(R.string.show_dial))
                }
            }
        },
        text = {
            Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), Alignment.Center) {
                when {
                    dial && dialFits -> TimePicker(state, layoutType = TimePickerLayoutType.Vertical)
                    useLists -> Row(verticalAlignment = Alignment.CenterVertically) {
                        TimePart(state.hour, 0..23, stringResource(R.string.hours), Modifier.width(96.dp)) { state.hour = it }
                        Text(":", Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.headlineSmall)
                        TimePart(state.minute, 0..59, stringResource(R.string.minutes), Modifier.width(96.dp)) { state.minute = it }
                    }
                    else -> TimeInput(state)
                }
            }
        },
        confirmButton = { TextButton({ onPick(formatTime(state.hour, state.minute)) }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RouteGroup(title: String, items: List<RouteItem>) {
    if (items.isEmpty()) return
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    items.forEach { r ->
        ListRow(
            if (r.enabled) Icons.Default.Route else Icons.Default.Block, r.enabled, routeTitle(r), false, routeSubtitle(r),
            if (r.enabled) stringResource(R.string.route_active) else stringResource(R.string.route_off), "",
        )
    }
}

@Composable
private fun RoutingScreen(s: UiState, vm: RouterViewModel) {
    val r = s.routing ?: return SectionPlaceholder(
        s.sectionErrors[Section.ROUTING], stringResource(R.string.loading_routing, s.routingProgress, ROUTING_PARTS),
        s.routingProgress.toFloat() / ROUTING_PARTS,
    ) { vm.load(Section.ROUTING) }
    val empty = r.staticRoutes.isEmpty() && r.dnsRoutes.isEmpty() && r.policyRoutes.isEmpty() && r.rip.isEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (empty) SectionHint(stringResource(R.string.no_routes))
        RouteGroup(stringResource(R.string.static_routes), r.staticRoutes)
        RouteGroup(stringResource(R.string.dns_routes), r.dnsRoutes)
        RouteGroup(stringResource(R.string.policy_routes), r.policyRoutes)
        RouteGroup(stringResource(R.string.rip), r.rip)
        SectionHint(stringResource(R.string.routes_readonly))
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
    if (log == null) return SectionPlaceholder(s.sectionErrors[Section.LOG], stringResource(R.string.loading_log)) { vm.load(Section.LOG) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedTwo(stringResource(R.string.log_stats), stringResource(R.string.log_entries), showStats, Modifier.weight(1f)) { showStats = it }
            IconButton({ vm.loadLog() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        }
        if (showStats) {
            // Сводки считаются один раз на журнал, а не при каждой перерисовке (опрос обновляет экран каждые 15 секунд).
            val aggregated = remember(log, period) { logAggregate(log, period) }
            val bySeverity = remember(log) { logCountsBy(log) { it.severity } }
            val byCategory = remember(log) { logCountsBy(log) { it.category }.take(10) }
            LazyColumn(Modifier.fillMaxSize()) {
                item { Text(stringResource(R.string.log_total, log.size), Modifier.padding(16.dp, 4.dp), fontWeight = FontWeight.SemiBold) }
                item {
                    // Переключатель периода стоит под заголовком раздела, которым он управляет.
                    Text(stringResource(R.string.log_by_period), Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LogPeriod.entries.forEach { p -> FilterChip(period == p, { period = p }, { Text(periodLabel(p)) }) }
                    }
                }
                items(aggregated) { c ->
                    DetailRow(periodCountLabel(c), c.count.toString(), Modifier.padding(horizontal = 16.dp))
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                item { Text(stringResource(R.string.log_by_severity), Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(bySeverity) { c ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.label, color = severityColor(c.label), style = MaterialTheme.typography.bodySmall)
                        Text(c.count.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text(stringResource(R.string.log_categories), Modifier.padding(16.dp, 8.dp, 16.dp, 2.dp), style = MaterialTheme.typography.titleSmall, color = scheme.primary) }
                items(byCategory) { c -> DetailRow(c.label, c.count.toString(), Modifier.padding(horizontal = 16.dp)) }
            }
        } else {
            val entries = log.filter { (severity.isEmpty() || severityGroup(it.severity) == severity) && (query.isEmpty() || it.raw.contains(query, ignoreCase = true)) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                SearchField(query, { query = it }, stringResource(R.string.log_search))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "" to stringResource(R.string.log_all), "err" to stringResource(R.string.log_level_err), "warning" to stringResource(R.string.log_level_warning),
                        "notice" to stringResource(R.string.log_level_notice), "info" to stringResource(R.string.log_level_info), "debug" to stringResource(R.string.log_level_debug),
                    ).forEach { (v, l) ->
                        FilterChip(severity == v, { severity = v }, { Text(l) })
                    }
                }
            }
            if (entries.isEmpty()) SectionHint(stringResource(R.string.log_empty))
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.category, color = scheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(e.severity, color = severityColor(e.severity), style = MaterialTheme.typography.labelSmall)
                            Text("${dayMonthText(e.day, e.month, currentLocale())} ${e.time}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(e.message.ifEmpty { e.raw }, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
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
