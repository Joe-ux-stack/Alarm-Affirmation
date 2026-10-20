package com.whynotyou.alarm

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Pro through Google Play: two subscriptions (pro_yearly, pro_monthly, each with a 7-day free trial
 * offer) and a one-time pro_lifetime. Play is the source of truth on the phone; when signed in, each
 * purchase is also sent to the server so the AI connector knows about Pro.
 */
object Billing {
    const val YEARLY = "pro_yearly"; const val MONTHLY = "pro_monthly"; const val LIFETIME = "pro_lifetime"
    private val SUBS = listOf(YEARLY, MONTHLY)

    private var client: BillingClient? = null
    private val details = mutableMapOf<String, ProductDetails>()
    private var onChange: (() -> Unit)? = null
    private lateinit var app: Context

    private fun prefs(c: Context) = c.getSharedPreferences("pro", Context.MODE_PRIVATE)
    /** Active Play purchase seen on this phone (kept between launches; refreshed on every start). */
    fun hasPlayPro(c: Context) = prefs(c).getBoolean("play_pro", false)

    /** Connect (once) and refresh what this Google account owns. Call from App.onCreate. */
    fun start(c: Context) {
        app = c.applicationContext
        if (client != null) { refresh(); return }
        client = BillingClient.newBuilder(app)
            .setListener { r, list -> if (r.responseCode == BillingClient.BillingResponseCode.OK) list?.let { handle(it) } }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        connect { refresh(); loadDetails() }
    }

    private fun connect(then: () -> Unit) {
        val c = client ?: return
        if (c.isReady) { then(); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) { if (r.responseCode == BillingClient.BillingResponseCode.OK) then() }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun loadDetails() {
        val c = client ?: return
        fun q(ids: List<String>, type: String) = c.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(ids.map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
            }).build()
        ) { r, list -> if (r.responseCode == BillingClient.BillingResponseCode.OK) { list.forEach { details[it.productId] = it }; onChange?.invoke() } }
        q(SUBS, BillingClient.ProductType.SUBS); q(listOf(LIFETIME), BillingClient.ProductType.INAPP)
    }

    /** Re-reads owned purchases: sets Pro on or off and syncs them to the server. Also "Restore purchase". */
    fun refresh(done: ((Boolean) -> Unit)? = null) = connect {
        val c = client ?: return@connect
        val found = mutableListOf<Purchase>(); var pending = 2
        fun finish() {
            if (--pending > 0) return
            val active = found.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            prefs(app).edit().putBoolean("play_pro", active).apply()
            handle(found)
            onChange?.invoke(); done?.invoke(active)
        }
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { _, l -> found += l; finish() }
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { _, l -> found += l; finish() }
    }

    /** Price to show for a plan, from Play (local currency), or the fallback text. */
    fun price(id: String, fallback: String): String {
        val d = details[id] ?: return fallback
        d.oneTimePurchaseOfferDetails?.let { return it.formattedPrice }
        val phases = d.subscriptionOfferDetails?.flatMap { it.pricingPhases.pricingPhaseList }.orEmpty()
        return phases.lastOrNull { it.priceAmountMicros > 0 }?.formattedPrice ?: fallback
    }
    /** True when Play offers a free trial on this plan to this account (not used before). */
    fun hasTrial(id: String) = details[id]?.subscriptionOfferDetails?.any { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: true

    fun onChange(f: (() -> Unit)?) { onChange = f }

    /** Opens Google Play's purchase sheet. Returns false when Play isn't ready (e.g. products not set up yet). */
    fun buy(a: Activity, id: String): Boolean {
        val c = client ?: return false
        val d = details[id] ?: run { loadDetails(); return false }
        val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
        d.subscriptionOfferDetails?.let { offers ->
            // Prefer the offer with the free trial; Play only returns offers this account is eligible for.
            val best = offers.firstOrNull { o -> o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } } ?: offers.first()
            p.setOfferToken(best.offerToken)
        }
        return c.launchBillingFlow(a, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p.build())).build()).responseCode ==
            BillingClient.BillingResponseCode.OK
    }

    private fun handle(list: List<Purchase>) {
        val c = client ?: return
        list.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { pu ->
            prefs(app).edit().putBoolean("play_pro", true).apply()
            if (!pu.isAcknowledged) c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(pu.purchaseToken).build()) {}
            if (Account.signedIn(app)) CoroutineScope(Dispatchers.IO).launch {
                try { Account.call(app, "POST", "/api/purchase", JSONObject().put("token", pu.purchaseToken).put("product", pu.products.firstOrNull() ?: YEARLY)) } catch (_: Throwable) {}
            }
        }
        onChange?.invoke()
    }
}
