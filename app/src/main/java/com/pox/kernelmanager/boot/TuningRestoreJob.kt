package com.pox.kernelmanager.boot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.pox.kernelmanager.PoxApplication
import com.pox.kernelmanager.data.repo.TuningApplyResult
import com.pox.kernelmanager.data.repo.TuningRepository
import kotlinx.coroutines.launch

/**
 * Schedules a restore of the saved tuning once the device has booted.
 *
 * The kernel tuning nodes reset on every reboot, so without this the app looks
 * like it forgot every setting. A receiver alone cannot do the work: right
 * after boot the root daemon may not be ready yet, which is why the actual
 * restore runs as a JobScheduler job with retries.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TuningRepository.hasSavedState()) return
        TuningRestoreJob.schedule(context)
    }
}

/** Applies the persisted tuning snapshot to the kernel after a reboot. */
class TuningRestoreJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // Route through the same once-per-process restore the app uses: if the
        // process (re)started for this job already replayed the snapshot, this
        // returns the cached result instead of writing everything twice.
        PoxApplication.applicationScope.launch {
            val restored = runCatching { TuningRepository.awaitSavedTuningRestored() }
                .getOrElse { TuningApplyResult(false, it.message ?: "Restore failed") }

            val nothingToRestore = restored == null || !TuningRepository.hasSavedState()
            val applied = restored?.isHardwareApplied == true
            val exhausted = TuningRepository.restoreAttempts() >= MAX_ATTEMPTS

            when {
                applied || nothingToRestore -> {
                    TuningRepository.clearRestoreAttempts()
                    jobFinished(params, false)
                }
                exhausted -> {
                    // Give up quietly; the next app launch retries anyway.
                    TuningRepository.clearRestoreAttempts()
                    jobFinished(params, false)
                }
                else -> {
                    TuningRepository.noteRestoreAttempt()
                    jobFinished(params, true)
                }
            }
        }
        return true
    }

    /** Keep the job across transient failures; losing it would lose the retry. */
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 0x504F58
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_DELAY_MS = 15_000L

        /** Schedules a restore attempt, by default 15 seconds after boot. */
        fun schedule(context: Context, delayMs: Long = INITIAL_DELAY_MS) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TuningRestoreJob::class.java))
                .setMinimumLatency(delayMs)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            runCatching { scheduler.schedule(job) }
        }
    }
}
