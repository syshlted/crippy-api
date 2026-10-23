package com.systemhalted.crippy.client

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.systemhalted.crippy.api.DescriptorResult
import com.systemhalted.crippy.api.GrantResult
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.api.SessionResult
import com.systemhalted.crippy.api.ThumbnailTicket
import com.systemhalted.crippy.api.TotalsResult
import com.systemhalted.crippy.client.internal.ServiceBinding
import com.systemhalted.crippy.client.internal.SessionListener
import com.systemhalted.crippy.client.internal.sha256Hex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.io.File

/**
 * The paths that are rare and still have to answer properly (L12): a binding that goes wrong, a
 * certificate Android gives in an unusual shape, and a request that is cancelled, hollow or late.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrippyEdgesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val fake = FakeSession()

    private fun TestScope.session(io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        CrippySession.open(fake, CrippyGrant(setOf(Right.READ), document()), SessionListener(), io)

    // ---- Binding ------------------------------------------------------------------------------

    @Test
    fun `a service that answers from another package is not crippy's`() {
        installCrippy(context, CRIPPY)
        shadowOf(context).setComponentNameAndServiceForBindServiceForIntent(
            Intent(ICrippyService.ACTION_BIND).setPackage(ICrippyService.PACKAGE),
            ComponentName("com.example.lookalike", "Service"),
            FakeService(),
        )
        assertEquals(
            ConnectResult.Unavailable("crippy's service did not answer"),
            connect(CrippyTrust.alsoTrusting(digest(CRIPPY))),
        )
    }

    @Test
    fun `a binding that dies, answers nothing or disconnects lets go of the service`() {
        val crippy = ComponentName(ICrippyService.PACKAGE, "Service")
        val died = ServiceBinding(context)
        died.onServiceConnected(crippy, FakeService())
        assertTrue(died.service != null)
        died.onServiceDisconnected(crippy)
        assertNull(died.service)
        died.onBindingDied(crippy)
        assertNull(died.service)
        ServiceBinding(context).onNullBinding(crippy)
        // Unbinding something never bound is not an error.
        ServiceBinding(context).apply {
            unbind()
            unbind()
        }
    }

    @Test
    fun `the public connect uses its own dispatcher and default trust`() {
        installCrippy(context, CRIPPY)
        assertEquals(ConnectResult.NotTrusted(sha256Hex(CRIPPY.toByteArray())), onWorker { Crippy.connect(context) })
    }

    @Test
    fun `an answer with no data allows nothing`() {
        assertNull(Crippy.authorizationResult(Activity.RESULT_OK, null))
    }

    @Test
    fun `only hex digits name a certificate`() {
        listOf("g", "/", "`").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { CrippyTrust.alsoTrusting(bad.repeat(64)) }
        }
        assertTrue(CrippyTrust.alsoTrusting("0a".repeat(32)).certificates.isNotEmpty())
    }

    @Test
    fun `a session with no grant is a failure`() {
        installCrippy(context, CRIPPY)
        val service = FakeService()
        serveCrippy(service)
        val connection =
            (
                connect(
                    CrippyTrust.alsoTrusting(sha256Hex(CRIPPY.toByteArray())),
                ) as ConnectResult.Connected
            ).connection
        service.opening = {
            SessionResult().apply {
                status = ICrippyService.STATUS_OK
                session = FakeSession()
            }
        }
        assertEquals(Failure.FAILED, (onWorker { connection.openSession() } as SessionOutcome.Failed).failure)
        connection.close()
    }

    // ---- Certificates in unusual shapes --------------------------------------------------------

    @Test
    fun `a crippy Android gives no signing info for is not trusted, and has no digest`() {
        installPackage(PackageInfo().apply { packageName = ICrippyService.PACKAGE })
        assertEquals(
            Availability.NotTrusted(""),
            Crippy.availability(context, CrippyTrust.alsoTrusting(digest(CRIPPY))),
        )
    }

    @Test
    fun `a crippy with several signers is named by every one of them`() {
        installPackage(
            PackageInfo().apply {
                packageName = ICrippyService.PACKAGE
                signingInfo =
                    SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(CRIPPY, OTHER)) }
            },
        )
        assertEquals(Availability.Ready, Crippy.availability(context, CrippyTrust.alsoTrusting(digest(OTHER))))
    }

    // ---- Requests that are hollow, cancelled or late -------------------------------------------

    @Test
    fun `a binder dead before the session could watch it is a session already over`() =
        runTest {
            fake.deadOnArrival = true
            assertFalse(session().isOpen)
        }

    @Test
    fun `a grant or a measurement crippy refused is the failure it names`() =
        runTest {
            fake.grant = { GrantResult().apply { status = ICrippyService.STATUS_OK } }
            fake.totals = { TotalsResult().apply { status = ICrippyService.STATUS_LOCKED } }
            val session = session()
            assertEquals(Failure.FAILED, (session.refreshGrant() as CrippyResult.Failed).failure)
            assertEquals(Failure.LOCKED, (session.measure("f") as CrippyResult.Failed).failure)
        }

    @Test
    fun `defaults ask with no flags, and a time left out is kept`() =
        runTest {
            val flags = mutableListOf<Int>()
            fake.changed = { _, flag ->
                flags += flag
                ok(entry("n"))
            }
            val session = session()
            session.createFolder(ROOT, "b")
            session.move("n", ROOT)
            session.rename("n", "c", NameConflict.PICK_FREE_NAME)
            session.createFile(ROOT, "d", "text/plain", NameConflict.PICK_FREE_NAME)
            session.setTimes("n", createdMillis = 7)
            assertEquals(listOf(0, 0, ICrippyService.FLAG_FREE_NAME, ICrippyService.FLAG_FREE_NAME, 0), flags)
            assertEquals("setTimes n 7 -1", fake.asked.last())
        }

    @Test
    fun `a write whose caller is cancelled is abandoned and stays cancelled`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, _ ->
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            val session = session(StandardTestDispatcher(testScheduler))
            val writing = async { session.write("w") { awaitCancellation() } }
            runCurrent()
            writing.cancel()
            runCurrent()
            assertTrue(writing.isCancelled)
            target.delete()
        }

    @Test
    fun `a writer that fails with no message, or not on the pipe, is named by its type`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, _ ->
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            val session = session()
            assertEquals(
                CrippyResult.Failed(Failure.CANCELLED, "IllegalStateException"),
                session.write("w") { throw IllegalStateException() },
            )
            assertEquals(
                CrippyResult.Failed(Failure.CANCELLED, "nope"),
                session.write("w") { throw UnsupportedOperationException("nope") },
            )
            target.delete()
        }

    @Test
    fun `a preview delivered twice keeps the first and closes the second`() =
        runTest {
            val image = File.createTempFile("preview", ".png").apply { writeBytes(byteArrayOf(1)) }
            val second = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(1, delivered(ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)))
                told?.onThumbnail(1, delivered(second))
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 1
                }
            }
            assertTrue(session().thumbnail("x", 8, 8) is CrippyResult.Ok)
            assertFalse("the second descriptor was left open", second.fileDescriptor.valid())
            image.delete()
        }

    @Test
    fun `a preview that succeeds with nothing, or fails with a descriptor, is a failure and leaks nothing`() =
        runTest {
            val image = File.createTempFile("preview", ".png").apply { writeBytes(byteArrayOf(1)) }
            val spare = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
            var answer: DescriptorResult = DescriptorResult().apply { status = ICrippyService.STATUS_OK }
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(2, answer)
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 2
                }
            }
            val session = session()
            assertEquals(Failure.FAILED, (session.thumbnail("x", 8, 8) as CrippyResult.Failed).failure)
            answer =
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_UNSUPPORTED
                    descriptor = spare
                }
            assertEquals(Failure.UNSUPPORTED, (session.thumbnail("x", 8, 8) as CrippyResult.Failed).failure)
            assertFalse("a refused preview's descriptor was left open", spare.fileDescriptor.valid())
            // And a request crippy did not answer at all: an older crippy (L2).
            fake.thumbnail = { _, _, _, _ -> null }
            assertEquals(Failure.FAILED, (session.thumbnail("x", 8, 8) as CrippyResult.Failed).failure)
            image.delete()
        }

    @Test
    fun `a request after the session ended is not waited on`() =
        runTest {
            fake.thumbnail = { _, _, _, _ ->
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 3
                }
            }
            val session = session()
            session.close()
            assertEquals(Failure.ENDED, (session.thumbnail("x", 8, 8) as CrippyResult.Failed).failure)
        }

    private fun delivered(descriptor: ParcelFileDescriptor) =
        DescriptorResult().apply {
            status = ICrippyService.STATUS_OK
            this.descriptor = descriptor
            length = 1
        }

    private fun installPackage(info: PackageInfo) = shadowOf(context.packageManager).installPackage(info)

    private fun connect(trust: CrippyTrust = CrippyTrust.DEFAULT): ConnectResult =
        onWorker { Crippy.connect(context, trust, Dispatchers.IO) }

    private fun <T> onWorker(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.IO).async { block() }
        while (!running.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        return runBlocking { running.await() }
    }

    private fun document() =
        CrippyDocument(ROOT, null, "crippy vault", ICrippyService.MIME_TYPE_FOLDER, true, 0, 0, 0, 0, false)

    private companion object {
        val CRIPPY = Signature(ByteArray(64) { 5 })
        val OTHER = Signature(ByteArray(64) { 6 })
        const val POLL_MILLIS = 5L

        fun digest(signature: Signature) = sha256Hex(signature.toByteArray())
    }
}
