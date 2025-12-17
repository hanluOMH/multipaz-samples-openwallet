package org.multipaz.samples.wallet.cmp

import android.content.ComponentName
import android.content.SharedPreferences
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.nfc.cardemulation.PollingFrame
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.multipaz.context.applicationContext
import org.multipaz.context.initializeApplication
import org.multipaz.util.Logger
import org.multipaz.util.toHex
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val TAG_SERVICE = "NfcObserveModeHelperService"
private const val TAG_HELPER = "NfcObserveModeHelper"
private const val API_LEVEL_BAKLAVA = 35 // Android 15 / Vanilla Ice Cream

/**
 * HostApduService that cooperates with [NfcObserveModeHelper] to disable observe mode when an
 * identity credential reader is detected. This mirrors the behaviour used in the Multipaz Test App.
 */
class NfcObserveModeHelperService : HostApduService() {

    override fun onCreate() {
        Logger.i(TAG_SERVICE, "onCreate")
        super.onCreate()
        initializeApplication(applicationContext)

        NfcObserveModeHelper.updateObserveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i(TAG_SERVICE, "onDestroy")
    }

    @RequiresApi(API_LEVEL_BAKLAVA)
    override fun processPollingFrames(frames: List<PollingFrame>) {
        var foundIdentityReader = false
        for (frame in frames) {
            if (frame.data.toHex().startsWith("6a028103")) {
                foundIdentityReader = true
            }
        }
        if (foundIdentityReader) {
            Logger.i(
                TAG_SERVICE,
                "Detected identity reader while in observe mode; inhibiting to allow transaction"
            )
            // TODO: authenticate user if required before disabling observe mode.
            NfcObserveModeHelper.inhibitObserveModeForTransaction()
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray? = null

    override fun onDeactivated(reason: Int) = Unit
}

/**
 * Helper for keeping Android observe mode in sync with the holder experience while using Multipaz.
 */
object NfcObserveModeHelper {

    private val sharedPreferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("NfcObserveModeHelper", 0)
    }

    private var localEnabledValue: Boolean? = null

    var isEnabled: Boolean
        get() {
            localEnabledValue?.let { return it }
            localEnabledValue = sharedPreferences.getBoolean("observeModeEnabled", false)
            return localEnabledValue!!
        }
        set(value) {
            sharedPreferences.edit(commit = true) {
                putBoolean("observeModeEnabled", value)
                localEnabledValue = value
            }
            updateObserveMode()
        }

    private var observeModeExplicitlyInhibited = false
    @OptIn(ExperimentalTime::class)
    private var inhibitForTransactionUntil: Instant? = null
    private var pollJob: Job? = null
    private var pollingFiltersRegistered = false

    /**
     * Temporarily inhibit observe mode for five seconds to let a transaction proceed.
     */
    @OptIn(ExperimentalTime::class)
    fun inhibitObserveModeForTransaction() {
        inhibitForTransactionUntil = Clock.System.now() + 5.seconds
        updateObserveMode()
    }

    fun inhibitObserveMode() {
        Logger.i(TAG_HELPER, "inhibitObserveMode() called")
        if (observeModeExplicitlyInhibited) {
            Logger.w(TAG_HELPER, "Observe mode already inhibited")
        }
        observeModeExplicitlyInhibited = true
        updateObserveMode()
    }

    fun uninhibitObserveMode() {
        Logger.i(TAG_HELPER, "uninhibitObserveMode() called")
        if (!observeModeExplicitlyInhibited) {
            Logger.w(TAG_HELPER, "Observe mode not currently inhibited")
        }
        observeModeExplicitlyInhibited = false
        updateObserveMode()
    }

    @OptIn(ExperimentalTime::class)
    internal fun updateObserveMode() {
        if (Build.VERSION.SDK_INT < API_LEVEL_BAKLAVA) {
            return
        }

        ensurePollingFiltersRegistered()

        if (!isEnabled) {
            pollJob?.cancel()
            pollJob = null
        } else if (pollJob == null) {
            pollJob = CoroutineScope(Dispatchers.IO).launch {
                while (true) {
                    delay(1.seconds)
                    updateObserveMode()
                }
            }
        }

        val adapter = NfcAdapter.getDefaultAdapter(applicationContext) ?: return

        val observeModeShouldBeEnabled = if (isEnabled) {
            if (observeModeExplicitlyInhibited) {
                false
            } else {
                inhibitForTransactionUntil?.let {
                    val now = Clock.System.now()
                    if (now < it) {
                        false
                    } else {
                        inhibitForTransactionUntil = null
                        true
                    }
                } ?: true
            }
        } else {
            false
        }

        val isObserveModeEnabledOnAdapter = adapter.isObserveModeEnabled
        if (isObserveModeEnabledOnAdapter != observeModeShouldBeEnabled) {
            Logger.i(
                TAG_HELPER,
                "isObserveModeEnabled=$isObserveModeEnabledOnAdapter changing to $observeModeShouldBeEnabled"
            )
            adapter.isObserveModeEnabled = observeModeShouldBeEnabled
        }
    }

    private fun ensurePollingFiltersRegistered() {
        if (pollingFiltersRegistered) {
            return
        }

        val adapter = NfcAdapter.getDefaultAdapter(applicationContext)
        if (adapter == null) {
            Logger.w(TAG_HELPER, "No NFC adapter available for observe mode")
            return
        }

        if (Build.VERSION.SDK_INT < API_LEVEL_BAKLAVA) {
            Logger.i(
                TAG_HELPER,
                "Observe mode not supported by Android version ${Build.VERSION.SDK_INT}"
            )
            return
        }

        if (!adapter.isObserveModeSupported) {
            Logger.i(TAG_HELPER, "Observe mode not supported by adapter")
            return
        }

        val componentName = ComponentName(applicationContext, NfcObserveModeHelperService::class.java)
        val cardEmulation = CardEmulation.getInstance(adapter)

        cardEmulation.registerPollingLoopPatternFilterForService(
            componentName,
            "6a028103.*",
            false
        )

        Logger.i(TAG_HELPER, "Polling filters registered")
        pollingFiltersRegistered = true
    }
}

