package com.usharik.routermanager

// Чистая логика рекламы и покупки «без рекламы»: без Android, покрывается JVM-тестами.

/** Идентификатор разовой покупки в Play Console (Монетизация → Разовые продукты). */
const val REMOVE_ADS_PRODUCT_ID = "remove_ads"

/** Покупка Play, сведённая к полям, нужным для решения «реклама отключена». */
data class PurchaseSnapshot(val products: List<String>, val purchased: Boolean, val pending: Boolean, val acknowledged: Boolean, val token: String)

data class RemoveAdsStatus(val owned: Boolean, val pending: Boolean, val tokensToAcknowledge: List<String>)

/** Что показывает интерфейс про покупку: цена из Play (null, пока неизвестна) и ожидающая оплата. */
data class RemoveAdsOffer(val formattedPrice: String? = null, val pending: Boolean = false)

object RemoveAdsPurchases {
    /**
     * Новое значение «без рекламы». Покупку выдаёт любой ответ, а отзывает (возврат денег) только полный список
     * покупок, начатый после последнего обновления: иначе запрос, ушедший до покупки, отменил бы её.
     */
    fun nextAdFree(current: Boolean, status: RemoveAdsStatus, fullList: Boolean): Boolean =
        if (status.owned || fullList) status.owned else current

    fun evaluate(purchases: List<PurchaseSnapshot>): RemoveAdsStatus {
        val removeAds = purchases.filter { REMOVE_ADS_PRODUCT_ID in it.products }
        val owned = removeAds.filter { it.purchased }
        return RemoveAdsStatus(
            owned = owned.isNotEmpty(),
            pending = owned.isEmpty() && removeAds.any { it.pending },
            // Play возвращает деньги за покупку, не подтверждённую за три дня.
            tokensToAcknowledge = owned.filterNot { it.acknowledged }.map { it.token },
        )
    }
}

/** Повод для полноэкранной рекламы: естественная пауза, когда пользователь закончил дело. */
enum class AdBreak(val analyticsName: String) {
    /** Роутер ушёл в перезагрузку: пользователь всё равно ждёт несколько минут. */
    REBOOT("reboot"),
    /** Профиль родительского контроля сохранён или удалён, диалог закрыт. */
    CHANGE_SAVED("change_saved"),
}

/** Пауза и момент действия пользователя, после которого она наступила (SystemClock.elapsedRealtime). */
data class AdBreakEvent(val reason: AdBreak, val actionAt: Long)

/** Всё, от чего зависит частота рекламы и предложений; время — миллисекунды Unix, хранится между запусками. */
data class AdPacingState(
    /** Первый запуск после установки (или после обновления до версии с рекламой). */
    val firstLaunchAt: Long = 0,
    /** Сколько раз приложение успешно подключалось к роутеру (не чаще раза за запуск процесса). */
    val sessions: Int = 0,
    /** Сохранённых изменений с последней полноэкранной рекламы. */
    val changesSinceAd: Int = 0,
    val lastInterstitialAt: Long = 0,
    /** Когда пользователь ответил на предложение отключить рекламу (купил или «не сейчас»). */
    val offerHandledAt: Long = 0,
    val reviewAskedAt: Long = 0,
    val reviewAskedVersion: Int = 0,
)

/**
 * Правила, чтобы реклама не раздражала:
 * - первые [GRACE_MS] после установки полноэкранной рекламы нет совсем;
 * - полноэкранная реклама — только в паузах ([AdBreak]) и не чаще раза в [MIN_INTERSTITIAL_INTERVAL_MS];
 *   после сохранения изменений — лишь каждое [CHANGES_PER_AD]-е;
 * - никогда во время ввода, при открытом окне и после нового касания экрана ([interfaceIdle]), не на экране входа;
 * - предложение отключить рекламу — карточкой после [OFFER_AFTER_SESSIONS] подключений, затем раз в [OFFER_REPEAT_MS],
 *   и диалогом сразу после закрытой рекламы, но не чаще раза в [AFTER_AD_OFFER_REPEAT_MS].
 */
