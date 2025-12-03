package me.phh.treble.app

import android.content.om.IOverlayManager
import android.os.ServiceManager
import android.util.Log

object OverlayUtil {
    private val om: IOverlayManager by lazy {
        IOverlayManager.Stub.asInterface(ServiceManager.getService("overlay"))
    }

    fun setOverlayEnabled(name: String, enabled: Boolean) {
        try {
            om.setEnabled(name, enabled, 0)
        } catch (e: Exception) {
            Log.d("PHH", "Failed to set overlay $name", e)
        }
    }
}
