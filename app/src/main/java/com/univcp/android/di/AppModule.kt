package com.univcp.android.di

import androidx.room.Room
import androidx.room.RoomDatabase
import com.univcp.android.data.ApiKeyStore
import com.univcp.android.data.ChatRepository
import com.univcp.android.data.SettingsStore
import com.univcp.android.data.db.AppDatabase
import me.rerere.ai.provider.ProviderManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val appModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "univcp.db")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
    single { get<AppDatabase>().agentDao() }
    single { get<AppDatabase>().topicDao() }
    single { get<AppDatabase>().messageDao() }
    single { SettingsStore(androidContext()) }
    single { ApiKeyStore(androidContext()) }
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    single { ProviderManager(get(), androidContext()) }
    single { ChatRepository(get(), get(), get(), get(), get(), get()) }
}
