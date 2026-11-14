package com.usharik.zyxelrouter

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Что сейчас происходит в интерфейсе: открытые окна и последнее касание. Реклама и просьба об оценке
 * появляются только в спокойный момент ([AdPacing.interfaceIdle]).
 */
class UiActivity {
    var overlays by mutableIntStateOf(0)
        private set
    /** SystemClock.elapsedRealtime последнего касания главного окна. */
    var lastTouchAt = 0L

    internal fun overlayOpened() { overlays++ }
    internal fun overlayClosed() { overlays = (overlays - 1).coerceAtLeast(0) }
}

val LocalUiActivity = staticCompositionLocalOf { UiActivity() }

/** Помечает окно (диалог, меню) открытым, пока оно в композиции: в это время реклама не показывается. */
@Composable
fun TrackOverlay() {
    val ui = LocalUiActivity.current
    DisposableEffect(ui) {
        ui.overlayOpened()
        onDispose { ui.overlayClosed() }
    }
}

/**
 * Реклама, согласие и покупка «без рекламы» на весь процесс: загруженная реклама и состояние сессии переживают
 * поворот экрана. Activity передаётся только в те вызовы, которым она нужна (формы UMP, показ рекламы, оценка).
 * Где и когда показывать — решает [AdPacing]; здесь только загрузка и показ.
 */
class AdsController(app: Application, val purchases: PlayPurchases, val pacing: AdPacingStore) {
    val consent = AdsConsent(app)
    val ui = UiActivity()
    private val interstitial = Interstitial(app) { adShownThisSession = true; pacing.interstitialShown() }
    private var adShownThisSession = false

    /** Предложение отключить рекламу сразу после закрытой полноэкранной рекламы. */
    var afterAdOffer by mutableStateOf(false)
        private set

    /** Форма согласия на рекламу не нужна тому, кто рекламу отключил: только уточняем регион для аналитики. */
    fun start(activity: Activity) = consent.gather(activity, showForm = !purchases.adFree.value)

    /** Реклама разрешена согласием и не отключена покупкой. */
    fun active(adFree: Boolean) = !adFree && consent.canShowAds

    fun preload() = interstitial.preload()

    /** Пользователь закончил дело: может быть, показать полноэкранную рекламу. */
    fun onBreak(activity: Activity, event: AdBreakEvent, adsActive: Boolean, keyboardVisible: Boolean) {
        if (event.reason == AdBreak.CHANGE_SAVED) pacing.changeSaved()
        if (!adsActive || !inForeground(activity)) return
        if (!AdPacing.breakFresh(event.actionAt, SystemClock.elapsedRealtime())) return
        if (!AdPacing.interfaceIdle(ui.overlays, keyboardVisible, ui.lastTouchAt, event.actionAt)) return
        if (!AdPacing.interstitialDue(pacing.state.value, AdPacingStore.now(), event.reason)) return
        interstitial.showIfReady(activity, event.reason.analyticsName) {
            if (AdPacing.afterAdOfferDue(pacing.state.value, AdPacingStore.now())) afterAdOffer = true
        }
    }

    fun buyRemoveAds(activity: Activity, source: String): Boolean {
        // Ответ на предложение после рекламы засчитывается сразу; карточка на «Обзоре» остаётся, если покупку отменили.
        if (source != "card") pacing.offerHandled()
        afterAdOffer = false
        return purchases.launchRemoveAds(activity, source)
    }

    fun dismissOffer() {
        pacing.offerHandled()
        afterAdOffer = false
        Telemetry.event("remove_ads_offer_dismissed")
    }

    /** Окно оценки Google Play постоянному пользователю; Play сам решает, показать ли его. Не в одну сессию с рекламой. */
    fun maybeAskReview(activity: Activity) {
        if (adShownThisSession || ui.overlays > 0 || !inForeground(activity)) return
        if (!AdPacing.reviewDue(pacing.state.value, AdPacingStore.now(), BuildConfig.VERSION_CODE)) return
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnCompleteListener { request ->
            if (!request.isSuccessful || ui.overlays > 0 || !inForeground(activity)) return@addOnCompleteListener
            // Попытка засчитывается, только когда окно действительно запущено: сбой Play не сжигает её на 90 дней.
            pacing.reviewAsked(BuildConfig.VERSION_CODE)
            manager.launchReviewFlow(activity, request.result)
            Telemetry.event("review_requested")
        }
    }

