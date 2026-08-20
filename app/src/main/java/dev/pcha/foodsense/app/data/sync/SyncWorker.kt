package dev.pcha.foodsense.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pcha.foodsense.app.data.ProductRepository

/**
 * Periodic reconciliation: pulls the remote state and merges it into Room to heal any drift
 * (e.g. serverId assignments missed while offline) and give pending writes a chance to flush
 * when connectivity returns. Runs under a CONNECTED constraint; retries with backoff on failure.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val productRepository: ProductRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (productRepository.sync()) Result.success() else Result.retry()
}
