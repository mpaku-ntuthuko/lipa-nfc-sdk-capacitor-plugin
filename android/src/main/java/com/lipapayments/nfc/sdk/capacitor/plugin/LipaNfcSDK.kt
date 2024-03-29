package com.lipapayments.nfc.sdk.capacitor.plugin

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.annotation.Keep
import com.lipa.tap.attestation.domain.models.SDKTransactionEvent
import com.lipa.tap.attestation.domain.models.SDKTransactionEvent.*
import com.lipa.tap.sdkManagement.adapter.out.DeviceStateUIAdapter
import com.lipa.tap.sdkManagement.application.port.`in`.VersionCheckCallback
import com.lipa.tap.sdkManagement.utils.IDeviceStateUIListener
import com.lipa.tap.transaction.domain.SdkLifeCycleEvent
import com.lipa.tap.transaction.domain.SdkLifeCycleEvent.*
import com.lipa.tap.transaction.domain.enums.NFCTransactionStartResult.NFC_NOT_ENABLED
import com.lipa.tap.transaction.domain.enums.NFCTransactionStartResult.STARTED
import com.lipa.tap.transaction.domain.enums.TransactionResult.*
import com.lipa.tap.transaction.domain.models.MorePaymentOptionsData
import com.lipa.tap.transaction.domain.models.TransactionStatus
import com.lipa.tap.utils.listeners.ITransactionListener
import com.lipa.tap.utils.startup.StartupManager
import com.lipa.tap.utils.LipaTapSDK
import com.lipa.tap.utils.NFCSdkConfiguration
import com.lipa.tap.utils.IStartupListener
import com.lipa.tap.utils.TransactionManager
import com.lipapayments.nfc.sdk.capacitor.plugin.EventMap.map
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.channelFlow
import org.jetbrains.annotations.ApiStatus.Experimental
import kotlin.reflect.KClass

object EventMap {
    internal val map: MutableMap<KClass<out SdkLifeCycleEvent>, List<(SdkLifeCycleEvent) -> Unit>> = mutableMapOf()
    private val TAG = "EventMap"

    infix fun <T : SdkLifeCycleEvent> map(eventMapper: Pair<KClass<T>, (T) -> Unit>) {
        val (event, onEvent) = eventMapper
        Log.d(TAG, "Mapping $event")
        @Suppress("UNCHECKED_CAST")
        map[event] = listOf(onEvent as ((SdkLifeCycleEvent) -> Unit))
    }

    /**
     * Not allowing adding of multiple events because removing listeners will require some id of
     * the onEvent to remove. Not sure if I want the API to be that annoying
     */
    fun <T : SdkLifeCycleEvent> remove(event: KClass<T>) {
        map[event] = listOf()
    }

    fun <T : SdkLifeCycleEvent> getListener(event: KClass<T>) = map[event]?.firstOrNull()

    fun onEvent(event: SdkLifeCycleEvent) {
        Log.d(TAG, "Running callback for $event")
        map.forEach { (evt, callbacks) ->
            if (evt == event) {
                callbacks.forEach { callback ->
                    try {
                        callback(event)
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed to run additional integrator callback")
                    }
                }
            }
        }

    }
}

enum class Env(val url: String) {
    TESTING("https://dev-gateway.lipapayments.com"), DEV("https://stage-gateway.lipapayments.com"), PROD("https://api.lipapayments.com");
}

@Experimental
object LipaNfcSDK {
    private val SDKScope = CoroutineScope(Dispatchers.Main.immediate + CoroutineName("LipaNFCSDK"))
    private val SDKTransactionScope = CoroutineScope(
        Dispatchers.Main.immediate + CoroutineName("LipaNFCSDKTransaction")
    )
    // SDK State
    private lateinit var startupManager: StartupManager
    private var terminalNickname: String? = null
    private var initialised = false
    private val TAG = "LipaNfcSDK"
    // End SDK State

    fun initialise(
        application: Application,
        apiKey: String,
        tenantId: String,
        env: Env = Env.PROD,
        getInTouchText: String = "Lipa Payments",
        getInTouchLink: String? = null,
        enableBuiltInReceiptScreen: Boolean = true,
        onEvent: (SdkLifeCycleEvent) -> Unit,
    ) {
        Log.d(TAG, "starting this mess")

        SDKScope.launch {// works

            Log.d(TAG, "launch this mess")
            channelFlow {
                Log.d(TAG, "channel this mess")
                Log.d(TAG, "inner launch this mess")
                init(
                    apiKey,
                    tenantId,
                    application,
                    env,
                    getInTouchText,
                    getInTouchLink,
                    enableBuiltInReceiptScreen,
                ) {
                    runBlocking { send(it) }
                }

                awaitClose {
                    // Keep me aliiiiive
                }
            }.collect {
                try {
                    Log.d(TAG, "collect this mess")
                    onEvent(it)
                    EventMap.onEvent(it)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to run init integrator callback")
                    e.printStackTrace()
                }
            }
        }

    }

