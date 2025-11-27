package com.davotoula.lce

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics

class LceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this)
        }

        Firebase.crashlytics.setCrashlyticsCollectionEnabled(true)
        Firebase.crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        Firebase.crashlytics.setCustomKey("version_name", BuildConfig.VERSION_NAME)

        Firebase.analytics.setAnalyticsCollectionEnabled(true)
        AnalyticsTracker.logAppOpen()
    }
}
