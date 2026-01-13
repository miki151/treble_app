package me.phh.treble.app

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import kotlin.concurrent.thread

object ImsSettings : Settings {
    val requestNetwork = "key_ims_request_network"
    val forceEnableSettings = "key_ims_force_enable_setting"
    val setupIms = "key_ims_setup"
    val parentalControls = "key_parental_controls"
    private const val imsInstallAction = "me.phh.treble.app.ImsInstalled"
    private const val imsApnName = "PHH IMS"
    private const val imsApkName = "ims_mtk_u_resigned.apk"
    private const val extraUserInitiated = "extra_ims_user_initiated"
    private val imsPackageCandidates = listOf("com.mediatek.ims", Ims.IMS_PACKAGE)

    @Volatile
    private var imsInstallInProgress = false

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

    fun setupIms(context: Context, userInitiated: Boolean) {
        val appContext = context.applicationContext
        if (imsInstallInProgress) {
            Log.d("PHH", "IMS install already in progress, skipping")
            if (userInitiated) {
                Toast.makeText(appContext, "IMS setup already running", Toast.LENGTH_LONG).show()
            }
            return
        }

        val tm = appContext.getSystemService(TelephonyManager::class.java)
        val operator = tm?.simOperator
        if (operator.isNullOrEmpty()) {
            Log.d("PHH", "No current carrier, skipping IMS APN setup")
            if (userInitiated) {
                Toast.makeText(appContext, "No current carrier, skipping IMS APN", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            Log.d("PHH", "Adding \"ims\" APN")
            val mcc = operator.substring(0, 3)
            val mnc = operator.substring(3, operator.length)
            Log.d("PHH", "Got mcc = $mcc and mnc = $mnc")

            val cr = appContext.contentResolver
            val cursor = cr.query(
                Uri.parse("content://telephony/carriers/current"),
                arrayOf("name", "type", "apn", "carrier_enabled", "edited"),
                "name = ?",
                arrayOf(imsApnName),
                null
            )
            val apnExists = cursor?.use { it.moveToFirst() } ?: false
            if (apnExists) {
                Log.d("PHH", "$imsApnName APN for this provider is already here")
            } else {
                Log.d("PHH", "No APN called $imsApnName, adding our own")

                val cv = ContentValues()
                cv.put("name", imsApnName)
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
        }

        Log.d("PHH", "MTK P radio = ${Ims.gotMtkP}")
        Log.d("PHH", "MTK Q radio = ${Ims.gotMtkQ}")
        Log.d("PHH", "MTK R radio = ${Ims.gotMtkR}")
        Log.d("PHH", "MTK S radio = ${Ims.gotMtkS}")
        Log.d("PHH", "MTK AIDL radio = ${Ims.gotMtkAidl}")
        Log.d("PHH", "Qualcomm HIDL radio = ${Ims.gotQcomHidl}")
        Log.d("PHH", "Qualcomm AIDL radio = ${Ims.gotQcomAidl}")

        Props.safeSetprop("persist.vendor.vilte_support", "0")

        if (isImsPackageInstalled(appContext)) {
            finalizeImsSetup(appContext, userInitiated)
            return
        }

        val apkDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (apkDir == null) {
            Log.e("PHH", "Failed to access external downloads directory for IMS APK")
            if (userInitiated) {
                Toast.makeText(appContext, "Cannot access storage to stage IMS APK", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (!apkDir.exists() && !apkDir.mkdirs()) {
            Log.e("PHH", "Unable to create external downloads directory at ${apkDir.absolutePath}")
            if (userInitiated) {
                Toast.makeText(appContext, "Cannot prepare storage for IMS APK", Toast.LENGTH_LONG).show()
            }
            return
        }

        imsInstallInProgress = true

        val apkFile = File(apkDir, imsApkName)
        appContext.resources.openRawResource(R.raw.ims_mtk_u_resigned).use { input ->
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
            imsInstallInProgress = false
            if (userInitiated) {
                Toast.makeText(appContext, "Bundled IMS APK is corrupted", Toast.LENGTH_LONG).show()
            }
            return
        }

        val apkLength = apkFile.length()
        Log.d("PHH", "Prepared bundled IMS APK at ${apkFile.absolutePath} length=$apkLength")

        val pi = appContext.packageManager.packageInstaller
        val sessionId = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        val session = pi.openSession(sessionId)

        session.openWrite(imsApkName, 0, apkLength).use { output ->
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

        session.commit(
            PendingIntent.getBroadcast(
                appContext,
                1,
                Intent(appContext, ImsInstallReceiver::class.java)
                    .setAction(imsInstallAction)
                    .putExtra(extraUserInitiated, userInitiated),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ).intentSender
        )
        session.close()
    }

    private fun isImsPackageInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return imsPackageCandidates.any { pkg ->
            try {
                pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    private fun finalizeImsSetup(context: Context, userInitiated: Boolean) {
        imsInstallInProgress = false
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.edit()
            .putBoolean(ImsSettings.requestNetwork, true)
            .putBoolean(ImsSettings.forceEnableSettings, true)
            .apply()

        Ims.startup(context)

        if (userInitiated) {
            Toast.makeText(context, "IMS setup applied", Toast.LENGTH_LONG).show()
        }
    }

    private fun scheduleFinalizeIfInstalled(context: Context, userInitiated: Boolean) {
        thread {
            repeat(10) { attempt ->
                if (isImsPackageInstalled(context)) {
                    Log.i("PHH", "IMS package detected after install failure, applying settings")
                    finalizeImsSetup(context, userInitiated)
                    return@thread
                }
                Log.d("PHH", "IMS package not yet visible after install failure, retry=${attempt + 1}")
                Thread.sleep(2000)
            }
            imsInstallInProgress = false
            if (userInitiated) {
                Toast.makeText(context, "IMS install failed, package not present", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleInstallResult(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val userInitiated = intent?.getBooleanExtra(extraUserInitiated, false) ?: false
        val status = intent?.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        ) ?: PackageInstaller.STATUS_FAILURE
        val legacyStatus = intent?.getIntExtra(
            PackageInstaller.EXTRA_LEGACY_STATUS,
            PackageManager.INSTALL_FAILED_INTERNAL_ERROR
        ) ?: PackageManager.INSTALL_FAILED_INTERNAL_ERROR
        val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_SUCCESS || legacyStatus == PackageManager.INSTALL_SUCCEEDED) {
            finalizeImsSetup(appContext, userInitiated)
            return
        }

        if (isImsPackageInstalled(appContext)) {
            Log.w(
                "PHH",
                "IMS install status missing/failure (status=$status legacy=$legacyStatus msg=$message); package present, applying settings"
            )
            finalizeImsSetup(appContext, userInitiated)
            return
        }

        Log.e("PHH", "IMS apk install failed: status=$status legacy=$legacyStatus msg=$message")
        scheduleFinalizeIfInstalled(appContext, userInitiated)
    }


    override fun enabled() = true
}

class ImsInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ImsSettings.handleInstallResult(context, intent)
    }
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
            val ctx = activity ?: return@setOnPreferenceClickListener true
            ImsSettings.setupIms(ctx, userInitiated = true)
            return@setOnPreferenceClickListener true
        }
    }
}