    private fun init(
        apiKey: String,
        tenantId: String,
        application: Application,
        env: Env,
        getInTouchText: String = "Lipa Payments",
        getInTouchLink: String? = null,
        enableBuiltInReceiptScreen: Boolean = true,
        onEvent: (SdkLifeCycleEvent) -> Unit,
    ) {
        Log.d(TAG, "init this mess")
        if (!initialised) {
            LipaTapSDK.initialize(context = application,
                getInTouchText = getInTouchText,
                getInTouchLink = getInTouchLink,
                enableBuiltInReceiptScreen = enableBuiltInReceiptScreen,
            )
            initialised = true
        }
        onEvent(SdkInitialised)
        Log.d(TAG, "configure this mess")
        NFCSdkConfiguration.configure(
            /*baseUrl =*/env.url,
            /*apiKey =*/ apiKey,
            /*tenantId =*/ tenantId,
        )
        onEvent(SdkConfigured)
        Log.d(TAG, "startup this mess")
        startupManager = StartupManager(object : IStartupListener {
            override fun onSuccess() {
                Log.d(TAG, "Startup Successful")
                onEvent(SdkStartUpSuccess)
                DeviceStateUIAdapter.setDeviceStateUIListener(object : IDeviceStateUIListener {
                    override fun onDeviceStateChange(activated: Boolean) {
                        if (!activated) {
                            Log.d(TAG, "Device state changed to: Deactivated.")
                            onEvent(
                                SdkLifeCycleEvent.SdkDeviceState(
                                    DeviceState.DEACTIVATED,
                                    "Device state changed to: Deactivated."
                                )
                            )
                        } else {
                            Log.d(TAG, "Device state changed to: Activated.")
                            onEvent(SdkDeviceState(DeviceState.ACTIVATED, "Device state changed to: Activated."))
                        }

                    }

                    override fun onError(errorMessage: String) {
                        Log.d(TAG, "Error checking device state. $errorMessage")
                        onEvent(SdkDeviceState(DeviceState.ERRORED, "Error checking device state. $errorMessage"))
                    }
                })
            }

            override fun onError(error: String) {
                Log.d(TAG, "Startup Error: $error")
                onEvent(SdkStartUpError("Startup Error: $error"))
            }
        }, listOf(), object : VersionCheckCallback {
            override fun onResult(isValid: Boolean, isError: Boolean) {
                if (isValid) {
                    Log.d(TAG, "Valid Version")
                    onEvent(SdkVersionCheck(true, "Valid Version"))
                } else {
                    if (isError) {
                        Log.d(TAG, "Invalid Version")
                        onEvent(SdkVersionCheckError("Startup Error: Error validating version"))
                    } else {
                        onEvent(SdkVersionCheckError("Startup Error: Invalid Version,\n Please update."))
                    }
                }
            }
        })
        startupManager.initializeNfcSDK()
        onEvent(SdkStartUpInitialised)

    }

    fun setOperatorInfo(
        merchantName: String,
        merchantId: String,
        operatorId: String = merchantId,
        terminalNickname: String = LipaNfcSDK.terminalNickname ?: "",
        externalMerchant: Boolean = true,
        onEvent: (SdkLifeCycleEvent) -> Unit,
    ) {

        LipaNfcSDK.terminalNickname = terminalNickname
        SDKScope.launch {
            channelFlow {
                try {
                    if (terminalNickname.isBlank()) {
                        send(
                            SdkSetOperatorInfoError(
                                "Failed to set operator info. 'terminalNickname' is required"
                            )
                        )
                        return@channelFlow
                    }
                    val res = startupManager.setOperatorInfo(
                        merchantName = merchantName,
                        merchantId = merchantId,
                        operatorId = operatorId,
                        terminalNickname = terminalNickname,
                        externalMerchant = externalMerchant,
                    )
                    if (res.operatorUpdated)
                        send(SdkSetOperatorInfoSuccess)
                    else
                        send(SdkSetOperatorInfoError("Failed to set operator info. ${res.message}"))
                } catch (e: Exception) {
                    send(SdkSetOperatorInfoError("Failed to set operator info"))
                }
                awaitClose { /* KEEP ME RUNNING */ }
            }.collect {
                onEvent(it)
            }
        }
    }

    fun <T : SdkLifeCycleEvent> on(event: KClass<T>, block: (T) -> Unit) {
        map(event to block)
    }

    fun startTransaction(
        amount: Long? = null,
        onEvent: (SDKTransactionEvent) -> Unit,
    ) {
        SDKTransactionScope.launch { // under test
            Log.d(TAG, "launch this mess")
            channelFlow {
                transact(amount) { runBlocking { send(it) } }
                awaitClose { /* KEEP ME RUNNING */ }
            }.collect {
                onEvent(it)
            }
        }

    }

    private fun transact(
        amount: Long?,
        onEvent: (SDKTransactionEvent) -> Unit,
    ) {
        val res = TransactionManager.startTransaction(
            /* transactionListener = */
            object : ITransactionListener {
                override fun onMorePaymentOptionsClicked(
                    morePaymentOptionsData: MorePaymentOptionsData,
                ) {
                    Log.d(TAG, "onMorePaymentOptionsClicked with $morePaymentOptionsData")
                    onEvent(SDKOnMorePaymentOptions(morePaymentOptionsData.amount))
                }

                override fun onTransactionFinished(transactionStatus: TransactionStatus) {
                    Log.d(TAG, "Transaction Finished with status = $transactionStatus")
                    val event = when (transactionStatus.transactionResult) {
                        APPROVED -> SDKOnTransactionApproved(transactionResult = transactionStatus)
                        DECLINED -> SDKOnTransactionDeclined(transactionResult = transactionStatus)
                        MORE_PAYMENT_OPTIONS -> SDKOnMorePaymentOptions(
                            amount = transactionStatus.amount?.toLongOrNull()
                        )

                        CANCELLED, RESTART, TIMEOUT, ERROR -> SDKOnTransactionError(
                            transactionResult = transactionStatus
                        )
                    }
                    onEvent(event)

                }
            },
            /* amount = */ amount,
        )
        val event = when (res) {
            STARTED -> SDKTransactionStarted
            NFC_NOT_ENABLED -> SDKTransactionInitialisationError("NFC_NOT_ENABLED")
        }
        onEvent(event)
    }
}
