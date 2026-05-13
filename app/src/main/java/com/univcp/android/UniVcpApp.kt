package com.univcp.android

import android.app.Application
import com.univcp.android.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class UniVcpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@UniVcpApp)
            modules(appModule)
        }
    }
}
