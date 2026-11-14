package com.usharik.zyxelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetizationTest {
    private val day = 24 * 60 * 60 * 1000L
    private val t0 = 1_800_000_000_000L
    /** Пользователь, у которого льготный период уже прошёл. */
    private val regular = AdPacingState(firstLaunchAt = t0 - 2 * day, sessions = 10)

    @Test fun noInterstitialDuringFirstDay() {
        val fresh = AdPacing.launched(AdPacingState(), t0)
        assertEquals(t0, fresh.firstLaunchAt)
        assertFalse(AdPacing.interstitialDue(fresh, t0 + day - 1, AdBreak.REBOOT))
        assertTrue(AdPacing.interstitialDue(fresh, t0 + day, AdBreak.REBOOT))
        // Повторный запуск не сдвигает дату первого.
        assertEquals(t0, AdPacing.launched(fresh, t0 + 5 * day).firstLaunchAt)
    }

    @Test fun unknownFirstLaunchShowsNothing() {
        assertFalse(AdPacing.interstitialDue(AdPacingState(), t0, AdBreak.REBOOT))
    }

    @Test fun savedChangesNeedSeveralBeforeAnAd() {
        var s = regular
        repeat(AdPacing.CHANGES_PER_AD - 1) { s = AdPacing.changeSaved(s) }
        assertFalse(AdPacing.interstitialDue(s, t0, AdBreak.CHANGE_SAVED))
        s = AdPacing.changeSaved(s)
        assertTrue(AdPacing.interstitialDue(s, t0, AdBreak.CHANGE_SAVED))
        s = AdPacing.interstitialShown(s, t0)
        assertEquals(0, s.changesSinceAd)
    }

    @Test fun minimumIntervalBetweenInterstitials() {
        val shown = AdPacing.interstitialShown(regular, t0)
        assertFalse(AdPacing.interstitialDue(shown, t0 + AdPacing.MIN_INTERSTITIAL_INTERVAL_MS - 1, AdBreak.REBOOT))
        assertTrue(AdPacing.interstitialDue(shown, t0 + AdPacing.MIN_INTERSTITIAL_INTERVAL_MS, AdBreak.REBOOT))
    }

    @Test fun clockMovedBackDoesNotBlockForever() {
        val shown = AdPacing.interstitialShown(regular, t0)
        assertTrue(AdPacing.interstitialDue(shown, t0 - 1000, AdBreak.REBOOT))
    }

    @Test fun offerCardAfterSeveralSessionsThenMonthly() {
        var s = AdPacingState(firstLaunchAt = t0)
        repeat(AdPacing.OFFER_AFTER_SESSIONS - 1) { s = AdPacing.sessionStarted(s) }
        assertFalse(AdPacing.offerDue(s, t0))
        s = AdPacing.sessionStarted(s)
        assertTrue(AdPacing.offerDue(s, t0))
        s = AdPacing.offerHandled(s, t0)
        assertFalse(AdPacing.offerDue(s, t0 + 29 * day))
        assertTrue(AdPacing.offerDue(s, t0 + 30 * day))
    }

    @Test fun afterAdOfferAtMostWeekly() {
        assertTrue(AdPacing.afterAdOfferDue(regular, t0))
        val handled = AdPacing.offerHandled(regular, t0)
        assertFalse(AdPacing.afterAdOfferDue(handled, t0 + 6 * day))
        assertTrue(AdPacing.afterAdOfferDue(handled, t0 + 7 * day))
    }

    @Test fun reviewOncePerVersionAndRarely() {
        assertFalse(AdPacing.reviewDue(AdPacingState(sessions = AdPacing.REVIEW_AFTER_SESSIONS - 1), t0, 3))
        assertTrue(AdPacing.reviewDue(regular, t0, 3))
        val asked = AdPacing.reviewAsked(regular, t0, 3)
        assertFalse(AdPacing.reviewDue(asked, t0 + 200 * day, 3))
        assertFalse(AdPacing.reviewDue(asked, t0 + 89 * day, 4))
        assertTrue(AdPacing.reviewDue(asked, t0 + 90 * day, 4))
    }

    private fun purchase(purchased: Boolean = true, pending: Boolean = false, acknowledged: Boolean = false, product: String = REMOVE_ADS_PRODUCT_ID, token: String = "t") =
        PurchaseSnapshot(listOf(product), purchased, pending, acknowledged, token)

    @Test fun purchasedRemoveAdsIsOwnedAndAcknowledged() {
        val status = RemoveAdsPurchases.evaluate(listOf(purchase(token = "a"), purchase(acknowledged = true, token = "b")))
        assertTrue(status.owned)
        assertFalse(status.pending)
        assertEquals(listOf("a"), status.tokensToAcknowledge)
    }

    @Test fun pendingPaymentIsNotOwned() {
        val status = RemoveAdsPurchases.evaluate(listOf(purchase(purchased = false, pending = true)))
        assertFalse(status.owned)
        assertTrue(status.pending)
        assertTrue(status.tokensToAcknowledge.isEmpty())
    }

    @Test fun otherProductsAndEmptyListGiveNothing() {
        assertFalse(RemoveAdsPurchases.evaluate(listOf(purchase(product = "something_else"))).owned)
        assertFalse(RemoveAdsPurchases.evaluate(emptyList()).owned)
    }

    @Test fun interstitialOnlyWhenInterfaceIsIdle() {
        assertTrue(AdPacing.interfaceIdle(overlays = 0, keyboardVisible = false, lastTouchAt = 100, actionAt = 100))
        assertFalse("открыт диалог", AdPacing.interfaceIdle(1, false, 100, 200))
        assertFalse("открыта клавиатура", AdPacing.interfaceIdle(0, true, 100, 200))
        assertFalse("пользователь уже касается экрана после действия", AdPacing.interfaceIdle(0, false, 300, 200))
    }

    @Test fun analyticsFollowsConsent() {
        assertTrue("вне ЕЭЗ согласие не требуется", AdPacing.analyticsAllowed(false, null))
        assertTrue(AdPacing.analyticsAllowed(true, "1110000000"))
        assertFalse("в ЕЭЗ без цели 1", AdPacing.analyticsAllowed(true, "0111"))
        assertFalse("в ЕЭЗ без ответа", AdPacing.analyticsAllowed(true, null))
        assertFalse("регион неизвестен", AdPacing.analyticsAllowed(null, "1"))
    }

    @Test fun adFreeGrantedByAnyAnswerRevokedOnlyByFullList() {
        val owned = RemoveAdsStatus(owned = true, pending = false, tokensToAcknowledge = emptyList())
        val none = RemoveAdsStatus(owned = false, pending = false, tokensToAcknowledge = emptyList())
        assertTrue("покупка из обновления", RemoveAdsPurchases.nextAdFree(false, owned, fullList = false))
        assertTrue("обновление без покупки не отзывает", RemoveAdsPurchases.nextAdFree(true, none, fullList = false))
        assertFalse("полный список без покупки — возврат денег", RemoveAdsPurchases.nextAdFree(true, none, fullList = true))
        assertFalse(RemoveAdsPurchases.nextAdFree(false, none, fullList = true))
    }

    @Test fun staleAdBreakIsDropped() {
        assertTrue(AdPacing.breakFresh(actionAt = 1_000, now = 5_000))
        assertFalse("пользователь вернулся через минуту", AdPacing.breakFresh(1_000, 61_000))
        assertFalse("часы ушли назад", AdPacing.breakFresh(5_000, 1_000))
    }
}
