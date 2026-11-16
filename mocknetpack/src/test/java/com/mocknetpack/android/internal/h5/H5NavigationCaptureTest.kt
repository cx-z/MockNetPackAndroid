package com.mocknetpack.android.internal.h5

import com.mocknetpack.android.TrafficEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H5NavigationCaptureTest {
    private val url = "https://example.test/missing"
    private var window: H5RuleController.Window? = H5RuleController.Window(1, 0)
    private var elapsed = 10L
    private val scheduled = mutableListOf<Runnable>()
    private val entries = mutableListOf<TrafficEntry>()
    private val capture = H5NavigationCapture(
        schedule = { task, _ -> scheduled.add(task) },
        cancel = { task -> scheduled.remove(task) },
        activeWindow = { window },
        uptime = { elapsed },
        submit = { entry, _ -> entries.add(entry) },
    )

    @Test
    fun recordsHttpErrorBeforeStartAndCoalescesLaterCallbacks() {
        record404()
        capture.started(url)
        capture.finished(url)
        capture.pageFinished(entries.single(), checkNotNull(window))

        val entry = entries.single()
        assertEquals(404, entry.statusCode)
        assertEquals("GET", entry.method)
        assertEquals("httpResponse", entry.terminalStage)
        assertEquals(listOf("text/html"), entry.responseHeaders?.get("Content-Type"))
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun httpErrorCancelsPendingCallbackFallbackAndKeepsMeasuredDuration() {
        capture.started(url)
        capture.finished(url)
        elapsed = 42
        record404()

        assertTrue(scheduled.isEmpty())
        assertEquals(32, entries.single().durationMs)
    }

    @Test
    fun aPreviousCompletedLoadDoesNotHideAnEarlyErrorForTheNextLoad() {
        capture.started(url)
        capture.failed(url, code = -6)
        capture.finished(url)
        record404()
        capture.started(url)
        capture.finished(url)

        assertEquals(listOf(null, 404), entries.map { it.statusCode })
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun doesNotMoveAnObservedNavigationIntoAnotherCaptureWindow() {
        capture.started(url)
        window = H5RuleController.Window(2, 0)
        record404()
        capture.finished(url)
        scheduled.toList().forEach(Runnable::run)
        assertTrue(entries.isEmpty())

        window = null
        capture.started(url)
        window = H5RuleController.Window(3, 0)
        record404()
        assertTrue(entries.isEmpty())
    }

    private fun record404() {
        capture.httpError(url, "GET", 404, "Not Found", emptyMap(), mapOf("Content-Type" to "text/html"))
    }
}
