package com.davotoula.lce

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase

class LceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }

        Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        Firebase.crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        Firebase.crashlytics.setCustomKey("version_name", BuildConfig.VERSION_NAME)

        Firebase.analytics
        AnalyticsTracker.logAppOpen()
    }
}
