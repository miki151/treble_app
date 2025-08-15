package me.phh.treble.app

import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.preference.PreferenceActivity

class SettingsActivity : PreferenceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applicationContext.startServiceAsUser(
            Intent(applicationContext, EntryService::class.java),
            UserHandle.SYSTEM
        )
        fragmentManager.beginTransaction()
            .replace(android.R.id.content, ImsSettingsFragment())
            .commit()
    }
}