object AdPacing {
    const val GRACE_MS = 24 * 60 * 60 * 1000L
    const val MIN_INTERSTITIAL_INTERVAL_MS = 20 * 60 * 1000L
    const val CHANGES_PER_AD = 3
    const val OFFER_AFTER_SESSIONS = 5
    const val OFFER_REPEAT_MS = 30L * 24 * 60 * 60 * 1000
    const val AFTER_AD_OFFER_REPEAT_MS = 7L * 24 * 60 * 60 * 1000
    const val REVIEW_AFTER_SESSIONS = 4
    const val REVIEW_REPEAT_MS = 90L * 24 * 60 * 60 * 1000

    fun launched(state: AdPacingState, now: Long) = if (state.firstLaunchAt == 0L) state.copy(firstLaunchAt = now) else state

    fun sessionStarted(state: AdPacingState) = state.copy(sessions = state.sessions + 1)

    fun changeSaved(state: AdPacingState) = state.copy(changesSinceAd = state.changesSinceAd + 1)

    fun interstitialShown(state: AdPacingState, now: Long) = state.copy(changesSinceAd = 0, lastInterstitialAt = now)

    fun offerHandled(state: AdPacingState, now: Long) = state.copy(offerHandledAt = now)

    fun reviewAsked(state: AdPacingState, now: Long, version: Int) = state.copy(reviewAskedAt = now, reviewAskedVersion = version)

    fun interstitialDue(state: AdPacingState, now: Long, reason: AdBreak): Boolean {
        if (state.firstLaunchAt == 0L || within(state.firstLaunchAt, now, GRACE_MS)) return false
        if (within(state.lastInterstitialAt, now, MIN_INTERSTITIAL_INTERVAL_MS)) return false
        return when (reason) {
            AdBreak.REBOOT -> true
            AdBreak.CHANGE_SAVED -> state.changesSinceAd >= CHANGES_PER_AD
        }
    }

    fun offerDue(state: AdPacingState, now: Long) =
        state.sessions >= OFFER_AFTER_SESSIONS && !within(state.offerHandledAt, now, OFFER_REPEAT_MS)

    fun afterAdOfferDue(state: AdPacingState, now: Long) = !within(state.offerHandledAt, now, AFTER_AD_OFFER_REPEAT_MS)

    /** Просьба об оценке: постоянному пользователю, раз за версию и не чаще раза в [REVIEW_REPEAT_MS]. */
    fun reviewDue(state: AdPacingState, now: Long, version: Int) =
        state.sessions >= REVIEW_AFTER_SESSIONS && state.reviewAskedVersion != version && !within(state.reviewAskedAt, now, REVIEW_REPEAT_MS)

    /** Сколько живёт повод для рекламы: вернувшись в приложение позже, пользователь её не ждёт. */
    const val BREAK_MAX_AGE_MS = 15_000L

    fun breakFresh(actionAt: Long, now: Long) = now - actionAt in 0..BREAK_MAX_AGE_MS

    /**
     * Можно ли показать полноэкранную рекламу прямо сейчас: нет открытых окон и клавиатуры, и пользователь не касался
     * экрана после действия, которое привело к паузе. Иначе реклама выскочила бы посреди следующего дела — лучше
     * пропустить этот повод совсем.
     */
    fun interfaceIdle(overlays: Int, keyboardVisible: Boolean, lastTouchAt: Long, actionAt: Long) =
        overlays == 0 && !keyboardVisible && lastTouchAt <= actionAt

    /**
     * Можно ли собирать аналитику. [privacyRequired] — UMP требует согласия в регионе пользователя (ЕЭЗ,
     * Великобритания, Швейцария); null — регион ещё неизвестен. В таких регионах нужна цель 1 TCF
     * («хранение и доступ к информации на устройстве») из [tcfPurposeConsents] — строки IABTCF_PurposeConsents.
     */
    fun analyticsAllowed(privacyRequired: Boolean?, tcfPurposeConsents: String?) = when (privacyRequired) {
        false -> true
        true -> tcfPurposeConsents?.firstOrNull() == '1'
        null -> false
    }

    /** Часы, переведённые назад, не должны навсегда заблокировать показ: такое время считается прошедшим. */
    private fun within(since: Long, now: Long, period: Long) = since != 0L && now - since in 0 until period
}
