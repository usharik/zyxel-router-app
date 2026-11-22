package com.usharik.routermanager

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Разовая покупка [REMOVE_ADS_PRODUCT_ID] через Google Play Billing. Сервера нет, поэтому право «без рекламы» следует
 * списку покупок в Play: полный запрос и выдаёт, и отзывает его (возврат денег), обновление после покупки только выдаёт.
 * Флаг хранится локально, чтобы при запуске купившему не мелькнул баннер, пока Play отвечает.
 * Billing вызывает колбэки в своих потоках; всё состояние класса живёт на главном.
 */
/** Ответ на «Восстановить покупку». */
enum class RestoreResult { CHECKING, RESTORED, PENDING, NOT_FOUND, FAILED }

class PlayPurchases(context: Context) {
    private val prefs = context.getSharedPreferences("billing", Context.MODE_PRIVATE)
    private val adFreeState = MutableStateFlow(prefs.getBoolean(KEY_AD_FREE, false))
    val adFree: StateFlow<Boolean> = adFreeState.asStateFlow()

    private val offerState = MutableStateFlow(RemoveAdsOffer())
    val offer: StateFlow<RemoveAdsOffer> = offerState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var productDetails: ProductDetails? = null
    private var connecting = false
    /** Растёт с каждым обновлением покупок: запрос, начатый раньше, не должен отозвать только что сделанную покупку. */
    private var purchaseUpdates = 0

    /** Итог «Восстановить покупку» для окна «О приложении»; null — восстановление не запрашивали. */
    private val restoreState = MutableStateFlow<RestoreResult?>(null)
    val restore: StateFlow<RestoreResult?> = restoreState.asStateFlow()

    /** «Восстановить покупку»: тот же запрос к Play, но с ответом пользователю, нашлась ли покупка. */
    fun restore() {
        if (restoreState.value == RestoreResult.CHECKING) return
        restoreState.value = RestoreResult.CHECKING
        Telemetry.event("remove_ads_restore_clicked")
        // Play может не ответить вовсе (нет сервиса): пользователь не должен ждать вечно.
        main.postDelayed({ if (restoreState.value == RestoreResult.CHECKING) restoreState.value = RestoreResult.FAILED }, 15_000)
        refresh()
    }

    fun clearRestore() { if (restoreState.value != RestoreResult.CHECKING) restoreState.value = null }

    private fun restoreFailed() { if (restoreState.value == RestoreResult.CHECKING) restoreState.value = RestoreResult.FAILED }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases -> main.post { onPurchasesUpdated(result, purchases.orEmpty()) } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Подключается к Play и сверяет покупки; можно звать при каждом возвращении в приложение. */
    fun refresh() {
        if (client.isReady) return syncWithPlay()
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = onMain {
                connecting = false
                if (result.responseCode == BillingResponseCode.OK) syncWithPlay() else {
                    Log.w(TAG, "Billing setup failed: ${result.describe()}")
                    restoreFailed()
                }
            }

            override fun onBillingServiceDisconnected() = onMain { connecting = false; restoreFailed() }
        })
    }

    /** Открывает окно покупки Play; false, если товар ещё не загружен (нет сети или Play недоступен). */
    fun launchRemoveAds(activity: Activity, source: String): Boolean {
        Telemetry.event("remove_ads_clicked", mapOf("source" to source))
        val details = productDetails ?: run {
            failed("product_not_loaded", null)
            refresh()
            return false
        }
        val offerToken = details.oneTimePurchaseOfferDetails?.offerToken?.takeIf { it.isNotEmpty() } ?: run {
            failed("no_offer", null)
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offerToken).build()))
            .build()
        // Отказ запустить окно приходит и в onPurchasesUpdated с тем же кодом — там он и записывается.
        return client.launchBillingFlow(activity, params).responseCode == BillingResponseCode.OK
    }

    private fun syncWithPlay() {
        if (productDetails == null) queryProduct()
        queryPurchases()
    }

    private fun queryProduct() {
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(REMOVE_ADS_PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { result, details ->
            onMain {
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "Product query failed: ${result.describe()}")
                    return@onMain
                }
                val removeAds = details.productDetailsList.firstOrNull { it.productId == REMOVE_ADS_PRODUCT_ID }
                productDetails = removeAds
                offerState.update { it.copy(formattedPrice = removeAds?.oneTimePurchaseOfferDetails?.formattedPrice) }
            }
        }
    }

    private fun queryPurchases() {
        val updatesAtStart = purchaseUpdates
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            onMain {
                if (result.responseCode != BillingResponseCode.OK) {
                    Log.w(TAG, "Purchase query failed: ${result.describe()}")
                    restoreFailed()
                    return@onMain
                }
                val status = RemoveAdsPurchases.evaluate(purchases.map { it.snapshot() })
                if (status.owned && !adFree.value) Telemetry.event("remove_ads_restored")
                apply(status, fullList = updatesAtStart == purchaseUpdates)
                if (restoreState.value == RestoreResult.CHECKING) {
                    restoreState.value = when {
                        status.owned -> RestoreResult.RESTORED
                        status.pending -> RestoreResult.PENDING
                        else -> RestoreResult.NOT_FOUND
                    }
                }
            }
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
        purchaseUpdates++
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val status = RemoveAdsPurchases.evaluate(purchases.map { it.snapshot() })
                when {
                    status.owned && !adFree.value -> Telemetry.event("remove_ads_purchased")
                    status.pending -> Telemetry.event("remove_ads_pending")
                }
                apply(status, fullList = false)
            }
            // Куплено на другом устройстве или до переустановки: полный запрос восстановит покупку.
            BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            BillingResponseCode.USER_CANCELED -> Telemetry.event("remove_ads_cancelled")
            else -> failed("billing_flow", result)
        }
    }

    private fun apply(status: RemoveAdsStatus, fullList: Boolean) {
        val next = RemoveAdsPurchases.nextAdFree(adFreeState.value, status, fullList)
        if (next != adFreeState.value) {
            adFreeState.value = next
            prefs.edit { putBoolean(KEY_AD_FREE, next) }
        }
        offerState.update { it.copy(pending = status.pending) }
        status.tokensToAcknowledge.forEach { token ->
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { ack ->
                if (ack.responseCode != BillingResponseCode.OK) Log.w(TAG, "Acknowledge failed: ${ack.describe()}")
            }
        }
    }

    private fun Purchase.snapshot() = PurchaseSnapshot(
        products = products,
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        pending = purchaseState == Purchase.PurchaseState.PENDING,
        acknowledged = isAcknowledged,
        token = purchaseToken,
    )

    private fun onMain(block: () -> Unit) { main.post(block) }

    private fun BillingResult.describe() = "$responseCode $debugMessage"

    private fun failed(type: String, result: BillingResult?) {
        result?.let { Log.w(TAG, "Purchase failed: ${it.describe()}") }
        Telemetry.event("remove_ads_failed", buildMap {
            put("error_type", type)
            if (result != null) put("response_code", result.responseCode.toLong())
        })
    }

    private companion object {
        const val TAG = "PlayPurchases"
        const val KEY_AD_FREE = "ad_free"
    }
}
