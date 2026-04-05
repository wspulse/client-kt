package com.wspulse.client.component

import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import com.wspulse.client.HeartbeatConfig
import com.wspulse.client.TransportFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach
import kotlin.time.Duration.Companion.seconds

/**
 * Shared base class for component tests.
 *
 * Provides common helpers ([clientConfig], [waitUntil], [waitForPing]) and
 * automatic [tearDown] via `@AfterEach`. Subclasses pass their own
 * [TestCoroutineScheduler] so that [waitUntil] advances virtual time
 * instead of polling with real delays.
 */
abstract class ComponentTestBase(
    protected val testScheduler: TestCoroutineScheduler,
) {
    protected var testClient: Client? = null

    @AfterEach
    fun tearDown() {
        runBlocking(UnconfinedTestDispatcher(testScheduler)) {
            testClient?.let {
                it.close()
                it.done.await()
            }
            testClient = null
        }
    }

    /** Create a [ClientConfig] with long heartbeat to prevent timeout during tests. */
    protected fun clientConfig(init: ClientConfig.() -> Unit = {}): ClientConfig =
        ClientConfig().apply {
            heartbeat = HeartbeatConfig(pingPeriod = 50.seconds, pongWait = 60.seconds)
            init()
        }

    /**
     * Poll [condition] up to 500 times, advancing virtual time by 10 ms each
     * iteration (5 s total). No real delays — purely scheduler-driven.
     */
    protected fun waitUntil(condition: () -> Boolean) {
        repeat(500) {
            if (condition()) return
            testScheduler.advanceTimeBy(10)
        }
        error("waitUntil: condition not satisfied after 5s virtual time")
    }

    /** Wait until the transport has sent at least one Ping frame. */
    internal fun waitForPing(transport: MockTransport) {
        waitUntil { transport.sent.any { it is TransportFrame.Ping } }
    }
}
