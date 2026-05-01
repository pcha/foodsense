package com.github.pcha.foodsense.app.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.pcha.foodsense.app.FoodSense
import com.github.pcha.foodsense.app.R
import com.github.pcha.foodsense.app.data.local.database.ItemDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

@HiltWorker
class ExpirationNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val itemDao: ItemDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val productNames = itemDao.getProductNamesExpiringOn(tomorrow)
        if (productNames.isNotEmpty()) {
            sendNotification(productNames)
        }
        return Result.success()
    }

    private fun sendNotification(productNames: List<String>) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (!notificationManager.areNotificationsEnabled()) return

        val title = if (productNames.size == 1) {
            "1 product expires tomorrow"
        } else {
            "${productNames.size} products expire tomorrow"
        }
        val body = productNames.joinToString(", ")

        val notification = NotificationCompat.Builder(applicationContext, FoodSense.EXPIRATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
