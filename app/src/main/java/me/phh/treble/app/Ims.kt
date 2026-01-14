package me.phh.treble.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

    lateinit var ctxt: WeakReference<Context>
    private var prefsListenerRegistered = false
    private val imsNetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
        .build()

    val networkListener = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("PHH", "Network $network is available!")
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            Log.i("PHH", "Received info about network $network, got $networkCapabilities")
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
            requestImsNetwork(cm)
        }
    }

    var registeredNetwork = false
    val spListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        val c = ctxt.get() ?: return@OnSharedPreferenceChangeListener
        val cm = c.getSystemService(ConnectivityManager::class.java)

        when(key) {
            ImsSettings.requestNetwork -> {
                val value = sp.getBoolean(key, false)
                if(value) {
                    if (!registeredNetwork) {
                        requestImsNetwork(cm)
                        registeredNetwork = true
                    }
                } else {
                    if(registeredNetwork) {
                        cm.unregisterNetworkCallback(networkListener)
                        registeredNetwork = false
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

    private fun requestImsNetwork(cm: ConnectivityManager) {
        cm.requestNetwork(imsNetworkRequest, networkListener, IMS_NETWORK_TIMEOUT_MS)
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
