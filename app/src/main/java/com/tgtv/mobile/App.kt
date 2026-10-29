package com.tgtv.mobile

import android.app.Application
import android.util.Log
import com.tgtv.mobile.td.Credentials
import com.tgtv.mobile.td.TdLib

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Some networks (and most emulators) black-hole IPv6 while the OS still
        // prefers it; Cloudflare-hosted APIs like OpenSubtitles then hang until
        // timeout. Prefer IPv4 for all java.net connections in this process.
        System.setProperty("java.net.preferIPv4Stack", "true")
        if (!Credentials.hasApi(this)) return
        // Never hard-crash on TDLib native init: the UI must still open so the
        // user can re-configure or re-authenticate.
        try {
            TdLib.start(this)
        } catch (t: Throwable) {
            Log.e(TAG, "TDLib init failed", t)
        }
    }

    private companion object {
        const val TAG = "TGTVMobileApp"
    }
}