    fun showPrivacyOptions(activity: Activity) = consent.showPrivacyOptions(activity)

    /**
     * Приложение на экране и его окно в фокусе. Фокуса нет и при открытых системных окнах (форма согласия,
     * окно покупки Play, «Поделиться», шторка), которые [UiActivity] не видит.
     */
    private fun inForeground(activity: Activity): Boolean =
        !activity.isFinishing && activity.hasWindowFocus() &&
            (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false
}

/**
 * Согласие (UMP, GDPR): реклама запрашивается, только когда UMP это разрешает, а аналитика включается по ответу
 * формы ([AdPacing.analyticsAllowed]). MobileAds инициализируется в фоне: на слабых устройствах это долго.
 */
class AdsConsent(private val app: Application) {
    private val information = UserMessagingPlatform.getConsentInformation(app)
    private val main = Handler(Looper.getMainLooper())
    private var initializing = false
    private var initialized = false
    private var resolved = false

    var canShowAds by mutableStateOf(false)
        private set
    /** Место под баннер держится, пока не ясно, что реклама запрещена: так он не появится потом под пальцем. */
    var reservesAdSpace by mutableStateOf(true)
        private set
    /** В ЕЭЗ и Великобритании пользователь должен иметь возможность изменить выбор — пункт в меню. */
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    /** Инструментальные тесты выключают форму согласия: она открывается поверх приложения и закрывает экраны. */
    @Volatile var formsEnabled = true

    fun gather(activity: Activity, showForm: Boolean) {
        information.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
            if (showForm && formsEnabled) {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    error?.let { Log.w(TAG, "Consent form failed: ${it.errorCode} ${it.message}") }
                    resolved = true
                    refresh()
                }
            } else {
                resolved = true
            }
            refresh()
        }, { error ->
            Log.w(TAG, "Consent update failed: ${error.errorCode} ${error.message}")
            resolved = true
            refresh()
        })
        refresh()
    }

    fun showPrivacyOptions(activity: Activity) {
        canShowAds = false
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            error?.let { Log.w(TAG, "Privacy options failed: ${it.errorCode} ${it.message}") }
            refresh()
        }
    }

    private fun refresh() {
        val requirement = information.privacyOptionsRequirementStatus
        privacyOptionsRequired = requirement == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        canShowAds = initialized && information.canRequestAds()
        reservesAdSpace = information.canRequestAds() || !resolved
        val required = when (requirement) {
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED -> true
            ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED -> false
            else -> null
        }
        // Регион неизвестен (нет сети при запуске) — остаётся прошлое решение, применённое при старте процесса.
        if (resolved && required != null) {
            // UMP пишет строки TCF в настройки приложения по умолчанию.
            val purposes = app.getSharedPreferences(app.packageName + "_preferences", android.content.Context.MODE_PRIVATE).getString("IABTCF_PurposeConsents", null)
            val allowed = AdPacing.analyticsAllowed(required, purposes)
            Telemetry.setCollectionEnabled(allowed)
            storedDecision(app).edit().putBoolean(KEY_ANALYTICS, allowed).apply()
        }
        if (!information.canRequestAds() || initializing || initialized) return
        initializing = true
        Thread {
            MobileAds.initialize(app) {
                main.post {
                    initialized = true
                    initializing = false
                    refresh()
                }
            }
        }.start()
    }

    companion object {
        private const val TAG = "AdsConsent"
        private const val KEY_ANALYTICS = "analytics_allowed"
        private fun storedDecision(context: android.content.Context) = context.getSharedPreferences("consent", android.content.Context.MODE_PRIVATE)

        /** Последнее решение об аналитике: применяется сразу при старте, до сетевого ответа UMP. До первого ответа — нет. */
        fun analyticsAllowedLastTime(context: android.content.Context) = storedDecision(context).getBoolean(KEY_ANALYTICS, false)
    }
}

/**
 * Закреплённый адаптивный баннер. Слот занимает свою высоту и до разрешения на показ, поэтому ни согласие,
 * ни загрузка рекламы не сдвигают содержимое над ним.
 */
