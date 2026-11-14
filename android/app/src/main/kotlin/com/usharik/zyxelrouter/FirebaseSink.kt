package com.usharik.zyxelrouter

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** [TelemetrySink] поверх Firebase; null, если Firebase не настроен (нет google-services.json). */
class FirebaseSink private constructor(
    private val analytics: FirebaseAnalytics?,
    private val crashlytics: FirebaseCrashlytics?,
) : TelemetrySink {
    override fun logEvent(name: String, params: Map<String, Any>) {
        analytics?.logEvent(name, Bundle().apply {
            params.forEach { (key, value) -> if (value is Long) putLong(key, value) else putString(key, value.toString()) }
        })
    }

    override fun log(message: String) { crashlytics?.log(message) }

    override fun setCustomKey(key: String, value: String) { crashlytics?.setCustomKey(key, value) }

    override fun recordException(error: Throwable) { crashlytics?.recordException(error) }

    /** Отладочные сборки не портят статистику и отчёты о сбоях, что бы ни ответил пользователь. */
    override fun setCollectionEnabled(enabled: Boolean) {
        val on = enabled && !BuildConfig.DEBUG
        analytics?.setAnalyticsCollectionEnabled(on)
        crashlytics?.isCrashlyticsCollectionEnabled = on
    }

    companion object {
        fun create(context: Context): FirebaseSink? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            val analytics = runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull()
            val crashlytics = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()
            if (analytics == null && crashlytics == null) return null
            // В манифесте сбор выключен по умолчанию; сразу применяем прошлое решение по согласию, а свежий ответ UMP его уточнит.
            return FirebaseSink(analytics, crashlytics).apply { setCollectionEnabled(AdsConsent.analyticsAllowedLastTime(context)) }
        }
    }
}
