package com.davotoula.lce

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics

class LceApplication : Application(), ImageLoaderFactory {

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

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }
}
