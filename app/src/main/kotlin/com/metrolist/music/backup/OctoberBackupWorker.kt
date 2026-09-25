/**
 * October Music Project (C) 2026
 * Licensed under GPL-3.0
 */

package com.metrolist.music.backup

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
import androidx.work.workDataOf
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.GoogleIdentityAttachedKey
import com.metrolist.music.constants.LastBackupHashKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

class OctoberBackupWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.dataStore.data.first()
        val googleAttached = prefs[GoogleIdentityAttachedKey] ?: false
        val email = prefs[AccountEmailKey].orEmpty()

        if (!googleAttached || email.isBlank()) {
            Timber.tag(TAG).d("Google identity not attached, skipping backup")
            return@withContext Result.success()
        }

        try {
            val database = MusicDatabase(InternalDatabase.newInternalDatabaseInstance(applicationContext))
            val zipBytes = OctoberBackupManager.createBackupZip(applicationContext, database)
            val hash = OctoberBackupManager.sha256(zipBytes.toString(Charsets.ISO_8859_1))
            val lastHash = prefs[LastBackupHashKey]
            val isForce = inputData.getBoolean(KEY_FORCE, false)

            if (isForce || hash != lastHash) {
                Timber.tag(TAG).i("Backup content changed (newHash=%s, lastHash=%s, force=%s), uploading to Drive", hash, lastHash, isForce)
                OctoberBackupManager.saveLocalBackup(applicationContext, zipBytes)

                val token = GoogleDriveClient.getDriveTokenBackground(applicationContext, email)
                if (token != null) {
                    val uploadResult = GoogleDriveClient.uploadBackupToAppDataFolder(applicationContext, token, email, zipBytes)
                    uploadResult.fold(
                        onSuccess = { fileId ->
                            Timber.tag(TAG).i("Drive appDataFolder upload succeeded (fileId=%s)", fileId)
                            val now = System.currentTimeMillis()
                            applicationContext.safeDataStoreEdit { settings ->
                                settings[LastBackupHashKey] = hash
                                settings[com.metrolist.music.constants.LastBackupTimestampKey] = now
                            }
                        },
                        onFailure = { err ->
                            Timber.tag(TAG).e(err, "Drive appDataFolder upload failed")
                            if (runAttemptCount < 3) return@withContext Result.retry()
                        }
                    )
                } else {
                    Timber.tag(TAG).w("No background drive token available for %s", email)
                }
            } else {
                Timber.tag(TAG).d("Backup content unchanged (hash=%s), skipping 48-hr Drive upload", hash)
            }

            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "OctoberBackupWorker execution failed")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "OctoberBackupWorker"
        const val WORK_NAME_IMMEDIATE = "october_immediate_backup"
        const val WORK_NAME_PERIODIC = "october_periodic_backup"
        const val KEY_FORCE = "force"

        /**
         * Enqueues an immediate one-time backup with ExistingWorkPolicy.REPLACE.
         */
        fun enqueueImmediateBackup(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<OctoberBackupWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_FORCE to true))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Timber.tag(TAG).i("Enqueued immediate backup with ExistingWorkPolicy.REPLACE")
        }

        /**
         * Schedules recurring 48-hour periodic backup checking with ExistingPeriodicWorkPolicy.UPDATE.
         */
        fun schedulePeriodicBackup(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<OctoberBackupWorker>(48, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Timber.tag(TAG).i("Scheduled 48-hour periodic backup check")
        }
    }
}
