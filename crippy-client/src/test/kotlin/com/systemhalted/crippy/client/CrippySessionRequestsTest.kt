package com.systemhalted.crippy.client

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.systemhalted.crippy.api.DescriptorResult
import com.systemhalted.crippy.api.EntryResult
import com.systemhalted.crippy.api.Grant
import com.systemhalted.crippy.api.GrantResult
import com.systemhalted.crippy.api.HandoffResult
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.api.IWriteListener
import com.systemhalted.crippy.api.PageResult
import com.systemhalted.crippy.api.SpaceResult
import com.systemhalted.crippy.api.ThumbnailTicket
import com.systemhalted.crippy.api.TotalsResult
import com.systemhalted.crippy.client.internal.SessionListener
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
 * Every request a session makes, against a fake session: what crippy answers becomes the client's own
 * types, and what it does not answer — a missing field, an older crippy, a process gone — is a failure
 * rather than an exception (L5, L12).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrippySessionRequestsTest {
    private val fake = FakeSession()
    private val grant = CrippyGrant(setOf(Right.READ), document(ROOT))
    private val listener = SessionListener()
    private var closedBy: CrippySession? = null

    private fun TestScope.session(io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        CrippySession.open(fake, grant, listener, io) { closedBy = it }

    @Test
    fun `the grant is read again from crippy`() =
        runTest {
            fake.grant = {
                GrantResult().apply {
                    status = ICrippyService.STATUS_OK
                    this.grant =
                        Grant().apply {
                            rights = ICrippyService.RIGHT_READ or ICrippyService.RIGHT_DELETE
                            scope = entry(ROOT, "crippy vault", folder = true)
                        }
                }
            }
            val refreshed = (session().refreshGrant() as CrippyResult.Ok).value
            assertEquals(setOf(Right.READ, Right.DELETE), refreshed.rights)
            assertEquals(3, refreshed.scope.childCount)
        }

    @Test
    fun `a folder that cannot be listed stops the whole listing`() =
        runTest {
            fake.pages = { _, _, _ -> PageResult().apply { status = ICrippyService.STATUS_NOT_FOUND } }
            assertEquals(Failure.NOT_FOUND, (session().list(ROOT) as CrippyResult.Failed).failure)
        }

    @Test
    fun `a page with nothing in it is an empty page`() =
        runTest {
            fake.pages = { _, _, _ -> PageResult().apply { status = ICrippyService.STATUS_OK } }
            assertEquals(CrippyPage(emptyList(), null), (session().listPage(ROOT) as CrippyResult.Ok).value)
        }

    @Test
    fun `a folder is measured`() =
        runTest {
            fake.totals = {
                TotalsResult().apply {
                    status = ICrippyService.STATUS_OK
                    bytes = 10
                    documents = 2
                    folders = 1
                }
            }
            assertEquals(FolderTotals(10, 2, 1), (session().measure(ROOT) as CrippyResult.Ok).value)
        }

    @Test
    fun `a document opens as a descriptor the app owns`() =
        runTest {
            val file = File.createTempFile("read", ".bin").apply { writeBytes(byteArrayOf(9)) }
            fake.read = {
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            val descriptor = (session().openRead("x") as CrippyResult.Ok).value
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { assertEquals(9, it.read()) }
            file.delete()
        }

    @Test
    fun `a success that carries nothing is a failure`() =
        runTest {
            fake.read = { DescriptorResult().apply { status = ICrippyService.STATUS_OK } }
            fake.stat = { EntryResult().apply { status = ICrippyService.STATUS_OK } }
            assertEquals(Failure.FAILED, (session().openRead("x") as CrippyResult.Failed).failure)
            assertEquals(Failure.FAILED, (session().stat("x") as CrippyResult.Failed).failure)
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
            assertTrue(session.createFile(ROOT, "a.txt", "text/plain") is CrippyResult.Ok)
            assertTrue(session.createFolder(ROOT, "b", NameConflict.PICK_FREE_NAME) is CrippyResult.Ok)
            assertTrue(session.rename("n", "c.txt") is CrippyResult.Ok)
            assertTrue(session.move("n", ROOT, NameConflict.PICK_FREE_NAME) is CrippyResult.Ok)
            assertEquals(listOf(0, ICrippyService.FLAG_FREE_NAME, 0, ICrippyService.FLAG_FREE_NAME), flags)
            assertEquals("create $ROOT b ${ICrippyService.MIME_TYPE_FOLDER}", fake.asked[1])
        }

    @Test
    fun `times left out are kept on the wire`() =
        runTest {
            fake.changed = { _, _ -> ok(entry("n")) }
            session().setTimes("n", modifiedMillis = 5)
            assertEquals("setTimes n -1 5", fake.asked.single())
        }

    @Test
    fun `deleting, revoking and locking answer what crippy did`() =
        runTest {
            fake.outcome =
                { outcomeOf(if (it.startsWith("delete")) ICrippyService.STATUS_OK else ICrippyService.STATUS_FAILED) }
            val session = session()
            assertEquals(CrippyResult.Ok(Unit), session.delete("x"))
            assertEquals(Failure.FAILED, (session.lock() as CrippyResult.Failed).failure)
            assertEquals(
                Failure.FAILED,
                (session.revokeHandoffs(listOf(Uri.parse("content://a/b"))) as CrippyResult.Failed).failure,
            )
        }

    @Test
    fun `documents handed off arrive as URIs`() =
        runTest {
            fake.handedOff = { ids ->
                HandoffResult().apply {
                    status = ICrippyService.STATUS_OK
                    uris = ids.map { "content://com.systemhalted.crippy.open/$it" }.toTypedArray()
                }
            }
            val uris = (session().handOff(listOf("t1", "t2")) as CrippyResult.Ok).value
            assertEquals(listOf("t1", "t2"), uris.map { it.lastPathSegment })
        }

    @Test
    fun `space is what crippy measured`() =
        runTest {
            fake.spaceLeft = {
                SpaceResult().apply {
                    status = ICrippyService.STATUS_OK
                    usableBytes = 1
                    totalBytes = 2
                }
            }
            assertEquals(VaultSpace(1, 2), (session().space() as CrippyResult.Ok).value)
        }

    @Test
    fun `crippy's process going away mid-call is a disconnection, and ends the session`() =
        runTest {
            fake.stat = { throw RemoteException("gone") }
            val session = session()
            assertEquals(Failure.DISCONNECTED, (session.stat("x") as CrippyResult.Failed).failure)
            assertFalse(session.isOpen)
            assertEquals(Failure.ENDED, (session.stat("x") as CrippyResult.Failed).failure)
        }

    @Test
    fun `crippy's process dying ends the session, says so, and answers a wait`() =
        runTest {
            fake.thumbnail = { _, _, _, _ ->
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 4
                }
            }
            val session = session(StandardTestDispatcher(testScheduler))
            val events = mutableListOf<VaultEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.events.toList(events) }
            val waiting = async { session.thumbnail("x", 64, 64) }
            runCurrent()
            requireNotNull(fake.recipient).binderDied()
            assertEquals(Failure.DISCONNECTED, (waiting.await() as CrippyResult.Failed).failure)
            assertFalse(session.isOpen)
            assertEquals(listOf<VaultEvent>(VaultEvent.Ended), events)
            assertEquals("the connection was not told the session ended", session, closedBy)
        }

    @Test
    fun `closing twice tells crippy once, and the connection once`() =
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
    fun `a preview crippy failed to make is the failure crippy names`() =
        runTest {
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(5, DescriptorResult().apply { status = ICrippyService.STATUS_LOCKED })
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 5
                }
            }
            assertEquals(Failure.LOCKED, (session().thumbnail("x", 8, 8) as CrippyResult.Failed).failure)
        }

    @Test
    fun `a preview delivered as nothing is a failure`() =
        runTest {
            fake.thumbnail = { _, _, _, told ->
                told?.onThumbnail(6, null)
                ThumbnailTicket().apply {
                    status = ICrippyService.STATUS_OK
                    requestId = 6
                }
            }
            assertEquals(Failure.FAILED, (session().thumbnail("x", 8, 8) as CrippyResult.Failed).failure)
        }

    @Test
    fun `a write crippy will not open is the failure crippy names`() =
        runTest {
            fake.write = { _, _ -> DescriptorResult().apply { status = ICrippyService.STATUS_NOT_AUTHORIZED } }
            assertEquals(Failure.NOT_AUTHORIZED, (session().write("w") { } as CrippyResult.Failed).failure)
        }

    @Test
    fun `a write crippy did not save is the failure crippy names`() =
        runTest {
            val target = File.createTempFile("write", ".bin")
            fake.write = { _, told ->
                told?.onWriteFinished(EntryResult().apply { status = ICrippyService.STATUS_NO_SPACE })
                DescriptorResult().apply {
                    status = ICrippyService.STATUS_OK
                    descriptor = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_WRITE_ONLY)
                }
            }
            assertEquals(Failure.NO_SPACE, (session().write("w") { it.write(1) } as CrippyResult.Failed).failure)
            target.delete()
        }

    @Test
    fun `a writer that fails on its own is cancelled, not given crippy's answer`() =
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
                    // crippy saved nothing and says the writer stopped, which is not a reason of crippy's.
                    told?.onWriteFinished(ok(entry("w")))
                    throw IOException("the source went away")
                }
            assertEquals(CrippyResult.Failed(Failure.CANCELLED, "the source went away"), written)
            target.delete()
        }

    @Test
    fun `what a value is, or null for a failure`() {
        assertEquals(1, CrippyResult.Ok(1).valueOrNull())
        assertNull(CrippyResult.Failed(Failure.FAILED, null).valueOrNull())
    }

    private fun document(id: String) =
        CrippyDocument(id, null, "crippy vault", ICrippyService.MIME_TYPE_FOLDER, true, 0, 0, 0, 0, false)
}
