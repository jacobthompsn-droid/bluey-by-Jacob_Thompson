package com.jacob.bluey

import android.content.Context
import com.jacob.bluey.link.PairingRecord

/**
 * The laptop's pairing code, in this app's private storage (other apps can't read it, and
 * android:allowBackup="false" keeps it out of cloud backups). Treat it like a password.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun load(): PairingRecord? = PairingRecord.parse(prefs.getString(KEY, null))

    fun save(record: PairingRecord) {
        prefs.edit().putString(KEY, record.qrText).apply()
    }

    private companion object {
        const val KEY = "record"
    }
}
