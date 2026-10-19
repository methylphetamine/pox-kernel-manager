package com.pox.kernelmanager

import android.app.Application
import com.pox.kernelmanager.data.repo.TuningRepository
import com.pox.kernelmanager.data.root.ShellUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PoxApplication : Application() {

    companion object {
        lateinit var instance: PoxApplication
            private set

        /**
         * Process-wide scope, cancelled in [onTerminate] so nothing outlives
         * the process it belongs to.
         *
         * Used for work that must survive the component that started it -
         * quick settings tile taps and the post-boot restore job in particular.
         */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        applicationScope.launch {
            // Touching ShellUtils installs the libsu builder; pre-warm the root
            // shell so the first dashboard read does not pay for the su prompt.
            ShellUtils.prewarm()

            // Kernel tuning nodes reset on reboot, so replay the saved choices
            // once per process. The tuning screen awaits this same call, which
            // is why it never renders kernel defaults that are about to be
            // replaced by the user's real settings.
            runCatching { TuningRepository.awaitSavedTuningRestored() }
        }
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }
}
