package me.phh.treble.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Handler
import android.os.Looper
import android.os.ServiceManager
import android.os.SystemClock
import android.os.SystemProperties
import android.preference.PreferenceManager
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.feature.MmTelFeature
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

@SuppressLint("StaticFieldLeak")
object Ims {
    const val IMS_PACKAGE = "com.mediatek.ims"

    private const val MTK_IMS_PACKAGE = IMS_PACKAGE
    private const val MTK_IMS_STACK_PROPERTY = "persist.sys.phh.ims.mtk"
    private const val IMS_NETWORK_TIMEOUT_MS = 120_000
    private const val IMS_REFRESH_COOLDOWN_MS = 30_000L
    private const val IMS_WATCHDOG_INTERVAL_MS = 15_000L
    private const val IMS_HEALTH_TIMEOUT_MS = 60_000L
    private const val IMS_STACK_RESTART_TIMEOUT_MS = 45_000L
    private const val IMS_STACK_RESTART_COOLDOWN_MS = 5 * 60_000L
    private const val IMS_STACK_REEVALUATE_DELAY_MS = 10_000L

    private enum class RegistrationState {
        UNKNOWN,
        REGISTERING,
        REGISTERED,
        UNREGISTERED,
    }

    private enum class RecoveryStep {
        NONE,
        NETWORK_REFRESHED,
        MTK_IMS_RESTARTED,
    }

