package me.phh.treble.app

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import dalvik.system.PathClassLoader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ImsSettings : Settings {
    val requestNetwork = "key_ims_request_network"
    val forceEnableSettings = "key_ims_force_enable_setting"
    val setupIms = "key_ims_setup"
    val parentalControls = "key_parental_controls"

    fun checkHasPhhSignature(): Boolean {
        try {
            val cl = PathClassLoader(
                "/system/framework/services.jar",
                ClassLoader.getSystemClassLoader()
            )
            val pmUtils = cl.loadClass("com.android.server.pm.PackageManagerServiceUtils")
            val field = pmUtils.getDeclaredField("PHH_SIGNATURE")
            Log.d("PHH", "checkHasPhhSignature Field $field")
            return true
        } catch(t: Throwable) {
            Log.d("PHH", "checkHasPhhSignature Field failed")
            return false
        }
    }


    override fun enabled() = true
}

class ImsSettingsFragment : SettingsFragment() {
    override val preferencesResId = R.xml.pref_ims
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        val parental = findPreference<Preference>(ImsSettings.parentalControls)
	parental!!.setOnPreferenceClickListener {
	    val intent = Intent(Intent.ACTION_MAIN).apply {
        	component = ComponentName(
		    "eu.dumbdroid.deviceowner",
                    "eu.dumbdroid.deviceowner.ui.MainActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
	    val ctx = preferenceManager.context
            ctx.startActivity(intent)
            return@setOnPreferenceClickListener true
	}
        val setup = findPreference<Preference>(ImsSettings.setupIms)
        setup!!.setOnPreferenceClickListener {
            Log.d("PHH", "Adding \"ims\" APN")

            val tm = activity.getSystemService(TelephonyManager::class.java)
            val operator = tm.simOperator
            if (tm.simOperator == null || tm.simOperator == "") {
                Log.d("PHH", "No current carrier bailing out")
                return@setOnPreferenceClickListener true
            }

            val mcc = operator.substring(0, 3)
            val mnc = operator.substring(3, operator.length)
            Log.d("PHH", "Got mcc = $mcc and mnc = $mnc")

            val cr = activity.contentResolver

            val cursor = cr.query(
                Uri.parse("content://telephony/carriers/current"),
                arrayOf("name", "type", "apn", "carrier_enabled", "edited"),
                "name = ?", arrayOf("PHH IMS"), null
            )

            if (cursor != null && cursor.moveToFirst()) {
                Log.d("PHH", "PHH IMS APN for this provider is already here with data $cursor")
            } else {
                Log.d("PHH", "No APN called PHH IMS, adding our own")

                val cv = ContentValues()
                cv.put("name", "PHH IMS")
                cv.put("apn", "ims")
                cv.put("type", "ims")
                cv.put("edited", "1")
                cv.put("user_editable", "1")
                cv.put("user_visible", "1")
                cv.put("protocol", "IPV4V6")
                cv.put("roaming_protocol", "IPV6")
                cv.put("modem_cognitive", "1")
                cv.put("numeric", operator)
                cv.put("mcc", mcc)
                cv.put("mnc", mnc)

                val res = cr.insert(Uri.parse("content://telephony/carriers"), cv)
                Log.d("PHH", "Insert APN returned $res")
            }

            Log.d("PHH", "MTK P radio = ${Ims.gotMtkP}")
            Log.d("PHH", "MTK Q radio = ${Ims.gotMtkQ}")
            Log.d("PHH", "MTK R radio = ${Ims.gotMtkR}")
            Log.d("PHH", "MTK S radio = ${Ims.gotMtkS}")
            Log.d("PHH", "MTK AIDL radio = ${Ims.gotMtkAidl}")
            Log.d("PHH", "Qualcomm HIDL radio = ${Ims.gotQcomHidl}")
            Log.d("PHH", "Qualcomm AIDL radio = ${Ims.gotQcomAidl}")

            val pi = activity.packageManager.packageInstaller
            val sessionId = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
            val session = pi.openSession(sessionId)

            Props.safeSetprop("persist.vendor.vilte_support", "0")

            val apkDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (apkDir == null) {
                Log.e("PHH", "Failed to access external downloads directory for IMS APK")
                Toast.makeText(activity, "Cannot access storage to stage IMS APK", Toast.LENGTH_LONG).show()
                return@setOnPreferenceClickListener true
            }

            if (!apkDir.exists() && !apkDir.mkdirs()) {
                Log.e("PHH", "Unable to create external downloads directory at ${apkDir.absolutePath}")
                Toast.makeText(activity, "Cannot prepare storage for IMS APK", Toast.LENGTH_LONG).show()
                return@setOnPreferenceClickListener true
            }

            val apkFile = File(apkDir, "ims_mtk_u_resigned.apk")
            activity.resources.openRawResource(R.raw.ims_mtk_u_resigned).use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            val zipOk = try {
                ZipFile(apkFile).use { zip ->
                    zip.entries().hasMoreElements()
                }
            } catch (e: Exception) {
                Log.e("PHH", "Bundled IMS APK appears corrupted", e)
                false
            }

            if (!zipOk) {
                apkFile.delete()
                Toast.makeText(activity, "Bundled IMS APK is corrupted", Toast.LENGTH_LONG).show()
                return@setOnPreferenceClickListener true
            }

            val apkLength = apkFile.length()
            Log.d("PHH", "Prepared bundled IMS APK at ${apkFile.absolutePath} length=$apkLength")

            session.openWrite("ims_mtk_u_resigned.apk", 0, apkLength).use { output ->
                FileInputStream(apkFile).use { input ->
                    val buffer = ByteArray(512 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                    session.fsync(output)
                }
            }

            apkFile.delete()

            activity.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(p0: Context?, intent: Intent?) {
                        Log.e("PHH", "Apk install received $intent")
                        Toast.makeText(p0, "IMS apk installed! You may now reboot.", Toast.LENGTH_LONG).show()

                        val sp = PreferenceManager.getDefaultSharedPreferences(activity)
                        sp.edit()
                            .putBoolean(ImsSettings.requestNetwork, true)
                            .putBoolean(ImsSettings.forceEnableSettings, true)
                            .apply()
                    }
                },
                IntentFilter("me.phh.treble.app.ImsInstalled")
            )

            session.commit(
                PendingIntent.getBroadcast(
                    this@ImsSettingsFragment.activity,
                    1,
                    Intent("me.phh.treble.app.ImsInstalled"),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                ).intentSender
            )
            session.close()

            return@setOnPreferenceClickListener true
        }
    }
}
