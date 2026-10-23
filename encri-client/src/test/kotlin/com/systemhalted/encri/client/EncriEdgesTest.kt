package com.systemhalted.encri.client

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.systemhalted.encri.api.DescriptorResult
import com.systemhalted.encri.api.GrantResult
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.api.SessionResult
import com.systemhalted.encri.api.ThumbnailTicket
import com.systemhalted.encri.api.TotalsResult
import com.systemhalted.encri.client.internal.ServiceBinding
import com.systemhalted.encri.client.internal.SessionListener
import com.systemhalted.encri.client.internal.sha256Hex
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
class EncriEdgesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val fake = FakeSession()

    private fun TestScope.session(io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        EncriSession.open(fake, EncriGrant(setOf(Right.READ), document()), SessionListener(), io)

    // ---- Binding ------------------------------------------------------------------------------

    @Test
    fun `a service that answers from another package is not encri's`() {
        installEncri(context, ENCRI)
        shadowOf(context).setComponentNameAndServiceForBindServiceForIntent(
            Intent(IEncriService.ACTION_BIND).setPackage(IEncriService.PACKAGE),
            ComponentName("com.example.lookalike", "Service"),
            FakeService(),
        )
        assertEquals(
            ConnectResult.Unavailable("encri's service did not answer"),
            connect(EncriTrust.alsoTrusting(digest(ENCRI))),
        )
    }

    @Test
    fun `a binding that dies, answers nothing or disconnects lets go of the service`() {
        val encri = ComponentName(IEncriService.PACKAGE, "Service")
        val died = ServiceBinding(context)
        died.onServiceConnected(encri, FakeService())
        assertTrue(died.service != null)
        died.onServiceDisconnected(encri)
        assertNull(died.service)
        died.onBindingDied(encri)
        assertNull(died.service)
        ServiceBinding(context).onNullBinding(encri)
        // Robolectric's unbind disconnects with no name, which used to throw on whichever thread ran it.
        died.onServiceDisconnected(null)
        died.onBindingDied(null)
        ServiceBinding(context).onNullBinding(null)
        // Unbinding something never bound is not an error.
        ServiceBinding(context).apply {
            unbind()
            unbind()
        }
    }

    @Test
    fun `the public connect uses its own dispatcher and default trust`() {
        installEncri(context, ENCRI)
        assertEquals(ConnectResult.NotTrusted(sha256Hex(ENCRI.toByteArray())), onWorker { Encri.connect(context) })
    }

    @Test
    fun `an answer with no data allows nothing`() {
        assertNull(Encri.authorizationResult(Activity.RESULT_OK, null))
    }

    @Test
    fun `only hex digits name a certificate`() {
        listOf("g", "/", "`").forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) { EncriTrust.alsoTrusting(bad.repeat(64)) }
        }
        assertTrue(EncriTrust.alsoTrusting("0a".repeat(32)).certificates.isNotEmpty())
    }

    @Test
    fun `a session with no grant is a failure`() {
        installEncri(context, ENCRI)
        val service = FakeService()
        serveEncri(service)
        val connection =
            (
                connect(
                    EncriTrust.alsoTrusting(sha256Hex(ENCRI.toByteArray())),
                ) as ConnectResult.Connected
            ).connection
        service.opening = {
            SessionResult().apply {
                status = IEncriService.STATUS_OK
                session = FakeSession()
            }
        }
        assertEquals(Failure.FAILED, (onWorker { connection.openSession() } as SessionOutcome.Failed).failure)
        connection.close()
    }

    // ---- Certificates in unusual shapes --------------------------------------------------------

    @Test
    fun `an encri Android gives no signing info for is not trusted, and has no digest`() {
        installPackage(PackageInfo().apply { packageName = IEncriService.PACKAGE })
        assertEquals(
            Availability.NotTrusted(""),
            Encri.availability(context, EncriTrust.alsoTrusting(digest(ENCRI))),
        )
    }

    @Test
    fun `an encri with several signers is named by every one of them`() {
        installPackage(
            PackageInfo().apply {
                packageName = IEncriService.PACKAGE
                signingInfo =
                    SigningInfo().also { Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(ENCRI, OTHER)) }
            },
        )
        assertEquals(Availability.Ready, Encri.availability(context, EncriTrust.alsoTrusting(digest(OTHER))))
    }

    // ---- Requests that are hollow, cancelled or late -------------------------------------------

    @Test
    fun `a binder dead before the session could watch it is a session already over`() =
        runTest {
            fake.deadOnArrival = true
            assertFalse(session().isOpen)
        }

    @Test
    fun `a grant or a measurement encri refused is the failure it names`() =
        runTest {
            fake.grant = { GrantResult().apply { status = IEncriService.STATUS_OK } }
            fake.totals = { TotalsResult().apply { status = IEncriService.STATUS_LOCKED } }
            val session = session()
            assertEquals(Failure.FAILED, (session.refreshGrant() as EncriResult.Failed).failure)
            assertEquals(Failure.LOCKED, (session.measure("f") as EncriResult.Failed).failure)
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
            assertEquals(listOf(0, 0, IEncriService.FLAG_FREE_NAME, IEncriService.FLAG_FREE_NAME, 0), flags)
            assertEquals("setTimes n 7 -1", fake.asked.last())
        }

    @Test
    fun `a write whose caller is cancelled is abandoned and stays cancelled`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, _ ->
                DescriptorResult().apply {
                    status = IEncriService.STATUS_OK
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
                    status = IEncriService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            val session = session()
            assertEquals(
                EncriResult.Failed(Failure.CANCELLED, "IllegalStateException"),
                session.write("w") { throw IllegalStateException() },
            )
            assertEquals(
                EncriResult.Failed(Failure.CANCELLED, "nope"),
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
                    status = IEncriService.STATUS_OK
                    requestId = 1
                }
            }
            assertTrue(session().thumbnail("x", 8, 8) is EncriResult.Ok)
            assertFalse("the second descriptor was left open", second.fileDescriptor.valid())
            image.delete()
        }

    @Test
    fun `a preview that succeeds with nothing, or fails with a descriptor, is a failure and leaks nothing`() =
        runTest {
            val image = File.createTempFile("preview", ".png").apply { writeBytes(byteArrayOf(1)) }
            val spare = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
            var answer: DescriptorResult = DescriptorResult().apply { status = IEncriService.STATUS_OK }
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(2, answer)
                ThumbnailTicket().apply {
                    status = IEncriService.STATUS_OK
                    requestId = 2
                }
            }
            val session = session()
            assertEquals(Failure.FAILED, (session.thumbnail("x", 8, 8) as EncriResult.Failed).failure)
            answer =
                DescriptorResult().apply {
                    status = IEncriService.STATUS_UNSUPPORTED
                    descriptor = spare
                }
            assertEquals(Failure.UNSUPPORTED, (session.thumbnail("x", 8, 8) as EncriResult.Failed).failure)
            assertFalse("a refused preview's descriptor was left open", spare.fileDescriptor.valid())
            // And a request encri did not answer at all: an older encri (L2).
            fake.thumbnail = { _, _, _, _ -> null }
            assertEquals(Failure.FAILED, (session.thumbnail("x", 8, 8) as EncriResult.Failed).failure)
            image.delete()
        }

    @Test
    fun `a request after the session ended is not waited on`() =
        runTest {
            fake.thumbnail = { _, _, _, _ ->
                ThumbnailTicket().apply {
                    status = IEncriService.STATUS_OK
                    requestId = 3
                }
            }
            val session = session()
            session.close()
            assertEquals(Failure.ENDED, (session.thumbnail("x", 8, 8) as EncriResult.Failed).failure)
        }

    private fun delivered(descriptor: ParcelFileDescriptor) =
        DescriptorResult().apply {
            status = IEncriService.STATUS_OK
            this.descriptor = descriptor
            length = 1
        }

    private fun installPackage(info: PackageInfo) = shadowOf(context.packageManager).installPackage(info)

    private fun connect(trust: EncriTrust = EncriTrust.DEFAULT): ConnectResult =
        onWorker { Encri.connect(context, trust, Dispatchers.IO) }

    private fun <T> onWorker(block: suspend () -> T): T {
        val running = CoroutineScope(Dispatchers.IO).async { block() }
        while (!running.isCompleted) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MILLIS)
        }
        return runBlocking { running.await() }
    }

    private fun document() =
        EncriDocument(ROOT, null, "encri vault", IEncriService.MIME_TYPE_FOLDER, true, 0, 0, 0, 0, false)

    private companion object {
        val ENCRI = Signature(ByteArray(64) { 5 })
        val OTHER = Signature(ByteArray(64) { 6 })
        const val POLL_MILLIS = 5L

        fun digest(signature: Signature) = sha256Hex(signature.toByteArray())
    }
}
