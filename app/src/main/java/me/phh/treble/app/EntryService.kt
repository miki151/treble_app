package me.phh.treble.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import kotlin.concurrent.thread

class EntryService : Service() {
    companion object {
        var service: EntryService? = null
    }

    @Volatile
    private var initStarted = false

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInitIfNeeded()
        return START_STICKY
    }

    private fun startInitIfNeeded() {
        if (initStarted) return
        initStarted = true
        thread {
            try {
                Ims.startup(this)
                val userManager = getSystemService(UserManager::class.java)
                if (userManager?.isUserUnlocked != false) {
                    ImsSettings.setupIms(this, userInitiated = false)
                } else {
                    Log.d("PHH", "User locked; skipping IMS auto-setup")
                }
            } catch (t: Throwable) {
                Log.e("PHH", "Caught", t)
                initStarted = false
            }
        }
    }
}

class Starter : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val caller = UserHandle.getCallingUserId()
        if (caller != 0) {
            Log.d("PHH", "Service called from user none 0, ignore")
            return
        }
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                context.startServiceAsUser(Intent(context, EntryService::class.java), UserHandle.SYSTEM)
            }
        }
    }
}
