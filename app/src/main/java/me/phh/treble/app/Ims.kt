package me.phh.treble.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.SystemClock
import android.os.ServiceManager
import android.os.SystemProperties
import android.preference.PreferenceManager
import android.telephony.SubscriptionManager
import android.telephony.ims.ImsMmTelManager
import android.util.Log
import java.lang.ref.WeakReference

@SuppressLint("StaticFieldLeak")
object Ims {
    const val IMS_PACKAGE = "me.phh.ims"
    private const val IMS_NETWORK_TIMEOUT_MS = 120_000
    private const val IMS_REFRESH_COOLDOWN_MS = 30_000L

    lateinit var ctxt: WeakReference<Context>
    private var prefsListenerRegistered = false
    private var lastRefreshElapsed = 0L
    private var wasSuspended = false
    private var requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID

    val networkListener = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("PHH", "Network $network is available!")
        }
        override fun onLost(network: Network) {
            Log.w("PHH", "IMS network $network lost")
            val c = ctxt.get() ?: return
            val sp = PreferenceManager.getDefaultSharedPreferences(c)
            if (!sp.getBoolean(ImsSettings.requestNetwork, false)) {
                registeredNetwork = false
                wasSuspended = false
                requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                return
            }
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
            refreshImsNetworkRequest(cm, "network lost", force = true)
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.i("PHH", "Received info about network $network, got $networkCapabilities")
            val suspended = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            if (wasSuspended && !suspended) {
                val c = ctxt.get() ?: return
                val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
                refreshImsNetworkRequest(cm, "suspension cleared")
            }
            wasSuspended = suspended
        }
        override fun onUnavailable() {
            Log.w("PHH", "IMS network request timed out, retrying")
            val c = ctxt.get() ?: return
            val sp = PreferenceManager.getDefaultSharedPreferences(c)
            if (!sp.getBoolean(ImsSettings.requestNetwork, false)) {
                registeredNetwork = false
                return
            }
            val cm = c.getSystemService(ConnectivityManager::class.java) ?: return
            refreshImsNetworkRequest(cm, "timeout", force = true)
        }
    }

    var registeredNetwork = false
    val spListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        val c = ctxt.get() ?: return@OnSharedPreferenceChangeListener
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@OnSharedPreferenceChangeListener

        when(key) {
            ImsSettings.requestNetwork -> {
                val value = sp.getBoolean(key, false)
                if(value) {
                    if (!registeredNetwork) {
                        requestImsNetwork(cm)
                        registeredNetwork = true
                    } else {
                        val subId = getTargetSubId(c)
                        if (subId != requestedSubId) {
                            refreshImsNetworkRequest(cm, "target subId changed", force = true)
                        }
                    }
                } else {
                    if(registeredNetwork) {
                        unregisterImsNetworkRequest(cm)
                        registeredNetwork = false
                        wasSuspended = false
                        requestedSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    }
                }
            }
            ImsSettings.forceEnableSettings -> {
                val value = if(sp.getBoolean(key, false)) "1" else "0"
                Props.safeSetprop("persist.dbg.volte_avail_ovr", value)
//                Props.safeSetprop("persist.dbg.wfc_avail_ovr", value)
                Props.safeSetprop("persist.dbg.allow_ims_off", value)
            }
        }
    }

    val mHidlService = android.hidl.manager.V1_0.IServiceManager.getService()

    val mAllSlots = listOf("imsrild1", "imsrild2", "imsrild3", "slot1", "slot2", "slot3", "imsSlot1", "imsSlot2", "mtkSlot1", "mtkSlot2", "imsradio0", "imsradio1")
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
        val allOverlays = listOf(
            "me.phh.treble.overlay.mtkims_telephony",
            "me.phh.treble.overlay.cafims_telephony",
            "me.phh.treble.overlay.hwims_telephony"
        )
        val selectOverlay = when {
            gotFloss -> "me.phh.treble.overlay.flossims_telephony"
            gotMtkP || gotMtkQ || gotMtkR || gotMtkS || gotMtkAidl -> "me.phh.treble.overlay.mtkims_telephony"
            gotQcomHidl || gotQcomAidl -> "me.phh.treble.overlay.cafims_telephony"
            gotSLSI -> "me.phh.treble.overlay.slsiims_telephony"
            gotSPRD -> "me.phh.treble.overlay.sprdims_telephony"
            gotHW -> "me.phh.treble.overlay.hwims_telephony"
            else -> null
        }
        if (gotFloss) {
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

        val subMgr = c.getSystemService(SubscriptionManager::class.java) ?: return SubscriptionManager.INVALID_SUBSCRIPTION_ID
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

    private fun unregisterImsNetworkRequest(cm: ConnectivityManager) {
        try {
            cm.unregisterNetworkCallback(networkListener)
        } catch (t: Throwable) {
            Log.d("PHH", "IMS network callback was not registered", t)
        }
    }

    private fun refreshImsNetworkRequest(cm: ConnectivityManager, reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRefreshElapsed < IMS_REFRESH_COOLDOWN_MS) {
            Log.d("PHH", "Skipping IMS request refresh ($reason), cooldown active")
            return
        }
        unregisterImsNetworkRequest(cm)
        requestImsNetwork(cm)
        registeredNetwork = true
        Log.i("PHH", "Refreshed IMS network request ($reason)")
    }

    private fun requestImsNetwork(cm: ConnectivityManager) {
        val c = ctxt.get() ?: return
        val imsNetworkRequest = buildImsNetworkRequest(c)
        cm.requestNetwork(imsNetworkRequest, networkListener, IMS_NETWORK_TIMEOUT_MS)
        lastRefreshElapsed = SystemClock.elapsedRealtime()
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
}
