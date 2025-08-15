package me.phh.treble.app

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import kotlin.concurrent.thread

class EntryService : Service() {
    companion object {
        var service: EntryService? = null
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        service = this
        thread {
            try {
                Ims.startup(this)
            } catch (t: Throwable) {
                Log.e("PHH", "Caught", t)
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
