package com.github.pcha.foodsense.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.github.pcha.foodsense.app.notification.ExpirationNotificationWorker
import com.github.pcha.foodsense.app.R
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FoodSense : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        scheduleExpirationCheck()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            EXPIRATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun scheduleExpirationCheck() {
        val request = PeriodicWorkRequestBuilder<ExpirationNotificationWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "expiration_check",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val EXPIRATION_CHANNEL_ID = "expiration_alerts"
    }
}
