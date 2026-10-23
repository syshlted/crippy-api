package com.systemhalted.encri.client

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Looper
import android.os.RemoteException
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.api.SessionResult
import com.systemhalted.encri.client.internal.sha256Hex
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
 * Trust at API 28 and above, where encri's certificates come from its signing history, and every way a
 * connection or a session does not happen (L12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncriConnectionFailuresTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a pinned certificate is read from encri's signing history`() {
        installEncri(context, ENCRI)
        assertEquals(Availability.Ready, Encri.availability(context, EncriTrust.alsoTrusting(digest(ENCRI))))
        assertEquals(Availability.NotTrusted(digest(ENCRI)), Encri.availability(context))
    }

    @Test
    fun `a certificate encri rotated away from is still encri's`() {
        installEncri(context, ENCRI, rotatedFrom = OLD_ENCRI)
        assertEquals(Availability.Ready, Encri.availability(context, EncriTrust.alsoTrusting(digest(OLD_ENCRI))))
    }

    @Test
    fun `an encri signed by this app's own key is trusted with nothing pinned`() {
        installEncri(context, ENCRI)
        installSelf(ENCRI)
        assertEquals(Availability.Ready, Encri.availability(context))
        val doubting = EncriTrust.alsoTrusting(digest(OLD_ENCRI), sameSigner = false)
        assertEquals(Availability.NotTrusted(digest(ENCRI)), Encri.availability(context, doubting))
    }

    @Test
    fun `a service that cannot be bound is unavailable`() {
        installEncri(context, ENCRI)
        shadowOf(context).declareActionUnbindable(IEncriService.ACTION_BIND)
        assertTrue(connect() is ConnectResult.Unavailable)
    }

    @Test
    fun `a bind refused by the system is unavailable, not a crash`() {
        installEncri(context, ENCRI)
        shadowOf(context).setThrowInBindService(SecurityException("not allowed"))
        assertEquals(ConnectResult.Unavailable("not allowed"), connect())
    }

    @Test
    fun `an encri older than the client needs is too old`() {
        installEncri(context, ENCRI)
        serveEncri(FakeService(version = -1))
        assertEquals(ConnectResult.TooOld(-1), connect())
    }

    @Test
    fun `every way a session does not open is a failure`() {
        installEncri(context, ENCRI)
        val service = FakeService()
        serveEncri(service)
        val connection = (connect() as ConnectResult.Connected).connection
        assertTrue(connection.isConnected)
        service.opening = { throw RemoteException("gone") }
        assertEquals(Failure.DISCONNECTED, (open(connection) as SessionOutcome.Failed).failure)
        service.opening = { null }
        assertEquals(Failure.FAILED, (open(connection) as SessionOutcome.Failed).failure)
        service.opening = { SessionResult().apply { status = IEncriService.STATUS_OK } }
        assertEquals(Failure.FAILED, (open(connection) as SessionOutcome.Failed).failure)
        service.opening = { refused(IEncriService.STATUS_FAILED) }
        assertEquals(Failure.FAILED, (open(connection) as SessionOutcome.Failed).failure)
        connection.close()
        assertFalse(connection.isConnected)
        assertEquals(Failure.DISCONNECTED, (open(connection) as SessionOutcome.Failed).failure)
        connection.close()
    }

    @Test
    fun `the unlock is asked of encri's own screen`() {
        val unlock: Intent = Encri.unlockIntent()
        assertEquals(IEncriService.ACTION_UNLOCK, unlock.action)
        assertEquals(IEncriService.PACKAGE, unlock.`package`)
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
        onWorker { Encri.connect(context, EncriTrust.alsoTrusting(digest(ENCRI)), Dispatchers.IO) }

    private fun open(connection: EncriConnection): SessionOutcome = onWorker { connection.openSession() }

    /** `EncriConnectionTest`'s: a bind's answer arrives on the main looper, which only this thread runs. */
    private fun <T> onWorker(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.IO).async { block() }
        while (!running.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        return runBlocking { running.await() }
    }

    private companion object {
        val ENCRI = Signature(ByteArray(64) { 3 })
        val OLD_ENCRI = Signature(ByteArray(64) { 4 })
        const val POLL_MILLIS = 5L

        fun digest(signature: Signature) = sha256Hex(signature.toByteArray())
    }
}
