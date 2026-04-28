package com.github.pcha.foodsense.app.notification

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.pcha.foodsense.app.FoodSense
import com.github.pcha.foodsense.app.R
import com.github.pcha.foodsense.app.data.local.database.Product
import com.github.pcha.foodsense.app.data.local.database.ProductDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate

@HiltWorker
class ExpirationNotificationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val productDao: ProductDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val products = productDao.getProductsExpiringOn(tomorrow)
        if (products.isNotEmpty()) {
            sendNotification(products)
        }
        return Result.success()
    }

    private fun sendNotification(products: List<Product>) {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (!notificationManager.areNotificationsEnabled()) return

        val title = if (products.size == 1) {
            "1 product expires tomorrow"
        } else {
            "${products.size} products expire tomorrow"
        }
        val body = products.joinToString(", ") { it.name }

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
