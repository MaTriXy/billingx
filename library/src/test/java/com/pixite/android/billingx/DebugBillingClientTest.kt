package com.pixite.android.billingx

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import com.android.billingclient.api.BillingClient.BillingResponse
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingClient.SkuType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.InternalPurchasesResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.SkuDetails
import com.android.billingclient.api.SkuDetailsParams
import com.android.billingclient.api.SkuDetailsResponseListener
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doAnswer
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.concurrent.Executor

class DebugBillingClientTest {

  lateinit var activity: Activity
  lateinit var store: BillingStore
  lateinit var purchasesUpdatedListener: FakePurchasesUpdatedListener
  lateinit var client: DebugBillingClient
  private val localBroadcastInteractor = object : LocalBroadcastInteractor {
    override fun registerReceiver(context: Context, broadcastReceiver: BroadcastReceiver, intentFilter: IntentFilter) {
      // no op
    }
    override fun unregisterReceiver(context: Context, broadcastReceiver: BroadcastReceiver) {
      // no op
    }
  }

  private val emptyStateListener = object : BillingClientStateListener {
    override fun onBillingServiceDisconnected() {}
    override fun onBillingSetupFinished(responseCode: Int) {}
  }
  private val subsPurchase1 = Purchase("{\"orderId\":\"foo-1234\",\"packageName\":" +
      "\"com.foo.package\",\"productId\":\"com.foo.package.sku\",\"autoRenewing\":true,\"pur" +
      "chaseTime\":\"${Date().time}\",\"token\":\"1234567890\"}",
      "debug-signature-com.foo.package.sku-subs")
  private val subsPurchase2 = Purchase("{\"orderId\":\"bar-1234\",\"packageName\":\"" +
      "com.bar.package\",\"productId\":\"com.bar.package.sku\",\"autoRenewing\":false,\"purcha" +
      "seTime\":\"${Date().time}\",\"purchaseToken\":\"0987654321\"}",
      "debug-signature-com.bar.package.sku-subs")
  private val inappPurchase1 = Purchase("{\"orderId\":\"foo-1234\",\"packageName\":" +
      "\"com.foo.package\",\"productId\":\"com.foo.package.sku\",\"autoRenewing\":true,\"pur" +
      "chaseTime\":\"${Date().time}\",\"token\":\"1234567890\"}",
      "debug-signature-com.foo.package.sku-inapp")
  private val inappPurchase2 = Purchase("{\"orderId\":\"bar-1234\",\"packageName\":\"" +
      "com.bar.package\",\"productId\":\"com.bar.package.sku\",\"autoRenewing\":false,\"purcha" +
      "seTime\":\"${Date().time}\",\"purchaseToken\":\"0987654321\"}",
      "debug-signature-com.bar.package.sku-inapp")

  private val subs1Json = "{\"productId\":\"com.foo.package.sku\",\"type\":\"" +
      "subs\",\"price\":\"\$4.99\",\"price_amount_micros\":\"4990000\",\"price_currency_code\":\"" +
      "USD\",\"title\":\"Foo\",\"description\":\"So much Foo\",\"subscriptionPeriod\":\"P1W\",\"f" +
      "reeTrialPeriod\":\"P1W\"}"
  private val subs2Json = "{\"productId\":\"com.bar.package.sku\",\"type\":\"" +
      "subs\",\"price\":\"\$9.99\",\"price_amount_micros\":\"9990000\",\"price_currency_code\":\"" +
      "USD\",\"title\":\"Bar\",\"description\":\"So much Bar\",\"subscriptionPeriod\":\"P1M\"}"
  private val inapp1Json = "{\"productId\":\"com.baz.package.sku\",\"type\":\"" +
      "inapp\",\"price\":\"\$14.99\",\"price_amount_micros\":\"14990000\",\"price_currency_code\":" +
      "\"USD\",\"title\":\"Baz\",\"description\":\"So much Baz\"}"
  private val subs1 = SkuDetails(subs1Json)
  private val subs2 = SkuDetails(subs2Json)
  private val inapp1 = SkuDetails(inapp1Json)

  @Before fun setup() {
    activity = mock()
    store = mock() {
      on { getPurchases(eq(SkuType.INAPP)) } doReturn InternalPurchasesResult(BillingResponse.OK, listOf(inappPurchase1, inappPurchase2))
      on { getPurchases(eq(SkuType.SUBS)) } doReturn InternalPurchasesResult(BillingResponse.OK, listOf(subsPurchase1, subsPurchase2))
      on { getSkuDetails(any()) } doAnswer {
        val params = it.arguments.first() as SkuDetailsParams
        when (params.skuType) {
          SkuType.SUBS -> listOf(subs1, subs2)
          SkuType.INAPP -> listOf(inapp1)
          else -> throw IllegalArgumentException("Unknown skuType: ${params.skuType}")
        }.filter { it.sku in params.skusList }
      }
    }
    purchasesUpdatedListener = FakePurchasesUpdatedListener()
    client = DebugBillingClient(activity, purchasesUpdatedListener,
        Executor { command -> command?.run() }, store, localBroadcastInteractor)
  }

  @Test fun methodsFailWithoutStartedConnection() {
    assertThat(client.isReady).isFalse()
    assertThat(client.isFeatureSupported(FeatureType.SUBSCRIPTIONS))
        .isEqualTo(BillingResponse.SERVICE_DISCONNECTED)

    // connect
    client.startConnection(emptyStateListener)

    assertThat(client.isReady).isTrue()
    assertThat(client.isFeatureSupported(FeatureType.SUBSCRIPTIONS))
        .isEqualTo(BillingResponse.OK)
  }

