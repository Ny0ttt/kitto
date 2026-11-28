package com.ny0t.kitto

import android.app.Application
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Sets up App Check once per process, before anything calls the backend (M6 B6).
 * Firebase itself starts on its own from google-services.json.
 */
class KittoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            if (BuildConfig.APP_CHECK_DEBUG) {
                // Sideloaded builds: on first use this logs "Enter this debug secret into the allow list in
                // the Firebase Console…" (logcat tag DebugAppCheckProvider). Register that token per phone.
                DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            },
        )
    }
}
