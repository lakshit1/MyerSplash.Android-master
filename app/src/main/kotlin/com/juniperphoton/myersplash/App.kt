package com.juniperphoton.myersplash

import android.app.Application
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.imagepipeline.backends.okhttp3.OkHttpImagePipelineConfigFactory
import com.juniperphoton.myersplash.api.OkHttpClientAPI
import com.juniperphoton.myersplash.utils.Pasteur
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Pasteur.init(BuildConfig.DEBUG)

        val config = OkHttpImagePipelineConfigFactory
                .newBuilder(this, OkHttpClientAPI.createClient()).build()
        Fresco.initialize(this, config)
        AppCenter.start(this, BuildConfig.APP_CENTER_KEY, Analytics::class.java, Crashes::class.java)
    }
}