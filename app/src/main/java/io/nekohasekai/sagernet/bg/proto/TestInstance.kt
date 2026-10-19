package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.Continuation

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    /**
     * Runs the URL test. The time limit covers building the config, starting plugins and the
     * request; [timeout] alone only limits the request. Cancelling the caller closes the
     * temporary core at once, which aborts the request in flight.
     */
    suspend fun doTest(): Int {
        return withTimeoutOrNull(timeout + START_TIMEOUT) {
            suspendCancellableCoroutine { cancellable ->
                // the project's tryResume extensions, not the internal members of the same name
                val c: Continuation<Int> = cancellable
                processes = GuardedProcessPool {
                    Logs.w(it)
                    c.tryResumeWithException(it)
                }
                val job = runOnDefaultDispatcher {
                    use {
                        try {
                            init()
                            ensureActive()
                            launch()
                            if (processes.processCount > 0) {
                                // wait for plugin start
                                delay(500)
                            }
                            c.tryResume(Libcore.urlTest(box, link, timeout))
                        } catch (e: Exception) {
                            c.tryResumeWithException(e)
                        }
                    }
                }
                cancellable.invokeOnCancellation {
                    job.cancel()
                    // Only the core: the job still runs close() for plugins and files when the
                    // blocking urlTest returns. BoxInstance.Close is idempotent and locked.
                    // Not here: this runs on the canceller's thread, often the main thread, and
                    // Close waits for the running test.
                    runOnIoDispatcher {
                        runCatching {
                            if (isInitialized()) box.close()
                        }
                    }
                }
            }
        } ?: error(app.getString(R.string.connection_test_timeout))
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    companion object {
        /** Time allowed for building the config and starting the core and plugins. */
        private const val START_TIMEOUT = 5000L
    }

}
