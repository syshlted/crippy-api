package com.systemhalted.crippy.client.device

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.Availability
import com.systemhalted.crippy.client.ConnectResult
import com.systemhalted.crippy.client.Crippy
import com.systemhalted.crippy.client.CrippyConnection
import com.systemhalted.crippy.client.CrippyResult
import com.systemhalted.crippy.client.CrippySession
import com.systemhalted.crippy.client.Failure
import com.systemhalted.crippy.client.NameConflict
import com.systemhalted.crippy.client.Right
import com.systemhalted.crippy.client.SessionOutcome
import com.systemhalted.crippy.client.VaultEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The client against the crippy installed on this phone, from an app crippy is not (L13): a different
 * package, a different uid, the real service, the real consent screen and the vault's own AppFuse
 * descriptors. It closes the check crippy's D120 owed, which ran its probe under crippy's own uid.
 *
 * **What it needs**: crippy installed and signed by the same key as this test, which a debug build of
 * each on one machine is; a vault; and, if the vault is locked or this test has not been let in yet, the
 * vault's passphrase as the instrumentation argument `crippyPassphrase`. It presses crippy's buttons
 * itself (`CrippyScreens`), so the screen must be on and not behind a secure keyguard. Without crippy it
 * skips, which is what CI does.
 *
 * **What it leaves**: nothing in the vault — every document it makes is under one folder it deletes —
 * and the grant crippy keeps for this test app, so a second run asks nobody.
 *
 * The steps run in order and share one session, because the session is the thing under test.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AgainstCrippyTest {
    @Test
    fun step01CrippyIsTrustedAndConnectedTo() {
        assertEquals(Availability.Ready, Crippy.availability(context))
        assertEquals(ICrippyService.VERSION, connection.contractVersion)
        assertTrue(connection.isConnected)
    }

    @Test
    fun step02TheSessionCarriesTheGrantTheUserGave() {
        assertEquals(ALL_RIGHTS, session.grant.rights)
        assertEquals("a whole-vault grant has no parent", null, session.grant.scope.parentId)
        // Rights and scope, not the scope's whole entry: the setup made a folder in it, so its count moved.
        val now = ok(blocking { session.refreshGrant() })
        assertEquals(session.grant.rights, now.rights)
        assertEquals(session.grant.scope.id, now.scope.id)
    }

    @Test
    fun step03AWriteIsReadBackThroughTheVaultsOwnDescriptor() {
        val document = ok(blocking { session.createFile(folder, "written.bin", "application/octet-stream") })
        val saved = ok(blocking { session.write(document.id) { it.write(CONTENT) } })
        assertEquals(CONTENT.size.toLong(), saved.sizeBytes)
        ok(blocking { session.openRead(document.id) }).use { descriptor ->
            val from = Os.readlink("/proc/self/fd/${descriptor.fd}")
            assertTrue("not one of crippy's AppFuse descriptors: $from", from.startsWith("/mnt/appfuse/"))
            assertArrayEquals(CONTENT, FileInputStream(descriptor.fileDescriptor).readBytes())
            val window = ByteArray(WINDOW)
            Os.pread(descriptor.fileDescriptor, window, 0, WINDOW, OFFSET.toLong())
            assertArrayEquals("a read across a block boundary", CONTENT.copyOfRange(OFFSET, OFFSET + WINDOW), window)
        }
    }

    @Test
    fun step04AFolderIsListedAPageAtATimeAndMeasured() {
        val names = (1..5).map { "page $it.txt" }
        names.forEach { ok(blocking { session.createFile(folder, it, "text/plain") }) }
        val first = ok(blocking { session.listPage(folder, limit = 2) })
        assertEquals(2, first.documents.size)
        assertNotNull("one page held everything", first.next)
        assertEquals((names + "written.bin").sorted(), ok(blocking { session.list(folder) }).map { it.name })
        val totals = ok(blocking { session.measure(folder) })
        assertEquals(names.size + 1, totals.documents)
        assertEquals(CONTENT.size.toLong(), totals.bytes)
    }

    @Test
    fun step05ADocumentIsRenamedMovedAndRedated() {
        val document = ok(blocking { session.createFile(folder, "to rename.txt", "text/plain") })
        val inner = ok(blocking { session.createFolder(folder, "inner") })
        val renamed = ok(blocking { session.rename(document.id, "renamed.txt") })
        assertEquals("renamed.txt", renamed.name)
        val moved = ok(blocking { session.move(document.id, inner.id) })
        assertEquals(inner.id, moved.parentId)
        val dated = ok(blocking { session.setTimes(document.id, modifiedMillis = A_DATE) })
        assertEquals(A_DATE, dated.modifiedMillis)
        assertEquals("setting the modified time moved the created one", renamed.createdMillis, dated.createdMillis)
        val refused = blocking { session.move(inner.id, inner.id) }
        assertEquals(Failure.INVALID, (refused as CrippyResult.Failed).failure)
    }

    @Test
    fun step06APictureHasAPreviewMadeInCrippy() {
        val picture = ok(blocking { session.createFile(folder, "picture.png", "image/png") })
        ok(blocking { session.write(picture.id) { it.write(png(PICTURE_WIDTH, PICTURE_HEIGHT)) } })
        assertTrue(ok(blocking { session.stat(picture.id) }).thumbnailable)
        val preview = ok(blocking { session.thumbnail(picture.id, PREVIEW, PREVIEW) })
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(preview, 0, preview.size)) { "not an image" }
        assertTrue(
            "${bitmap.width}x${bitmap.height} is larger than asked",
            bitmap.width <= PREVIEW && bitmap.height <= PREVIEW,
        )
    }

    @Test
    fun step07AHandoffIsReadableUntilItIsRevoked() {
        val document = ok(blocking { session.createFile(folder, "handed.txt", "text/plain") })
        ok(blocking { session.write(document.id) { it.write(HANDED) } })
        val uri = ok(blocking { session.handOff(listOf(document.id)) }).single()
        assertTrue("the URI names the document: $uri", document.id !in uri.toString() && "handed" !in uri.toString())
        assertArrayEquals(HANDED, read(uri))
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { names ->
            assertTrue(requireNotNull(names).moveToFirst())
            assertEquals("handed.txt", names.getString(0))
        }
        ok(blocking { session.revokeHandoffs(listOf(uri)) })
        val after = runCatching { read(uri) }.exceptionOrNull()
        assertTrue("a revoked handoff still read: $after", after is FileNotFoundException || after is SecurityException)
    }

    @Test
    fun step08ARefusalIsAnAnswerAndAnAbandonedWriteSavesNothing() {
        assertEquals(Failure.NOT_FOUND, (blocking { session.stat(NO_SUCH_DOCUMENT) } as CrippyResult.Failed).failure)
        val taken = blocking { session.createFile(folder, "written.bin", "application/octet-stream") }
        assertEquals(Failure.NAME_TAKEN, (taken as CrippyResult.Failed).failure)
        val free = ok(blocking { session.createFile(folder, "written.bin", "text/plain", NameConflict.PICK_FREE_NAME) })
        assertTrue("not a free name: ${free.name}", free.name != "written.bin")
        ok(blocking { session.write(free.id) { it.write(HANDED) } })
        val stopped =
            blocking {
                session.write(free.id) {
                    it.write(CONTENT)
                    throw IOException("the writer gave up")
                }
            }
        assertEquals(Failure.CANCELLED, (stopped as CrippyResult.Failed).failure)
        assertEquals(
            "an abandoned write was saved",
            HANDED.size.toLong(),
            ok(blocking { session.stat(free.id) }).sizeBytes,
        )
        assertTrue(ok(blocking { session.space() }).usableBytes > 0)
    }

    /** A session outlives a lock, and a descriptor already open goes on serving across it (crippy D120). */
    @Test
    fun step09ASessionOutlivesALockAndAnOpenDescriptorServesAcrossIt() {
        assumeTrue("locking needs crippyPassphrase to unlock again", passphrase != null)
        val written = ok(blocking { session.list(folder) }).first { it.name == "written.bin" }
        ok(blocking { session.openRead(written.id) }).use { descriptor ->
            val events = CoroutineScope(Dispatchers.IO).async { session.events.first { it == VaultEvent.Locked } }
            blocking { delay(SETTLE_MILLIS) }
            ok(blocking { session.lock() })
            assertEquals(VaultEvent.Locked, blocking { events.await() })
            assertEquals(Failure.LOCKED, (blocking { session.stat(folder) } as CrippyResult.Failed).failure)
            assertArrayEquals(
                "an open descriptor stopped at the lock",
                CONTENT,
                FileInputStream(descriptor.fileDescriptor).readBytes(),
            )
        }
        val unlocked = CoroutineScope(Dispatchers.IO).async { session.events.first { it == VaultEvent.Unlocked } }
        blocking { delay(SETTLE_MILLIS) }
        unlock()
        assertEquals(VaultEvent.Unlocked, blocking { unlocked.await() })
        assertTrue(session.isOpen)
        assertEquals(folder, ok(blocking { session.stat(folder) }).id)
    }

    @Test
    fun step10DeletingTheFolderTakesEverythingInIt() {
        ok(blocking { session.delete(folder) })
        assertEquals(Failure.NOT_FOUND, (blocking { session.stat(folder) } as CrippyResult.Failed).failure)
        deleted = true
    }

    companion object {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context: Context = instrumentation.targetContext
        private val passphrase: String? = InstrumentationRegistry.getArguments().getString("crippyPassphrase")
        private val screens = CrippyScreens(instrumentation)

        private lateinit var connection: CrippyConnection
        private lateinit var session: CrippySession
        private lateinit var folder: String
        private var deleted = false

        private val ALL_RIGHTS = setOf(Right.READ, Right.WRITE, Right.CREATE, Right.DELETE, Right.MOVE)

        @BeforeClass
        @JvmStatic
        fun connectAndOpen() {
            assumeTrue("crippy is not installed here", Crippy.availability(context) != Availability.NotInstalled)
            connection =
                when (val connected = blocking { Crippy.connect(context) }) {
                    is ConnectResult.Connected -> connected.connection
                    else -> throw AssertionError("not connected to crippy: $connected")
                }
            session = openSession()
            folder =
                ok(blocking { session.createFolder(session.grant.scope.id, FOLDER, NameConflict.PICK_FREE_NAME) }).id
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (!::connection.isInitialized) return
            if (::session.isInitialized && ::folder.isInitialized &&
                !deleted
            ) {
                runCatching { blocking { session.delete(folder) } }
            }
            connection.close()
        }

        /** A session, asking the user — that is, crippy's screens, pressed by [screens] — for what it needs. */
        private fun openSession(): CrippySession {
            repeat(ATTEMPTS) {
                when (val outcome = blocking { connection.openSession() }) {
                    is SessionOutcome.Opened -> {
                        if (outcome.session.grant.rights == ALL_RIGHTS) return outcome.session
                        outcome.session.close()
                        authorize()
                    }

                    SessionOutcome.Locked -> {
                        unlock()
                    }

                    SessionOutcome.NotAuthorized, SessionOutcome.AccessOff -> {
                        authorize()
                    }

                    is SessionOutcome.Failed -> {
                        throw AssertionError("crippy would not open a session: $outcome")
                    }
                }
            }
            throw AssertionError("no session after $ATTEMPTS tries")
        }

        private fun unlock() {
            val typed = needPassphrase()
            ResultHost.start(context, Crippy.unlockIntent())
            screens.unlockWith(typed)
            val answer =
                requireNotNull(ResultHost.awaitAnswer(ANSWER_SECONDS)) { "crippy's unlock screen never answered" }
            assertEquals("the vault did not unlock", Activity.RESULT_OK, answer.resultCode)
        }

        private fun authorize() {
            ResultHost.start(context, Crippy.authorizationIntent(ALL_RIGHTS))
            while (true) {
                when (
                    screens.whichOf(
                        CrippyScreens.UNLOCK_THE_VAULT,
                        CrippyScreens.TURN_ACCESS_ON,
                        CrippyScreens.ALLOW_THE_WHOLE_VAULT,
                    )
                ) {
                    CrippyScreens.UNLOCK_THE_VAULT -> {
                        val typed = needPassphrase()
                        screens.press(CrippyScreens.UNLOCK_THE_VAULT)
                        screens.unlockWith(typed)
                    }

                    CrippyScreens.TURN_ACCESS_ON -> {
                        screens.press(CrippyScreens.TURN_ACCESS_ON)
                    }

                    else -> {
                        screens.press(CrippyScreens.ALLOW_THE_WHOLE_VAULT)
                        break
                    }
                }
            }
            val answer =
                requireNotNull(ResultHost.awaitAnswer(ANSWER_SECONDS)) { "crippy's consent screen never answered" }
            assertEquals(ALL_RIGHTS, Crippy.authorizationResult(answer.resultCode, answer.data))
        }

        private fun needPassphrase(): String {
            assumeTrue("crippy's vault needs unlocking, and no crippyPassphrase was given", passphrase != null)
            return requireNotNull(passphrase)
        }

        private fun read(uri: Uri): ByteArray =
            requireNotNull(context.contentResolver.openInputStream(uri)) { "nothing opened" }.use { it.readBytes() }

        private fun png(
            width: Int,
            height: Int,
        ): ByteArray =
            ByteArrayOutputStream()
                .also { out ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.CYAN)
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                    bitmap.recycle()
                }.toByteArray()

        private fun <T> ok(result: CrippyResult<T>): T =
            when (result) {
                is CrippyResult.Ok -> result.value
                is CrippyResult.Failed -> throw AssertionError("failed: $result")
            }

        private fun <T> blocking(block: suspend CoroutineScope.() -> T): T =
            runBlocking { withTimeout(TIMEOUT_MILLIS, block) }

        private fun <R> ParcelFileDescriptor.use(block: (ParcelFileDescriptor) -> R): R =
            try {
                block(this)
            } finally {
                close()
            }

        private const val FOLDER = "crippy-api device test"
        private const val ATTEMPTS = 4
        private const val ANSWER_SECONDS = 60L
        private const val TIMEOUT_MILLIS = 60_000L
        private const val SETTLE_MILLIS = 500L
        private const val OFFSET = 65_000
        private const val WINDOW = 10_000
        private const val PICTURE_WIDTH = 300
        private const val PICTURE_HEIGHT = 200
        private const val PREVIEW = 64
        private const val PNG_QUALITY = 100
        private const val A_DATE = 1_600_000_000_000L
        private const val NO_SUCH_DOCUMENT = "0123456789abcdef0123456789abcdef"
        private val CONTENT = ByteArray(1_000_003) { (it * 31 + 7).toByte() }
        private val HANDED = "handed to another app".toByteArray()
    }
}
