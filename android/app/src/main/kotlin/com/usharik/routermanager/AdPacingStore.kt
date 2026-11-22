package com.usharik.routermanager

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Хранит [AdPacingState] в SharedPreferences; все вызовы — с главного потока. */
class AdPacingStore(context: Context) {
    private val prefs = context.getSharedPreferences("ad_pacing", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AdPacing.launched(load(), now()).also(::save))
    val state: StateFlow<AdPacingState> = mutable.asStateFlow()
    private var sessionCounted = false

    /** Считает подключение к роутеру; повторные входы в том же процессе (поворот, перелогин) не считаются. */
    fun sessionStarted() {
        if (sessionCounted) return
        sessionCounted = true
        update(AdPacing::sessionStarted)
    }

    fun changeSaved() = update(AdPacing::changeSaved)
    fun interstitialShown() = update { AdPacing.interstitialShown(it, now()) }
    fun offerHandled() = update { AdPacing.offerHandled(it, now()) }
    fun reviewAsked(version: Int) = update { AdPacing.reviewAsked(it, now(), version) }

    private fun update(change: (AdPacingState) -> AdPacingState) {
        mutable.value = change(mutable.value).also(::save)
    }

    private fun load() = AdPacingState(
        firstLaunchAt = prefs.getLong("first_launch_at", 0),
        sessions = prefs.getInt("sessions", 0),
        changesSinceAd = prefs.getInt("changes_since_ad", 0),
        lastInterstitialAt = prefs.getLong("last_interstitial_at", 0),
        offerHandledAt = prefs.getLong("offer_handled_at", 0),
        reviewAskedAt = prefs.getLong("review_asked_at", 0),
        reviewAskedVersion = prefs.getInt("review_asked_version", 0),
    )

    private fun save(s: AdPacingState) = prefs.edit {
        putLong("first_launch_at", s.firstLaunchAt)
        putInt("sessions", s.sessions)
        putInt("changes_since_ad", s.changesSinceAd)
        putLong("last_interstitial_at", s.lastInterstitialAt)
        putLong("offer_handled_at", s.offerHandledAt)
        putLong("review_asked_at", s.reviewAskedAt)
        putInt("review_asked_version", s.reviewAskedVersion)
    }

    companion object {
        fun now() = System.currentTimeMillis()
    }
}
