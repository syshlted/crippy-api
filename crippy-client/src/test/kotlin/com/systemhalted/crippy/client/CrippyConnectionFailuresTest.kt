package com.systemhalted.crippy.client

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Looper
import android.os.RemoteException
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.api.SessionResult
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

/**
 * Trust at API 28 and above, where crippy's certificates come from its signing history, and every way a
 * connection or a session does not happen (L12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrippyConnectionFailuresTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a pinned certificate is read from crippy's signing history`() {
        installCrippy(context, CRIPPY)
        assertEquals(Availability.Ready, Crippy.availability(context, CrippyTrust.alsoTrusting(digest(CRIPPY))))
        assertEquals(Availability.NotTrusted(digest(CRIPPY)), Crippy.availability(context))
    }

    @Test
    fun `a certificate crippy rotated away from is still crippy's`() {
        installCrippy(context, CRIPPY, rotatedFrom = OLD_CRIPPY)
        assertEquals(Availability.Ready, Crippy.availability(context, CrippyTrust.alsoTrusting(digest(OLD_CRIPPY))))
    }

    @Test
    fun `a crippy signed by this app's own key is trusted with nothing pinned`() {
        installCrippy(context, CRIPPY)
        installSelf(CRIPPY)
        assertEquals(Availability.Ready, Crippy.availability(context))
        val doubting = CrippyTrust.alsoTrusting(digest(OLD_CRIPPY), sameSigner = false)
        assertEquals(Availability.NotTrusted(digest(CRIPPY)), Crippy.availability(context, doubting))
    }

    @Test
    fun `a service that cannot be bound is unavailable`() {
        installCrippy(context, CRIPPY)
        shadowOf(context).declareActionUnbindable(ICrippyService.ACTION_BIND)
        assertTrue(connect() is ConnectResult.Unavailable)
    }

    @Test
    fun `a bind refused by the system is unavailable, not a crash`() {
        installCrippy(context, CRIPPY)
        shadowOf(context).setThrowInBindService(SecurityException("not allowed"))
        assertEquals(ConnectResult.Unavailable("not allowed"), connect())
    }

    @Test
    fun `a crippy older than the client needs is too old`() {
        installCrippy(context, CRIPPY)
        serveCrippy(FakeService(version = -1))
        assertEquals(ConnectResult.TooOld(-1), connect())
    }

    @Test
    fun `every way a session does not open is a failure`() {
        installCrippy(context, CRIPPY)
        val service = FakeService()
        serveCrippy(service)
        val connection = (connect() as ConnectResult.Connected).connection
        assertTrue(connection.isConnected)
        service.opening = { throw RemoteException("gone") }
        assertEquals(Failure.DISCONNECTED, (open(connection) as SessionOutcome.Failed).failure)
        service.opening = { null }
        assertEquals(Failure.FAILED, (open(connection) as SessionOutcome.Failed).failure)
        service.opening = { SessionResult().apply { status = ICrippyService.STATUS_OK } }
        assertEquals(Failure.FAILED, (open(connection) as SessionOutcome.Failed).failure)
        service.opening = { refused(ICrippyService.STATUS_FAILED) }
        assertEquals(Failure.FAILED, (open(connection) as SessionOutcome.Failed).failure)
        connection.close()
        assertFalse(connection.isConnected)
        assertEquals(Failure.DISCONNECTED, (open(connection) as SessionOutcome.Failed).failure)
        connection.close()
    }

    @Test
    fun `the unlock is asked of crippy's own screen`() {
        val unlock: Intent = Crippy.unlockIntent()
        assertEquals(ICrippyService.ACTION_UNLOCK, unlock.action)
        assertEquals(ICrippyService.PACKAGE, unlock.`package`)
    }

    /** Signs this test's own package with [signature], so `checkSignatures` can compare the two. */
    private fun installSelf(signature: Signature) {
        val info =
            PackageInfo().apply {
                packageName = context.packageName
                @Suppress("DEPRECATION")
                signatures = arrayOf(signature)
                signingInfo =
                    SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(signature)) }
            }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun connect(): ConnectResult =
        onWorker { Crippy.connect(context, CrippyTrust.alsoTrusting(digest(CRIPPY)), Dispatchers.IO) }

    private fun open(connection: CrippyConnection): SessionOutcome = onWorker { connection.openSession() }

    /** `CrippyConnectionTest`'s: a bind's answer arrives on the main looper, which only this thread runs. */
    private fun <T> onWorker(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.IO).async { block() }
        while (!running.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        return runBlocking { running.await() }
    }

    private companion object {
        val CRIPPY = Signature(ByteArray(64) { 3 })
        val OLD_CRIPPY = Signature(ByteArray(64) { 4 })
        const val POLL_MILLIS = 5L

        fun digest(signature: Signature) = sha256Hex(signature.toByteArray())
    }
}
