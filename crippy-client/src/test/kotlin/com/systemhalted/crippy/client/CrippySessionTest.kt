package com.systemhalted.crippy.client

import android.os.ParcelFileDescriptor
import com.systemhalted.crippy.api.DescriptorResult
import com.systemhalted.crippy.api.EntryResult
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.api.IWriteListener
import com.systemhalted.crippy.api.PageResult
import com.systemhalted.crippy.api.ThumbnailTicket
import com.systemhalted.crippy.client.internal.SessionListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * What a session makes of crippy's answers (L11, L12): every one a [CrippyResult], whatever crippy said
 * or did not say. Against a fake session, in this process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 36])
class CrippySessionTest {
    private val fake = FakeSession()
    private val grant = CrippyGrant(setOf(Right.READ), document(ROOT, "crippy vault", folder = true))

    // The test's own scheduler, so a wait inside a request runs on virtual time.
    private fun TestScope.session(io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        CrippySession.open(fake, grant, SessionListener(), io)

    @Test
    fun `a document crippy describes arrives as one`() =
        runTest {
            fake.stat =
                { id ->
                    EntryResult().apply {
                        status = ICrippyService.STATUS_OK
                        entry = entry(requireNotNull(id))
                    }
                }
            val stat = session().stat("00000000000000000000000000000002")
            val document = (stat as CrippyResult.Ok).value
            assertEquals("notes.txt", document.name)
            assertEquals(12L, document.sizeBytes)
            assertEquals(null, document.childCount)
            assertEquals(2_000L, document.modifiedMillis)
        }

    @Test
    fun `a status crippy sends is the failure it names, with its message`() =
        runTest {
            fake.stat = {
                EntryResult().apply {
                    status = ICrippyService.STATUS_NOT_FOUND
                    message = "nothing"
                }
            }
            assertEquals(CrippyResult.Failed(Failure.NOT_FOUND, "nothing"), session().stat("x"))
        }

    @Test
    fun `no answer at all is a failure, not a success`() =
        runTest {
            // What an older crippy sends for a method it does not have (L2).
            assertEquals(Failure.FAILED, (session().stat("x") as CrippyResult.Failed).failure)
        }

    @Test
    fun `an ended session says so, and asks crippy nothing more`() =
        runTest {
            var asked = 0
            fake.stat = {
                asked++
                EntryResult().apply { status = ICrippyService.STATUS_ENDED }
            }
            val session = session()
            assertEquals(Failure.ENDED, (session.stat("x") as CrippyResult.Failed).failure)
            assertFalse(session.isOpen)
            assertEquals(Failure.ENDED, (session.stat("x") as CrippyResult.Failed).failure)
            assertEquals(1, asked)
        }

    @Test
    fun `a folder is listed a page at a time until there is no next`() =
        runTest {
            fake.pages = { _, after, _ ->
                PageResult().apply {
                    status = ICrippyService.STATUS_OK
                    entries = arrayOf(entry(if (after == null) "a" else "b", name = after ?: "first"))
                    next = if (after == null) "first" else null
                }
            }
            val listed = (session().list(ROOT) as CrippyResult.Ok).value
            assertEquals(listOf("first", "first"), listed.map { it.name })
            assertEquals(listOf("a", "b"), listed.map { it.id })
        }

    @Test
    fun `a preview arrives as its bytes`() =
        runTest {
            val image = File.createTempFile("preview", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            fake.thumbnail = { _, _, _, listener ->
                listener?.onThumbnail(
                    7,
                    DescriptorResult().apply {
                        status = ICrippyService.STATUS_OK
                        descriptor = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)
                        length = 3
                    },
                )
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 7
                }
            }
            assertArrayEquals(byteArrayOf(1, 2, 3), (session().thumbnail("x", 96, 96) as CrippyResult.Ok).value)
            image.delete()
        }

    @Test
    fun `a preview crippy cannot make is the failure crippy names`() =
        runTest {
            fake.thumbnail = { _, _, _, _ -> ThumbnailTicket().apply { status = ICrippyService.STATUS_UNSUPPORTED } }
            assertEquals(Failure.UNSUPPORTED, (session().thumbnail("x", 96, 96) as CrippyResult.Failed).failure)
        }

    @Test
    fun `a preview cancelled by its caller is cancelled in crippy too`() =
        runTest {
            fake.thumbnail = { _, _, _, _ ->
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 9
                }
            }
            val session = session(StandardTestDispatcher(testScheduler))
            val waiting = async { session.thumbnail("x", 96, 96) }
            runCurrent()
            waiting.cancel()
            runCurrent()
            assertEquals(listOf(9), fake.cancelled)
        }

    @Test
    fun `closing the session under a wait answers it as disconnected`() =
        runTest {
            fake.thumbnail = { _, _, _, _ ->
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 3
                }
            }
            val session = session(StandardTestDispatcher(testScheduler))
            val waiting = async { session.thumbnail("x", 96, 96) }
            runCurrent()
            session.close()
            assertEquals(Failure.DISCONNECTED, (waiting.await() as CrippyResult.Failed).failure)
            assertTrue("crippy was not told the session ended", fake.ended)
        }

    @Test
    fun `what the app writes reaches crippy, and crippy's answer is the result`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, listener ->
                listener?.onWriteFinished(
                    EntryResult().apply {
                        status = ICrippyService.STATUS_OK
                        entry = entry("w")
                    },
                )
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor =
                        ParcelFileDescriptor.open(
                            target,
                            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE,
                        )
                }
            }
            val written = session().write("w") { it.write(CONTENT) }
            assertEquals("w", (written as CrippyResult.Ok).value.id)
            assertArrayEquals(CONTENT, target.readBytes())
            target.delete()
        }

    @Test
    fun `a writer that throws saves nothing and says the write was cancelled`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, _ ->
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            val written = session().write("w") { throw IOException("the source went away") }
            assertEquals(CrippyResult.Failed(Failure.CANCELLED, "the source went away"), written)
            target.delete()
        }

    @Test
    fun `a write crippy refused part way is told crippy's reason`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            var told: IWriteListener? = null
            fake.write = { _, listener ->
                told = listener
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            val written =
                session().write("w") {
                    // What crippy does when the disk fills: it says why, and breaks the pipe.
                    told?.onWriteFinished(
                        EntryResult().apply {
                            status = ICrippyService.STATUS_NO_SPACE
                            message =
                                "full"
                        },
                    )
                    throw IOException("EPIPE (Broken pipe)")
                }
            assertEquals(CrippyResult.Failed(Failure.NO_SPACE, "full"), written)
            target.delete()
        }

    private fun document(
        id: String,
        name: String,
        folder: Boolean,
    ) = CrippyDocument(id, null, name, ICrippyService.MIME_TYPE_FOLDER, folder, 0, 0, 0, 0, false)

    private companion object {
        val CONTENT = ByteArray(200_000) { (it % 251).toByte() }
    }
}