@Composable
fun AdBanner(showAd: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth.value.toInt().coerceAtLeast(1)
        // Обычная высота (50–60 dp на телефоне). «Большой» размер, который SDK 25 советует взамен, занимает до 15% экрана —
        // для служебного приложения это навязчиво. Если обычный размер уберут из SDK — перейти на getLargeAnchored….
        @Suppress("DEPRECATION")
        val adSize = remember(context, width) { AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, width) }
        Box(Modifier.fillMaxWidth().height(adSize.height.dp)) {
            if (showAd) LoadedBanner(adSize)
        }
    }
}

@Composable
private fun LoadedBanner(adSize: AdSize) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val adUnitId = stringResource(R.string.admob_banner_ad_unit_id)
    key(context, adUnitId, adSize) {
        val retry = remember { BannerRetry() }
        val adView = remember {
            AdView(context).apply {
                setAdSize(adSize)
                this.adUnitId = adUnitId
                adListener = object : AdListener() {
                    override fun onAdLoaded() = retry.reset()
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.w("AdBanner", "Banner load failed: $error")
                        retry.failed { loadAd(AdRequest.Builder().build()) }
                    }
                }
            }
        }
        AndroidView(factory = { adView }, modifier = Modifier.fillMaxSize(), onRelease = { it.destroy() }, update = { it.visibility = View.VISIBLE })
        DisposableEffect(adView, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> { adView.resume(); retry.resume() }
                    Lifecycle.Event.ON_PAUSE -> { adView.pause(); retry.pause() }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            adView.loadAd(AdRequest.Builder().build())
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { adView.pause(); retry.pause() }
            onDispose {
                retry.cancel()
                lifecycle.removeObserver(observer)
            }
        }
    }
}

/**
 * Повторная загрузка баннера с растущей паузой: на некоторых устройствах первые запросы падают и проходят позже.
 * Только пока экран на виду и не больше [DELAYS_MS].size попыток подряд: в фоне и без сети запросы не идут.
 */
private class BannerRetry {
    private val handler = Handler(Looper.getMainLooper())
    private var attempt = 0
    private var pending: (() -> Unit)? = null
    private var paused = false

    fun failed(load: () -> Unit) {
        if (attempt >= DELAYS_MS.size) return
        pending = load
        if (!paused) schedule()
    }

    private fun schedule() {
        val load = pending ?: return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ pending = null; load() }, DELAYS_MS[attempt++])
    }

    fun pause() { paused = true; handler.removeCallbacksAndMessages(null) }
    fun resume() { paused = false; schedule() }
    fun reset() { attempt = 0; pending = null }
    fun cancel() { pending = null; handler.removeCallbacksAndMessages(null) }

    private companion object { val DELAYS_MS = longArrayOf(3_000, 10_000, 30_000, 60_000, 120_000) }
}

/** Полноэкранная реклама: загружается заранее, показывается только готовая — пользователь её никогда не ждёт. */
private class Interstitial(private val app: Application, private val onShown: () -> Unit) {
    private val adUnitId = app.getString(R.string.admob_interstitial_ad_unit_id)
    private var ad: InterstitialAd? = null
    private var loadedAt = 0L
    private var loading = false

    fun preload() {
        if (ad != null && SystemClock.elapsedRealtime() - loadedAt > MAX_AGE_MS) ad = null
        if (ad != null || loading) return
        loading = true
        InterstitialAd.load(app, adUnitId, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(loaded: InterstitialAd) {
                loading = false
                ad = loaded
                loadedAt = SystemClock.elapsedRealtime()
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.w("Interstitial", "Load failed: $error")
                loading = false
            }
        })
    }

    /** Показывает готовую рекламу; если её нет — ничего не происходит, а следующая начинает загружаться. */
    fun showIfReady(activity: Activity, placement: String, onDismissed: () -> Unit) {
        val loaded = ad?.takeIf { SystemClock.elapsedRealtime() - loadedAt <= MAX_AGE_MS }
        ad = null
        if (loaded == null) return preload()
        loaded.fullScreenContentCallback = object : FullScreenContentCallback() {
            // Интервал между показами отсчитывается от настоящего показа, а не от попытки.
            override fun onAdShowedFullScreenContent() {
                onShown()
                Telemetry.event("interstitial_shown", mapOf("placement" to placement))
            }
            override fun onAdDismissedFullScreenContent() {
                preload()
                onDismissed()
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w("Interstitial", "Show failed: $error")
                preload()
            }
        }
        loaded.show(activity)
    }

    private companion object {
        /** AdMob считает загруженную рекламу просроченной через час. */
        const val MAX_AGE_MS = 55 * 60 * 1000L
    }
}
