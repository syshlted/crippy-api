package com.systemhalted.encri.client

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.systemhalted.encri.api.DescriptorResult
import com.systemhalted.encri.api.EntryResult
import com.systemhalted.encri.api.Grant
import com.systemhalted.encri.api.GrantResult
import com.systemhalted.encri.api.HandoffResult
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.api.IWriteListener
import com.systemhalted.encri.api.PageResult
import com.systemhalted.encri.api.SpaceResult
import com.systemhalted.encri.api.ThumbnailTicket
import com.systemhalted.encri.api.TotalsResult
import com.systemhalted.encri.client.internal.SessionListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Every request a session makes, against a fake session: what encri answers becomes the client's own
 * types, and what it does not answer — a missing field, an older encri, a process gone — is a failure
 * rather than an exception (L5, L12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncriSessionRequestsTest {
    private val fake = FakeSession()
    private val grant = EncriGrant(setOf(Right.READ), document(ROOT))
    private val listener = SessionListener()
    private var closedBy: EncriSession? = null

    private fun TestScope.session(io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        EncriSession.open(fake, grant, listener, io) { closedBy = it }

    @Test
    fun `the grant is read again from encri`() =
        runTest {
            fake.grant = {
                GrantResult().apply {
                    status = IEncriService.STATUS_OK
                    this.grant =
                        Grant().apply {
                            rights = IEncriService.RIGHT_READ or IEncriService.RIGHT_DELETE
                            scope = entry(ROOT, "encri vault", folder = true)
                        }
                }
            }
            val refreshed = (session().refreshGrant() as EncriResult.Ok).value
            assertEquals(setOf(Right.READ, Right.DELETE), refreshed.rights)
            assertEquals(3, refreshed.scope.childCount)
        }

    @Test
    fun `a folder that cannot be listed stops the whole listing`() =
        runTest {
            fake.pages = { _, _, _ -> PageResult().apply { status = IEncriService.STATUS_NOT_FOUND } }
            assertEquals(Failure.NOT_FOUND, (session().list(ROOT) as EncriResult.Failed).failure)
        }

    @Test
    fun `a page with nothing in it is an empty page`() =
        runTest {
            fake.pages = { _, _, _ -> PageResult().apply { status = IEncriService.STATUS_OK } }
            assertEquals(EncriPage(emptyList(), null), (session().listPage(ROOT) as EncriResult.Ok).value)
        }

    @Test
    fun `a folder is measured`() =
        runTest {
            fake.totals = {
                TotalsResult().apply {
                    status = IEncriService.STATUS_OK
                    bytes = 10
                    documents = 2
                    folders = 1
                }
            }
            assertEquals(FolderTotals(10, 2, 1), (session().measure(ROOT) as EncriResult.Ok).value)
        }

    @Test
    fun `a document opens as a descriptor the app owns`() =
        runTest {
            val file = File.createTempFile("read", ".bin").apply { writeBytes(byteArrayOf(9)) }
            fake.read = {
                DescriptorResult().apply {
                    status = IEncriService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            val descriptor = (session().openRead("x") as EncriResult.Ok).value
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { assertEquals(9, it.read()) }
            file.delete()
        }

    @Test
    fun `a success that carries nothing is a failure`() =
        runTest {
            fake.read = { DescriptorResult().apply { status = IEncriService.STATUS_OK } }
            fake.stat = { EntryResult().apply { status = IEncriService.STATUS_OK } }
            assertEquals(Failure.FAILED, (session().openRead("x") as EncriResult.Failed).failure)
            assertEquals(Failure.FAILED, (session().stat("x") as EncriResult.Failed).failure)
        }

    @Test
    fun `creating, renaming and moving ask with the name conflict the app chose`() =
        runTest {
            val flags = mutableListOf<Int>()
            fake.changed = { _, flag ->
                flags += flag
                ok(entry("n"))
            }
            val session = session()
            assertTrue(session.createFile(ROOT, "a.txt", "text/plain") is EncriResult.Ok)
            assertTrue(session.createFolder(ROOT, "b", NameConflict.PICK_FREE_NAME) is EncriResult.Ok)
            assertTrue(session.rename("n", "c.txt") is EncriResult.Ok)
            assertTrue(session.move("n", ROOT, NameConflict.PICK_FREE_NAME) is EncriResult.Ok)
            assertEquals(listOf(0, IEncriService.FLAG_FREE_NAME, 0, IEncriService.FLAG_FREE_NAME), flags)
            assertEquals("create $ROOT b ${IEncriService.MIME_TYPE_FOLDER}", fake.asked[1])
        }

    @Test
    fun `times left out are kept on the wire`() =
        runTest {
            fake.changed = { _, _ -> ok(entry("n")) }
            session().setTimes("n", modifiedMillis = 5)
            assertEquals("setTimes n -1 5", fake.asked.single())
        }

    @Test
    fun `deleting, revoking and locking answer what encri did`() =
        runTest {
            fake.outcome =
                { outcomeOf(if (it.startsWith("delete")) IEncriService.STATUS_OK else IEncriService.STATUS_FAILED) }
            val session = session()
            assertEquals(EncriResult.Ok(Unit), session.delete("x"))
            assertEquals(Failure.FAILED, (session.lock() as EncriResult.Failed).failure)
            assertEquals(
                Failure.FAILED,
                (session.revokeHandoffs(listOf(Uri.parse("content://a/b"))) as EncriResult.Failed).failure,
            )
        }

    @Test
    fun `documents handed off arrive as URIs`() =
        runTest {
            fake.handedOff = { ids ->
                HandoffResult().apply {
                    status = IEncriService.STATUS_OK
                    uris = ids.map { "content://com.systemhalted.encri.open/$it" }.toTypedArray()
                }
            }
            val uris = (session().handOff(listOf("t1", "t2")) as EncriResult.Ok).value
            assertEquals(listOf("t1", "t2"), uris.map { it.lastPathSegment })
        }

    @Test
    fun `space is what encri measured`() =
        runTest {
            fake.spaceLeft = {
                SpaceResult().apply {
                    status = IEncriService.STATUS_OK
                    usableBytes = 1
                    totalBytes = 2
                }
            }
            assertEquals(VaultSpace(1, 2), (session().space() as EncriResult.Ok).value)
        }

    @Test
    fun `encri's process going away mid-call is a disconnection, and ends the session`() =
        runTest {
            fake.stat = { throw RemoteException("gone") }
            val session = session()
            assertEquals(Failure.DISCONNECTED, (session.stat("x") as EncriResult.Failed).failure)
            assertFalse(session.isOpen)
            assertEquals(Failure.ENDED, (session.stat("x") as EncriResult.Failed).failure)
        }

    @Test
    fun `encri's process dying ends the session, says so, and answers a wait`() =
        runTest {
            fake.thumbnail = { _, _, _, _ ->
                ThumbnailTicket().apply {
                    status = IEncriService.STATUS_OK
                    requestId = 4
                }
            }
            val session = session(StandardTestDispatcher(testScheduler))
            val events = mutableListOf<VaultEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.events.toList(events) }
            val waiting = async { session.thumbnail("x", 64, 64) }
            runCurrent()
            requireNotNull(fake.recipient).binderDied()
            assertEquals(Failure.DISCONNECTED, (waiting.await() as EncriResult.Failed).failure)
            assertFalse(session.isOpen)
            assertEquals(listOf<VaultEvent>(VaultEvent.Ended), events)
            assertEquals("the connection was not told the session ended", session, closedBy)
        }

    @Test
    fun `closing twice tells encri once, and the connection once`() =
        runTest {
            val session = session()
            session.close()
            closedBy = null
            session.close()
            assertNull(closedBy)
            assertTrue(fake.ended)
            assertNull("the death recipient outlived the session", fake.recipient)
        }

    @Test
    fun `a preview encri failed to make is the failure encri names`() =
        runTest {
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(5, DescriptorResult().apply { status = IEncriService.STATUS_LOCKED })
                ThumbnailTicket().apply {
                    status = IEncriService.STATUS_OK
                    requestId = 5
                }
            }
            assertEquals(Failure.LOCKED, (session().thumbnail("x", 8, 8) as EncriResult.Failed).failure)
        }

    @Test
    fun `a preview delivered as nothing is a failure`() =
        runTest {
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(6, null)
                ThumbnailTicket().apply {
                    status = IEncriService.STATUS_OK
                    requestId = 6
                }
            }
            assertEquals(Failure.FAILED, (session().thumbnail("x", 8, 8) as EncriResult.Failed).failure)
        }

    @Test
    fun `a write encri will not open is the failure encri names`() =
        runTest {
            fake.write = { _, _ -> DescriptorResult().apply { status = IEncriService.STATUS_NOT_AUTHORIZED } }
            assertEquals(Failure.NOT_AUTHORIZED, (session().write("w") { } as EncriResult.Failed).failure)
        }

    @Test
    fun `a write encri did not save is the failure encri names`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, told ->
                told?.onWriteFinished(EntryResult().apply { status = IEncriService.STATUS_NO_SPACE })
                DescriptorResult().apply {
                    status = IEncriService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            assertEquals(Failure.NO_SPACE, (session().write("w") { it.write(1) } as EncriResult.Failed).failure)
            target.delete()
        }

    @Test
    fun `a writer that fails on its own is cancelled, not given encri's answer`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            var told: IWriteListener? = null
            fake.write = { _, listener ->
                told = listener
                DescriptorResult().apply {
                    status = IEncriService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            val written =
                session().write("w") {
                    // encri saved nothing and says the writer stopped, which is not a reason of encri's.
                    told?.onWriteFinished(ok(entry("w")))
                    throw IOException("the source went away")
                }
            assertEquals(EncriResult.Failed(Failure.CANCELLED, "the source went away"), written)
            target.delete()
        }

    @Test
    fun `what a value is, or null for a failure`() {
        assertEquals(1, EncriResult.Ok(1).valueOrNull())
        assertNull(EncriResult.Failed(Failure.FAILED, null).valueOrNull())
    }

    private fun document(id: String) =
        EncriDocument(id, null, "encri vault", IEncriService.MIME_TYPE_FOLDER, true, 0, 0, 0, 0, false)
}