    lateinit var ctxt: WeakReference<Context>

    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> mainHandler.post(command) }

    private var prefsListenerRegistered = false
    private var lastRefreshElapsed = 0L
    private var wasSuspended = false
    private var requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var imsCallbacksSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private var imsCallbacksRegistered = false
    private var imsBearerAvailable = false
    private var imsVoiceCapable = false
    private var imsSmsCapable = false
    private var imsRegistrationState = RegistrationState.UNKNOWN
    private var imsDegradedSinceElapsed = 0L
    private var imsRecoveryStepElapsed = 0L
    private var imsRecoveryStep = RecoveryStep.NONE
    private var imsRequestTimeoutCount = 0
    private var lastMtkRestartElapsed = 0L
    private var mtkRestartPending = false
    private var lastImsDisconnectReason = "none"

    private val imsWatchdogRunnable = object : Runnable {
        override fun run() {
            val c = ctxt.get() ?: return
            evaluateImsHealth(c, "watchdog")
            if (registeredNetwork) {
                mainHandler.postDelayed(this, IMS_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private val imsRegistrationCallback = object : ImsMmTelManager.RegistrationCallback() {
        override fun onRegistered(imsTransportType: Int) {
            imsRegistrationState = RegistrationState.REGISTERED
            lastImsDisconnectReason = "registered:$imsTransportType"
            Log.i("PHH", "IMS registered on transport $imsTransportType")
            ctxt.get()?.let { evaluateImsHealth(it, "IMS registered") }
        }

        override fun onRegistering(imsTransportType: Int) {
            imsRegistrationState = RegistrationState.REGISTERING
            Log.i("PHH", "IMS registering on transport $imsTransportType")
            ctxt.get()?.let { evaluateImsHealth(it, "IMS registering") }
        }

        override fun onUnregistered(info: ImsReasonInfo) {
            imsRegistrationState = RegistrationState.UNREGISTERED
            imsVoiceCapable = false
            imsSmsCapable = false
            lastImsDisconnectReason = info.toString()
            Log.w("PHH", "IMS unregistered: $info")
            ctxt.get()?.let { evaluateImsHealth(it, "IMS unregistered") }
        }

        override fun onTechnologyChangeFailed(imsTransportType: Int, info: ImsReasonInfo) {
            lastImsDisconnectReason = "techChangeFailed:$info"
            Log.w("PHH", "IMS technology change failed on transport $imsTransportType: $info")
            ctxt.get()?.let { evaluateImsHealth(it, "IMS technology change failed") }
        }
    }

    private val imsCapabilityCallback = object : ImsMmTelManager.CapabilityCallback() {
        override fun onCapabilitiesStatusChanged(capabilities: MmTelFeature.MmTelCapabilities) {
            imsVoiceCapable = capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE)
            imsSmsCapable = capabilities.isCapable(MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_SMS)
            Log.i(
                "PHH",
                "IMS capabilities updated: voice=$imsVoiceCapable sms=$imsSmsCapable raw=$capabilities"
            )
            ctxt.get()?.let { evaluateImsHealth(it, "IMS capabilities changed") }
        }
    }

    val networkListener = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.post {
                Log.i("PHH", "Network $network is available!")
                if (!useMtkImsStack()) return@post
                imsBearerAvailable = true
                resetImsRequestTimeouts("IMS network available")
                val c = ctxt.get() ?: return@post
                registerImsCallbacksIfNeeded(c)
                evaluateImsHealth(c, "IMS network available")
            }
        }

        override fun onLost(network: Network) {
            mainHandler.post {
                Log.w("PHH", "IMS network $network lost")
                val c = ctxt.get() ?: return@post
                val sp = PreferenceManager.getDefaultSharedPreferences(c)
                if (!useMtkImsStack()) {
                    if (!sp.getBoolean(ImsSettings.requestNetwork, false)) {
                        registeredNetwork = false
                        wasSuspended = false
                        requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        return@post
                    }
                    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@post
                    refreshImsNetworkRequest(cm, "network lost", force = true)
                    return@post
                }
                imsBearerAvailable = false
                if (!sp.getBoolean(ImsSettings.requestNetwork, false)) {
                    registeredNetwork = false
                    wasSuspended = false
                    requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    unregisterImsCallbacks()
                    stopImsWatchdog()
                    resetImsRecoveryState("IMS network lost while disabled")
                    return@post
                }
                evaluateImsHealth(c, "IMS network lost")
                val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@post
                refreshImsNetworkRequest(cm, "network lost", force = true)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            mainHandler.post {
                Log.i("PHH", "Received info about network $network, got $networkCapabilities")
                val suspended = !networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
                )
                val c = ctxt.get() ?: return@post
                if (wasSuspended && !suspended) {
                    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@post
                    refreshImsNetworkRequest(cm, "suspension cleared")
                }
                wasSuspended = suspended
                if (!useMtkImsStack()) return@post
                imsBearerAvailable = true
                resetImsRequestTimeouts("IMS network capabilities changed")
                registerImsCallbacksIfNeeded(c)
                evaluateImsHealth(c, "IMS network capabilities changed")
            }
        }

        override fun onUnavailable() {
            mainHandler.post {
                val c = ctxt.get() ?: return@post
                val sp = PreferenceManager.getDefaultSharedPreferences(c)
                if (!useMtkImsStack()) {
                    Log.w("PHH", "IMS network request timed out, retrying")
                    if (!sp.getBoolean(ImsSettings.requestNetwork, false)) {
                        registeredNetwork = false
                        return@post
                    }
                    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@post
                    refreshImsNetworkRequest(cm, "timeout", force = true)
                    return@post
                }
                imsBearerAvailable = false
                imsRequestTimeoutCount += 1
                lastImsDisconnectReason = "requestTimeout:$imsRequestTimeoutCount"
                Log.w("PHH", "IMS network request timed out ($imsRequestTimeoutCount), retrying")
                if (!sp.getBoolean(ImsSettings.requestNetwork, false)) {
                    registeredNetwork = false
                    unregisterImsCallbacks()
                    stopImsWatchdog()
                    resetImsRequestTimeouts("IMS request unavailable while disabled")
                    resetImsRecoveryState("IMS request unavailable while disabled")
                    return@post
                }
                if (recoverAfterImsRequestTimeout(c)) {
                    return@post
                }
                val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@post
                refreshImsNetworkRequest(cm, "timeout", force = true)
                evaluateImsHealth(c, "IMS request unavailable")
            }
        }
    }

    var registeredNetwork = false

    val spListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        mainHandler.post {
            val c = ctxt.get() ?: return@post
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@post

            if (key == ImsSettings.requestNetwork && !useMtkImsStack()) {
                val enabled = sp.getBoolean(ImsSettings.requestNetwork, false)
                if (enabled) {
                    if (!registeredNetwork) {
                        requestImsNetwork(cm)
                        registeredNetwork = true
                    } else {
                        val subId = getTargetSubId(c)
                        if (subId != requestedSubId) {
                            refreshImsNetworkRequest(cm, "target subId changed", force = true)
                        }
                    }
                } else if (registeredNetwork) {
                    unregisterImsNetworkRequest(cm)
                    registeredNetwork = false
                    wasSuspended = false
                    requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                }
                return@post
            }

            when (key) {
                ImsSettings.requestNetwork -> {
                    val enabled = sp.getBoolean(ImsSettings.requestNetwork, false)
                    if (enabled) {
                        registerImsCallbacksIfNeeded(c)
                        if (!registeredNetwork) {
                            requestImsNetwork(cm)
                        } else {
                            val subId = getTargetSubId(c)
                            if (subId != requestedSubId || subId != imsCallbacksSubId) {
                                registerImsCallbacksIfNeeded(c, force = true)
                                refreshImsNetworkRequest(cm, "target subId changed", force = true)
                            } else {
                                startImsWatchdog()
                                evaluateImsHealth(c, "IMS request enabled")
                            }
                        }
                    } else {
                        if (registeredNetwork) {
                            unregisterImsNetworkRequest(cm)
                        }
                        registeredNetwork = false
                        imsBearerAvailable = false
                        wasSuspended = false
                        requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                        resetImsRequestTimeouts("IMS request disabled")
                        unregisterImsCallbacks()
                        stopImsWatchdog()
                        resetImsRecoveryState("IMS request disabled")
                    }
                }

                ImsSettings.forceEnableSettings -> {
                    val value = if (sp.getBoolean(key, false)) "1" else "0"
                    Props.safeSetprop("persist.dbg.volte_avail_ovr", value)
//                    Props.safeSetprop("persist.dbg.wfc_avail_ovr", value)
                    Props.safeSetprop("persist.dbg.allow_ims_off", value)
                }
            }
        }
    }

    val mHidlService = android.hidl.manager.V1_0.IServiceManager.getService()

    val mAllSlots = listOf(
        "imsrild1", "imsrild2", "imsrild3", "slot1", "slot2", "slot3",
        "imsSlot1", "imsSlot2", "mtkSlot1", "mtkSlot2", "imsradio0", "imsradio1"
    )
    val gotMtkP = mAllSlots
        .find { i -> mHidlService.get("vendor.mediatek.hardware.radio@3.0::IRadio", i) != null } != null
    val gotMtkQ = mAllSlots
        .find { i -> mHidlService.get("vendor.mediatek.hardware.mtkradioex@1.0::IMtkRadioEx", i) != null } != null
    val gotMtkR = mAllSlots
        .find { i -> mHidlService.get("vendor.mediatek.hardware.mtkradioex@2.0::IMtkRadioEx", i) != null } != null
    val gotMtkS = mAllSlots
        .find { i -> mHidlService.get("vendor.mediatek.hardware.mtkradioex@3.0::IMtkRadioEx", i) != null } != null
    val gotMtkAidl = mAllSlots
        .find { i -> ServiceManager.getService("vendor.mediatek.hardware.mtkradioex.ims.IMtkRadioExIms/" + i) != null } != null
    val gotQcomHidl = mAllSlots
        .find { i -> mHidlService.get("vendor.qti.hardware.radio.ims@1.0::IImsRadio", i) != null } != null
    val gotQcomHidlMoto = gotQcomHidl
        && SystemProperties.get("ro.product.vendor.brand", "N/A").equals("motorola")
    val gotQcomAidl = mAllSlots
        .find { i -> ServiceManager.getService("vendor.qti.hardware.radio.ims.IImsRadio/" + i) != null } != null
    val gotSLSI = mAllSlots
        .find { i -> mHidlService.get("vendor.samsung_slsi.telephony.hardware.radio@1.0::IOemSamsungslsi", i) != null } != null
    val gotSPRD = mAllSlots
        .find { i -> mHidlService.get("vendor.sprd.hardware.radio@1.0::IExtRadio", i) != null } != null
    val gotHW = mAllSlots
        .find { i -> mHidlService.get("vendor.huawei.hardware.radio@1.0::IRadio", i) != null } != null

    fun startup(ctxt: Context) {
        if (!ImsSettings.enabled()) return
        val appContext = ctxt.applicationContext
        this.ctxt = WeakReference(appContext)

        val sp = PreferenceManager.getDefaultSharedPreferences(appContext)
        if (!prefsListenerRegistered) {
            sp.registerOnSharedPreferenceChangeListener(spListener)
            prefsListenerRegistered = true
        }

        disableVoWifi(appContext)
        refreshOverlays(appContext)

        // Refresh parameters on boot (and after IMS install).
        spListener.onSharedPreferenceChanged(sp, ImsSettings.requestNetwork)
        spListener.onSharedPreferenceChanged(sp, ImsSettings.forceEnableSettings)
    }

    private fun refreshOverlays(ctxt: Context) {
        val gotFloss = ctxt.packageManager.getInstalledPackages(0).find { it.packageName == IMS_PACKAGE } != null
        val useMtkImsStack = useMtkImsStack()
        val allOverlays = listOf(
            "me.phh.treble.overlay.flossims_telephony",
            "me.phh.treble.overlay.mtkims_telephony",
            "me.phh.treble.overlay.cafims_telephony",
            "me.phh.treble.overlay.slsiims_telephony",
            "me.phh.treble.overlay.sprdims_telephony",
            "me.phh.treble.overlay.hwims_telephony"
        )
        val selectOverlay = when {
            gotFloss -> "me.phh.treble.overlay.flossims_telephony"
            useMtkImsStack && (gotMtkP || gotMtkQ || gotMtkR || gotMtkS || gotMtkAidl) ->
                "me.phh.treble.overlay.mtkims_telephony"
            gotQcomHidl || gotQcomAidl -> "me.phh.treble.overlay.cafims_telephony"
            gotSLSI -> "me.phh.treble.overlay.slsiims_telephony"
            gotSPRD -> "me.phh.treble.overlay.sprdims_telephony"
            gotHW -> "me.phh.treble.overlay.hwims_telephony"
            else -> null
        }
        if (gotFloss && !useMtkImsStack) {
            Props.safeSetprop("persist.sys.phh.ims.floss", "true")
        } else {
            Props.safeSetprop("persist.sys.phh.ims.floss", "false")
        }
        if (selectOverlay != null) {
            allOverlays
                .filter { it != selectOverlay }
                .forEach { OverlayUtil.setOverlayEnabled(it, false) }
            OverlayUtil.setOverlayEnabled(selectOverlay, true)
        }
    }

    private fun getTargetSubId(c: Context): Int {
        val defaultSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(defaultSubId)) return defaultSubId

        val subMgr = c.getSystemService(SubscriptionManager::class.java)
            ?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val activeSubs = try {
            subMgr.activeSubscriptionIdList
        } catch (t: Throwable) {
            Log.e("PHH", "Failed to read active subscriptions", t)
            IntArray(0)
        }
        return activeSubs.firstOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun buildImsNetworkRequest(c: Context): NetworkRequest {
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        val subId = getTargetSubId(c)
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            builder.setNetworkSpecifier(
                TelephonyNetworkSpecifier.Builder()
                    .setSubscriptionId(subId)
                    .build()
            )
        }
        requestedSubId = subId
        return builder.build()
    }

    private fun startImsWatchdog() {
        mainHandler.removeCallbacks(imsWatchdogRunnable)
        if (registeredNetwork) {
            mainHandler.postDelayed(imsWatchdogRunnable, IMS_WATCHDOG_INTERVAL_MS)
        }
    }

    private fun stopImsWatchdog() {
        mainHandler.removeCallbacks(imsWatchdogRunnable)
    }

    private fun registerImsCallbacksIfNeeded(c: Context, force: Boolean = false) {
        val subId = getTargetSubId(c)
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            unregisterImsCallbacks()
            return
        }
        if (!force && imsCallbacksRegistered && imsCallbacksSubId == subId) {
            return
        }

        unregisterImsCallbacks()
        try {
            val imsManager = ImsMmTelManager.createForSubscriptionId(subId)
            imsManager.registerImsRegistrationCallback(callbackExecutor, imsRegistrationCallback)
            imsManager.registerMmTelCapabilityCallback(callbackExecutor, imsCapabilityCallback)
            imsCallbacksSubId = subId
            imsCallbacksRegistered = true
            imsRegistrationState = RegistrationState.UNKNOWN
            imsVoiceCapable = false
            imsSmsCapable = false
            Log.i("PHH", "Registered IMS callbacks for subId $subId")
        } catch (t: Throwable) {
            imsCallbacksSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            imsCallbacksRegistered = false
            imsRegistrationState = RegistrationState.UNKNOWN
            imsVoiceCapable = false
            imsSmsCapable = false
            Log.e("PHH", "Failed to register IMS callbacks for subId $subId", t)
        }
    }

    private fun unregisterImsCallbacks() {
        if (!imsCallbacksRegistered || !SubscriptionManager.isValidSubscriptionId(imsCallbacksSubId)) {
            imsCallbacksSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
            imsCallbacksRegistered = false
            imsRegistrationState = RegistrationState.UNKNOWN
            imsVoiceCapable = false
            imsSmsCapable = false
            return
        }

        try {
            val imsManager = ImsMmTelManager.createForSubscriptionId(imsCallbacksSubId)
            imsManager.unregisterImsRegistrationCallback(imsRegistrationCallback)
        } catch (t: Throwable) {
            Log.d("PHH", "Failed to unregister IMS registration callback", t)
        }

        try {
            val imsManager = ImsMmTelManager.createForSubscriptionId(imsCallbacksSubId)
            imsManager.unregisterMmTelCapabilityCallback(imsCapabilityCallback)
        } catch (t: Throwable) {
            Log.d("PHH", "Failed to unregister IMS capability callback", t)
        }

        imsCallbacksSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        imsCallbacksRegistered = false
        imsRegistrationState = RegistrationState.UNKNOWN
        imsVoiceCapable = false
        imsSmsCapable = false
    }

    private fun unregisterImsNetworkRequest(cm: ConnectivityManager) {
        try {
            cm.unregisterNetworkCallback(networkListener)
        } catch (t: Throwable) {
            Log.d("PHH", "IMS network callback was not registered", t)
        }
    }

    private fun refreshImsNetworkRequest(
        cm: ConnectivityManager,
        reason: String,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshElapsed < IMS_REFRESH_COOLDOWN_MS) {
            Log.d("PHH", "Skipping IMS request refresh ($reason), cooldown active")
            return
        }
        unregisterImsNetworkRequest(cm)
        requestImsNetwork(cm)
        Log.i("PHH", "Refreshed IMS network request ($reason)")
    }

    private fun requestImsNetwork(cm: ConnectivityManager) {
        val c = ctxt.get() ?: return
        val imsNetworkRequest = buildImsNetworkRequest(c)
        if (useMtkImsStack()) {
            registerImsCallbacksIfNeeded(c)
        }
        cm.requestNetwork(imsNetworkRequest, networkListener, IMS_NETWORK_TIMEOUT_MS)
        lastRefreshElapsed = SystemClock.elapsedRealtime()
        registeredNetwork = true
        if (useMtkImsStack()) {
            startImsWatchdog()
        }
    }

    private fun isImsHealthy(): Boolean {
        return imsBearerAvailable
            && imsRegistrationState == RegistrationState.REGISTERED
            && imsVoiceCapable
    }

    private fun resetImsRequestTimeouts(reason: String) {
        if (imsRequestTimeoutCount != 0) {
            Log.i("PHH", "Clearing IMS request timeout counter ($reason)")
        }
        imsRequestTimeoutCount = 0
    }

    private fun buildHealthSummary(): String {
        return "bearer=$imsBearerAvailable reg=$imsRegistrationState voice=$imsVoiceCapable " +
            "sms=$imsSmsCapable subId=$imsCallbacksSubId reason=$lastImsDisconnectReason"
    }

    private fun resetImsRecoveryState(reason: String) {
        if (imsDegradedSinceElapsed != 0L || imsRecoveryStep != RecoveryStep.NONE || mtkRestartPending) {
            Log.i("PHH", "Clearing IMS recovery state ($reason)")
        }
        imsDegradedSinceElapsed = 0L
        imsRecoveryStepElapsed = 0L
        imsRecoveryStep = RecoveryStep.NONE
        mtkRestartPending = false
    }

    private fun evaluateImsHealth(c: Context, reason: String) {
        if (!registeredNetwork) {
            stopImsWatchdog()
            resetImsRequestTimeouts("IMS request is not active")
            resetImsRecoveryState("IMS request is not active")
            return
        }

        startImsWatchdog()
        registerImsCallbacksIfNeeded(c)

        if (!imsBearerAvailable) {
            resetImsRecoveryState("IMS bearer unavailable ($reason)")
            return
        }

        if (isImsHealthy()) {
            resetImsRequestTimeouts("IMS healthy")
            if (imsDegradedSinceElapsed != 0L || imsRecoveryStep != RecoveryStep.NONE) {
                Log.i("PHH", "IMS recovered ($reason): ${buildHealthSummary()}")
            }
            resetImsRecoveryState("IMS healthy")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (imsDegradedSinceElapsed == 0L) {
            imsDegradedSinceElapsed = now
            Log.w("PHH", "IMS bearer is up but IMS is unhealthy ($reason): ${buildHealthSummary()}")
            return
        }

        when (imsRecoveryStep) {
            RecoveryStep.NONE -> {
                if (now - imsDegradedSinceElapsed < IMS_HEALTH_TIMEOUT_MS) {
                    return
                }
                val cm = c.getSystemService(ConnectivityManager::class.java)
                if (cm != null) {
                    Log.w(
                        "PHH",
                        "Refreshing IMS network request after unhealthy IMS state: ${buildHealthSummary()}"
                    )
                    refreshImsNetworkRequest(cm, "watchdog unhealthy", force = true)
                    imsRecoveryStep = RecoveryStep.NETWORK_REFRESHED
                    imsRecoveryStepElapsed = now
                }
            }

            RecoveryStep.NETWORK_REFRESHED -> {
                if (now - imsRecoveryStepElapsed < IMS_STACK_RESTART_TIMEOUT_MS || mtkRestartPending) {
                    return
                }

                imsRecoveryStep = RecoveryStep.MTK_IMS_RESTARTED
                imsRecoveryStepElapsed = now
                lastMtkRestartElapsed = now

                if (isMtkImsRecoveryAvailable(c)) {
                    restartMtkImsPackage(c, "watchdog unhealthy")
                } else {
                    Log.w(
                        "PHH",
                        "IMS still unhealthy after network refresh, but no MTK IMS recovery path is available: ${buildHealthSummary()}"
                    )
                }
            }

            RecoveryStep.MTK_IMS_RESTARTED -> {
                if (mtkRestartPending || now - lastMtkRestartElapsed < IMS_STACK_RESTART_COOLDOWN_MS) {
                    return
                }
                Log.w("PHH", "IMS is still unhealthy after MTK IMS recovery cooldown, retrying")
                imsDegradedSinceElapsed = now
                imsRecoveryStep = RecoveryStep.NONE
                imsRecoveryStepElapsed = 0L
            }
        }
    }

    private fun isMtkImsRecoveryAvailable(c: Context): Boolean {
        // The stock package is deliberately disabled while TrebleApp IMS is active.
        if (ImsSettings.enabled()) {
            return false
        }
        if (!(gotMtkP || gotMtkQ || gotMtkR || gotMtkS || gotMtkAidl)) {
            return false
        }
        return try {
            c.packageManager.getPackageInfo(MTK_IMS_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun recoverAfterImsRequestTimeout(c: Context): Boolean {
        if (!isMtkImsRecoveryAvailable(c)) {
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (mtkRestartPending || now - lastMtkRestartElapsed < IMS_STACK_RESTART_COOLDOWN_MS) {
            return false
        }

        lastMtkRestartElapsed = now
        Log.w(
            "PHH",
            "Attempting MTK IMS recovery after IMS request timeout #$imsRequestTimeoutCount"
        )
        restartMtkImsPackage(c, "request timeout #$imsRequestTimeoutCount")
        return true
    }

    private fun restartMtkImsPackage(c: Context, reason: String) {
        val activityManager = c.getSystemService(ActivityManager::class.java)
        if (activityManager == null) {
            Log.e("PHH", "Cannot restart MTK IMS package, ActivityManager is unavailable")
            return
        }

        unregisterImsCallbacks()
        imsRegistrationState = RegistrationState.UNKNOWN
        imsVoiceCapable = false
        imsSmsCapable = false
        lastImsDisconnectReason = "forcing MTK IMS restart"

        try {
            val forceStop = ActivityManager::class.java.getMethod(
                "forceStopPackage",
                String::class.java,
            )
            forceStop.invoke(activityManager, MTK_IMS_PACKAGE)
            mtkRestartPending = true
            Log.w("PHH", "Force-stopped $MTK_IMS_PACKAGE for IMS recovery ($reason)")
        } catch (t: Throwable) {
            Log.e("PHH", "Failed to force-stop $MTK_IMS_PACKAGE for IMS recovery ($reason)", t)
            return
        }

        mainHandler.postDelayed({
            val context = ctxt.get() ?: run {
                mtkRestartPending = false
                return@postDelayed
            }
            mtkRestartPending = false
            resetImsRequestTimeouts("post MTK IMS restart")
            registerImsCallbacksIfNeeded(context, force = true)
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            if (sp.getBoolean(ImsSettings.requestNetwork, false)) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                if (cm != null) {
                    refreshImsNetworkRequest(cm, "post MTK IMS restart", force = true)
                }
            }
            evaluateImsHealth(context, "post MTK IMS restart")
        }, IMS_STACK_REEVALUATE_DELAY_MS)
    }

    private fun disableVoWifi(ctxt: Context) {
        Props.safeSetprop("persist.dbg.wfc_avail_ovr", "0")
        val subMgr = ctxt.getSystemService(SubscriptionManager::class.java) ?: return
        val subIds = try {
            subMgr.activeSubscriptionIdList ?: IntArray(0)
        } catch (t: Throwable) {
            Log.e("PHH", "Failed to fetch active subscriptions for VoWiFi disable", t)
            return
        }
        subIds.forEach { subId ->
            try {
                ImsMmTelManager.createForSubscriptionId(subId).setVoWiFiSettingEnabled(false)
                Log.d("PHH", "Disabled VoWiFi for subId $subId")
            } catch (t: Throwable) {
                Log.e("PHH", "Failed to disable VoWiFi for subId $subId", t)
            }
        }
    }

    private fun hasSystemMtkIms(context: Context): Boolean {
        val appInfo = try {
            context.packageManager.getApplicationInfo(MTK_IMS_PACKAGE, 0)
        } catch (t: Throwable) {
            return false
        }
        val sourceDir = appInfo.sourceDir ?: return false
        return !sourceDir.startsWith("/data/")
    }

    private fun useMtkImsStack(): Boolean {
        return SystemProperties.getBoolean(MTK_IMS_STACK_PROPERTY, false)
    }
}
