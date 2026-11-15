package pl.btmap.mobile.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pl.btmap.mobile.BtmapApp
import pl.btmap.mobile.settings.Settings
import java.util.concurrent.TimeUnit

/**
 * Downloads the selected station database. Scheduled periodically (only on Wi-Fi when the user
 * wants that, never on low battery or low storage) and on demand from settings.
 */
class DatabaseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BtmapApp
        val settings = app.settings.settings.value
        // The networks of the SIM cards in the phone are downloaded first.
        val simMncs = app.core.state.value.snapshot.sims
            .sortedByDescending { it.isDataSim }
            .mapNotNull { (it.registeredPlmn ?: it.simPlmn)?.mnc?.toIntOrNull() }
        val result = app.core.stations.download(settings.database, settings.apiBaseUrl, settings.btsearchMncs, simMncs)
        return if (result.isSuccess) Result.success() else Result.failure()
    }

    companion object {
        private const val PERIODIC = "station-db-periodic"
        private const val NOW = "station-db-now"

        private fun constraints(s: Settings) = Constraints.Builder()
            .setRequiredNetworkType(if (s.dbWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        /** (Re)plans automatic downloads after a settings change. */
        fun schedule(context: Context, s: Settings) {
            val wm = WorkManager.getInstance(context)
            if (s.dbRefreshDays <= 0) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<DatabaseWorker>(s.dbRefreshDays.toLong(), TimeUnit.DAYS)
                .setConstraints(constraints(s))
                .setInitialDelay(s.dbRefreshDays.toLong(), TimeUnit.DAYS)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Download now; "now" still waits for Wi-Fi if the user restricted downloads to it. */
        fun runNow(context: Context, s: Settings) {
            val request = OneTimeWorkRequestBuilder<DatabaseWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (s.dbWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            // KEEP: asking again while a download runs must not restart it from zero.
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NOW)
        }
    }
}
