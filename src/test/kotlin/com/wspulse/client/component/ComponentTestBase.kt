package com.wspulse.client.component

import com.wspulse.client.Client
import com.wspulse.client.ClientConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.AfterEach

/**
 * Shared base class for component tests.
 *
 * Provides common helpers ([clientConfig], [waitUntil]) and
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

    /** Create a [ClientConfig] with default settings for tests. */
    protected fun clientConfig(init: ClientConfig.() -> Unit = {}): ClientConfig = ClientConfig().apply(init)

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
}
