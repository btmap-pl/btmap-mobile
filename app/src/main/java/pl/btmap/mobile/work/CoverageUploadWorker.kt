package pl.btmap.mobile.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.btmap.mobile.BtmapApp
import java.util.concurrent.TimeUnit

/** Sends queued coverage measurements to BTMap, every half hour on Wi-Fi, never on low battery. */
class CoverageUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BtmapApp
        val settings = app.settings.settings.value
        if (settings.coverageConsent != true) return Result.success()
        app.core.coverage.flush()
        val sent = app.core.coverage.upload(settings.apiBaseUrl)
        app.core.coverageUploaded(sent)
        // Something still queued means the server was unreachable: try again with backoff.
        return if (app.core.coverage.pending > 0 && sent == 0) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "coverage-upload"
        private const val NOW = "coverage-upload-now"

        /** Sends the queue now (still Wi-Fi only), e.g. on start or when the coverage map opens. */
        fun runNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<CoverageUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, androidx.work.ExistingWorkPolicy.KEEP, request)
        }

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<CoverageUploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
