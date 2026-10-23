package com.systemhalted.crippy.client

import android.content.pm.Signature
import android.os.Looper
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.internal.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Finding crippy, trusting it and connecting (L12): nothing is bound unless the crippy installed is
 * signed by a key the app trusts, and a service that does not answer as crippy does is not connected to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class CrippyConnectionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `crippy not installed is not installed`() {
        assertEquals(Availability.NotInstalled, Crippy.availability(context))
        assertEquals(ConnectResult.NotInstalled, connect())
    }

    @Test
    fun `a crippy signed by a key nobody trusts is never bound`() {
        installCrippy(context, STRANGER)
        val service = FakeService()
        serveCrippy(service)
        assertEquals(ConnectResult.NotTrusted(sha256Hex(STRANGER.toByteArray())), connect())
        assertEquals("the lookalike was asked something", null, service.listener)
    }

    @Test
    fun `a pinned certificate is trusted, and the service is connected to`() {
        installCrippy(context, STRANGER)
        serveCrippy(FakeService())
        val connected = connect(CrippyTrust.alsoTrusting(sha256Hex(STRANGER.toByteArray()))) as ConnectResult.Connected
        assertEquals(ICrippyService.VERSION, connected.connection.contractVersion)
        connected.connection.close()
    }

    @Test
    fun `a service that answers version 0 is not crippy's`() {
        installCrippy(context, STRANGER)
        serveCrippy(FakeService(version = 0))
        assertTrue(connect(trustStranger()) is ConnectResult.Unavailable)
    }

    @Test
    fun `each answer to opening a session is its own outcome`() {
        installCrippy(context, STRANGER)
        val service = FakeService()
        serveCrippy(service)
        val connection = (connect(trustStranger()) as ConnectResult.Connected).connection
        service.opening = { refused(ICrippyService.STATUS_LOCKED) }
        assertEquals(SessionOutcome.Locked, open(connection))
        service.opening = { refused(ICrippyService.STATUS_NOT_AUTHORIZED) }
        assertEquals(SessionOutcome.NotAuthorized, open(connection))
        service.opening = { refused(ICrippyService.STATUS_ACCESS_OFF) }
        assertEquals(SessionOutcome.AccessOff, open(connection))
        service.opening = { opened(FakeSession()) }
        val session = (open(connection) as SessionOutcome.Opened).session
        assertEquals(setOf(Right.READ, Right.WRITE), session.grant.rights)
        assertEquals(ROOT, session.grant.scope.id)
        connection.close()
    }

    @Test
    fun `closing a connection ends the sessions opened through it`() {
        installCrippy(context, STRANGER)
        val service = FakeService()
        serveCrippy(service)
        val connection = (connect(trustStranger()) as ConnectResult.Connected).connection
        val remote = FakeSession()
        service.opening = { opened(remote) }
        val session = (open(connection) as SessionOutcome.Opened).session
        connection.close()
        assertFalse("the session outlived its connection", session.isOpen)
        assertTrue("crippy was not told the session ended", remote.ended)
    }

    private fun trustStranger() = CrippyTrust.alsoTrusting(sha256Hex(STRANGER.toByteArray()))

    private fun connect(trust: CrippyTrust = CrippyTrust.DEFAULT): ConnectResult =
        onWorker {
            Crippy.connect(context, trust, Dispatchers.IO)
        }

    private fun open(connection: CrippyConnection): SessionOutcome = onWorker { connection.openSession() }

    /**
     * Runs [block] off the main thread while this one delivers what Robolectric posts to it: a bind's
     * answer arrives on the main looper, which only the test's own thread can run.
     */
    private fun <T> onWorker(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.IO).async { block() }
        while (!running.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        return runBlocking { running.await() }
    }

    private companion object {
        val STRANGER = Signature(ByteArray(64) { 7 })
        const val POLL_MILLIS = 5L
    }
}