  @Test fun queryPurchasesReturnsDisconnected() {
    val response = client.queryPurchases("com.foo")
    assertThat(response.responseCode).isEqualTo(BillingResponse.SERVICE_DISCONNECTED)
    assertThat(response.purchasesList).isNull()
  }

  @Test fun queryPurchasesReturnsSavedSubscriptions() {
    client.startConnection(emptyStateListener)

    val response = client.queryPurchases(SkuType.SUBS)
    assertThat(response.responseCode).isEqualTo(BillingResponse.OK)
    assertThat(response.purchasesList[0]).isEqualTo(subsPurchase1)
    assertThat(response.purchasesList[1]).isEqualTo(subsPurchase2)
  }

  @Test fun queryPurchasesReturnsSavedInAppPurchases() {
    client.startConnection(emptyStateListener)

    val response = client.queryPurchases(SkuType.INAPP)
    assertThat(response.responseCode).isEqualTo(BillingResponse.OK)
    assertThat(response.purchasesList[0]).isEqualTo(inappPurchase1)
    assertThat(response.purchasesList[1]).isEqualTo(inappPurchase2)
  }

  @Test fun querySkuDetailsAsyncReturnsDisconnected() {
    val listener = FakeSkuDetailsResponseListener()
    client.querySkuDetailsAsync(SkuDetailsParams.newBuilder()
        .setType(SkuType.SUBS).build(), listener)

    assertThat(listener.responseCode).isEqualTo(BillingResponse.SERVICE_DISCONNECTED)
    assertThat(listener.skuDetailsList).isNull()
  }

  @Test fun querySkuDetailsAsyncReturnsSavedSubscriptions() {
    val listener = FakeSkuDetailsResponseListener()
    client.startConnection(emptyStateListener)
    client.querySkuDetailsAsync(
        SkuDetailsParams.newBuilder()
            .setType(SkuType.SUBS)
            .setSkusList(listOf(subs1.sku, subs2.sku))
            .build(),
        listener)

    assertThat(listener.responseCode).isEqualTo(BillingResponse.OK)
    assertThat(listener.skuDetailsList).containsExactlyElementsIn(listOf(subs1, subs2))
  }

  @Test fun querySkuDetailsAsyncReturnsSavedInAppProducts() {
    val listener = FakeSkuDetailsResponseListener()
    client.startConnection(emptyStateListener)
    client.querySkuDetailsAsync(
        SkuDetailsParams.newBuilder()
            .setType(SkuType.INAPP)
            .setSkusList(listOf(inapp1.sku))
            .build(),
        listener)

    assertThat(listener.responseCode).isEqualTo(BillingResponse.OK)
    assertThat(listener.skuDetailsList).containsExactlyElementsIn(listOf(inapp1))
  }

  @Test fun queryPurchaseHistoryAsyncReturnsDisconnected() {
    val listener = FakePurchaseHistoryResponseListener()
    client.queryPurchaseHistoryAsync(SkuType.SUBS, listener)
    assertThat(listener.responseCode).isEqualTo(BillingResponse.SERVICE_DISCONNECTED)
    assertThat(listener.purchasesList).isNull()
  }

  @Test fun queryPurchaseHistoryAsyncReturnsSavedSubscriptions() {
    client.startConnection(emptyStateListener)

    val listener = FakePurchaseHistoryResponseListener()
    client.queryPurchaseHistoryAsync(SkuType.SUBS, listener)
    assertThat(listener.responseCode).isEqualTo(BillingResponse.OK)
    assertThat(listener.purchasesList).containsExactlyElementsIn(listOf(subsPurchase1, subsPurchase2))
  }

  @Test fun queryPurchaseHistoryAsyncReturnsSavedInAppProducts() {
    client.startConnection(emptyStateListener)

    val listener = FakePurchaseHistoryResponseListener()
    client.queryPurchaseHistoryAsync(SkuType.INAPP, listener)
    assertThat(listener.responseCode).isEqualTo(BillingResponse.OK)
    assertThat(listener.purchasesList).containsExactlyElementsIn(listOf(inappPurchase1, inappPurchase2))
  }

  class FakeSkuDetailsResponseListener : SkuDetailsResponseListener {
    var responseCode: Int? = null
    var skuDetailsList: List<SkuDetails>? = null
    override fun onSkuDetailsResponse(responseCode: Int, skuDetailsList: MutableList<SkuDetails>?) {
      this.responseCode = responseCode
      this.skuDetailsList = skuDetailsList
    }
  }

  class FakePurchaseHistoryResponseListener : PurchaseHistoryResponseListener {
    var responseCode: Int? = null
    var purchasesList: MutableList<Purchase>? = null
    override fun onPurchaseHistoryResponse(responseCode: Int, purchasesList: MutableList<Purchase>?) {
      this.responseCode = responseCode
      this.purchasesList = purchasesList
    }
  }

  class FakePurchasesUpdatedListener : PurchasesUpdatedListener {
    var responseCode: Int? = null
    var purchasesList: MutableList<Purchase>? = null
    override fun onPurchasesUpdated(responseCode: Int, purchases: MutableList<Purchase>?) {
      this.responseCode = responseCode
      this.purchasesList = purchases
    }
  }
}