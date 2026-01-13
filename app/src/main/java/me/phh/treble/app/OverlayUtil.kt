package me.phh.treble.app

import android.content.om.IOverlayManager
import android.content.om.OverlayInfo
import android.os.ServiceManager
import android.util.Log

object OverlayUtil {
    private val om: IOverlayManager by lazy {
        IOverlayManager.Stub.asInterface(ServiceManager.getService("overlay"))
    }

    private fun getOverlayInfo(name: String): OverlayInfo? {
        return try {
            om.getOverlayInfo(name, 0)
        } catch (e: Exception) {
            Log.d("PHH", "Failed to read overlay $name", e)
            null
        }
    }

    fun isOverlayEnabled(name: String): Boolean {
        return getOverlayInfo(name)?.isEnabled ?: false
    }

    fun setOverlayEnabled(name: String, enabled: Boolean): Boolean {
        val before = isOverlayEnabled(name)
        if (before == enabled) return false
        try {
            om.setEnabled(name, enabled, 0)
        } catch (e: Exception) {
            Log.d("PHH", "Failed to set overlay $name", e)
        }
        val after = isOverlayEnabled(name)
        return before != after
    }
}
