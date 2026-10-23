package com.systemhalted.crippy.client.device

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.ConnectResult
import com.systemhalted.crippy.client.CrippyConnection
import com.systemhalted.crippy.client.CrippyResult
import com.systemhalted.crippy.client.CrippySession
import com.systemhalted.crippy.client.Failure
import com.systemhalted.crippy.client.NameConflict
import com.systemhalted.crippy.client.Right
import com.systemhalted.crippy.client.SessionOutcome
import com.systemhalted.crippy.client.VaultEvent
import com.systemhalted.crippy.client.internal.ServiceBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The client over a real Binder, against [StandInCrippy] and [OlderCrippy] in processes of their own
 * (L13). Everything the unit tests run against a fake in one process — a parcel read back, a pipe, a
 * descriptor, a listener called oneway, a binder that dies — here crosses the kernel, as it does
 * between an app and crippy. It needs no crippy, so it is what CI runs.
 */
@RunWith(AndroidJUnit4::class)
class CrossProcessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val connections = mutableListOf<CrippyConnection>()
    private val rawBindings = mutableListOf<ServiceConnection>()

    @After
    fun closeEverything() {
        connections.forEach(CrippyConnection::close)
        rawBindings.forEach(context::unbindService)
    }

    // ---- L2, over a real BinderProxy ---------------------------------------------------------------

    @Test
    fun anOlderCrippyAnswersZeroRatherThanAnErrorThroughARealProxy() {
        val binder = bindRaw(Intent().setClassName(context.packageName, OlderCrippy::class.java.name))
        assertEquals("the stand-in is in this process", "android.os.BinderProxy", binder.javaClass.name)
        val older = ICrippyService.Stub.asInterface(binder)
        // The control: without it, a reply that read zero for everything would pass the next line.
        assertEquals(ICrippyService.VERSION, older.contractVersion())
        assertNull("a method the service lacks read as something", older.openSession(null))
    }

    @Test
    fun aServiceAnsweringVersionZeroIsNotConnectedTo() {
        val answer =
            connect(
                Intent().setClassName(context.packageName, OlderCrippy::class.java.name).setAction(OlderCrippy.ZERO),
            )
        assertTrue("connected to $answer", answer is ConnectResult.Unavailable)
    }

    @Test
    fun aSessionAskedOfAnOlderCrippyIsAFailureAndNotASuccess() {
        val connection = connected(Intent().setClassName(context.packageName, OlderCrippy::class.java.name))
        val outcome = blocking { connection.openSession() }
        assertEquals(SessionOutcome.Failed(Failure.FAILED, "crippy did not answer"), outcome)
    }

    // ---- A session, across the Binder --------------------------------------------------------------

    @Test
    fun listingCrossesTheBinderAPageAtATime() {
        val session = session()
        assertEquals(setOf(Right.READ, Right.WRITE, Right.CREATE, Right.DELETE, Right.MOVE), session.grant.rights)
        val folder = ok(blocking { session.createFolder(StandInCrippy.ROOT, "listing", NameConflict.PICK_FREE_NAME) })
        val names = (1..7).map { "document $it.txt" }.shuffled()
        names.forEach { ok(blocking { session.createFile(folder.id, it, "text/plain") }) }
        val listed = ok(blocking { session.list(folder.id) })
        assertEquals("seven documents in three pages", names.sorted(), listed.map { it.name })
        val first = ok(blocking { session.listPage(folder.id, limit = 100) })
        assertEquals("crippy's cap did not hold", StandInCrippy.PAGE_CAP, first.documents.size)
        assertEquals(7, ok(blocking { session.stat(folder.id) }).childCount)
        assertEquals(7, ok(blocking { session.measure(folder.id) }).documents)
        val taken = blocking { session.createFile(folder.id, names.first(), "text/plain") }
        assertEquals(Failure.NAME_TAKEN, (taken as CrippyResult.Failed).failure)
    }

    @Test
    fun aWriteCrossesARealPipeAndIsReadBackThroughADescriptorFromAnotherProcess() {
        val session = session()
        val document =
            ok(blocking { session.createFile(StandInCrippy.ROOT, "written.bin", "application/octet-stream") })
        val content = pattern(LONG_WRITE)
        val saved = ok(blocking { session.write(document.id) { it.write(content) } })
        assertEquals(LONG_WRITE.toLong(), saved.sizeBytes)
        ok(blocking { session.openRead(document.id) }).use { descriptor ->
            val from = Os.readlink("/proc/self/fd/${descriptor.fd}")
            assertTrue(
                "the descriptor is not the stand-in's file: $from",
                "/cache/read" in from && from.endsWith("(deleted)"),
            )
            assertArrayEquals(content, FileInputStream(descriptor.fileDescriptor).readBytes())
            val window = ByteArray(PREAD_LENGTH)
            Os.pread(descriptor.fileDescriptor, window, 0, PREAD_LENGTH, PREAD_OFFSET.toLong())
            assertArrayEquals(content.copyOfRange(PREAD_OFFSET, PREAD_OFFSET + PREAD_LENGTH), window)
        }
    }

    @Test
    fun aWriterThatFailsSavesNothing() {
        val session = session()
        val document = ok(blocking { session.createFile(StandInCrippy.ROOT, "kept.txt", "text/plain") })
        ok(blocking { session.write(document.id) { it.write(BEFORE) } })
        val stopped =
            blocking {
                session.write(document.id) {
                    it.write(pattern(SHORT_WRITE))
                    it.flush()
                    throw IOException("the writer gave up")
                }
            }
        assertEquals(Failure.CANCELLED, (stopped as CrippyResult.Failed).failure)
        assertEquals(
            "a write the writer abandoned was saved",
            BEFORE.size.toLong(),
            ok(
                blocking {
                    session.stat(document.id)
                },
            ).sizeBytes,
        )
    }

    /**
     * crippy refuses a write part way by closing its end of the pipe, so the writer's next write fails;
     * the reason arrives on the listener a moment later (L12). This is also the case that would end the
     * app's process if a broken pipe raised SIGPIPE rather than an error, which only a device can say.
     */
    @Test
    fun aWriteCrippyRefusesPartWayIsToldCrippysReason() {
        val session = session()
        val document =
            ok(
                blocking {
                    session.createFile(
                        StandInCrippy.ROOT,
                        StandInCrippy.REFUSED_PART_WAY,
                        "application/octet-stream",
                    )
                },
            )
        val refused = blocking { session.write(document.id) { it.write(pattern(LONG_WRITE)) } }
        assertEquals(CrippyResult.Failed(Failure.NO_SPACE, "the stand-in is full"), refused)
    }

    @Test
    fun aPreviewArrivesOnewayOnADescriptor() {
        val session = session()
        val picture = ok(blocking { session.createFile(StandInCrippy.ROOT, "picture.png", "image/png") })
        val png = ok(blocking { session.thumbnail(picture.id, PREVIEW_WIDTH, PREVIEW_HEIGHT) })
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "not an image" }
        assertEquals(PREVIEW_WIDTH, bitmap.width)
        assertEquals(PREVIEW_HEIGHT, bitmap.height)
    }

    @Test
    fun anEventCrossesOneway() {
        val session = session()
        val heard = CoroutineScope(Dispatchers.IO).async { session.events.first() }
        // The flow replays nothing, so the collector has to be listening before the event is sent.
        blocking { delay(SETTLE_MILLIS) }
        ok(blocking { session.lock() })
        assertEquals(VaultEvent.Locked, blocking { heard.await() })
    }

    // ---- A process that dies ---------------------------------------------------------------------

    /**
     * crippy's process going away ends the session, answers a request waiting on a listener, and leaves
     * a connection Android binds again by itself (L12): nothing the unit tests can do but pretend.
     */
    @Test
    fun crippysProcessDyingEndsTheSessionAndTheConnectionComesBack() {
        val connection = connected(standIn())
        val session = opened(connection)
        val picture =
            ok(blocking { session.createFile(StandInCrippy.ROOT, StandInCrippy.NEVER_PREVIEWED, "image/png") })
        val ended = CoroutineScope(Dispatchers.IO).async { session.events.first { it == VaultEvent.Ended } }
        val waiting =
            CoroutineScope(
                Dispatchers.IO,
            ).async { session.thumbnail(picture.id, PREVIEW_WIDTH, PREVIEW_HEIGHT) }
        blocking { delay(SETTLE_MILLIS) }
        Process.killProcess(pidOf(STAND_IN_PROCESS))

        assertEquals(
            CrippyResult.Failed(Failure.DISCONNECTED, "crippy's process went away"),
            blocking { waiting.await() },
        )
        assertEquals(VaultEvent.Ended, blocking { ended.await() })
        assertFalse("the session outlived crippy's process", session.isOpen)
        assertEquals(Failure.ENDED, (blocking { session.stat(picture.id) } as CrippyResult.Failed).failure)

        blocking(REBIND_MILLIS) { while (!connection.isConnected) delay(POLL_MILLIS) }
        val again = opened(connection)
        assertEquals(
            "the old process answered",
            emptyList<String>(),
            ok(
                blocking {
                    again.list(StandInCrippy.ROOT)
                },
            ).map { it.id },
        )
    }

    // ---- How -------------------------------------------------------------------------------------

    private fun standIn(): Intent = Intent().setClassName(context.packageName, StandInCrippy::class.java.name)

    private fun connect(intent: Intent): ConnectResult =
        blocking { ServiceBinding.connect(context, Dispatchers.IO, intent, context.packageName) }
            .also { (it as? ConnectResult.Connected)?.let { connected -> connections += connected.connection } }

    private fun connected(intent: Intent): CrippyConnection =
        when (val answer = connect(intent)) {
            is ConnectResult.Connected -> answer.connection
            else -> throw AssertionError("not connected: $answer")
        }

    private fun opened(connection: CrippyConnection): CrippySession =
        when (val outcome = blocking { connection.openSession() }) {
            is SessionOutcome.Opened -> outcome.session
            else -> throw AssertionError("no session: $outcome")
        }

    private fun session(): CrippySession = opened(connected(standIn()))

    /** A binder bound by hand, for the cases that must reach the contract without the client. */
    private fun bindRaw(intent: Intent): IBinder {
        val bound = LinkedBlockingQueue<IBinder>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    bound.offer(service)
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        rawBindings += connection
        return requireNotNull(bound.poll(BIND_SECONDS, TimeUnit.SECONDS)) { "the service did not answer" }
    }

    private fun pidOf(process: String): Int {
        val name = "${context.packageName}:$process"
        val activity = context.getSystemService(ActivityManager::class.java)
        return requireNotNull(activity.runningAppProcesses.orEmpty().firstOrNull { it.processName == name }) {
            "$name is not running"
        }.pid
    }

    private fun <T> ok(result: CrippyResult<T>): T =
        when (result) {
            is CrippyResult.Ok -> result.value
            is CrippyResult.Failed -> throw AssertionError("failed: $result")
        }

    private fun <T> blocking(
        millis: Long = TIMEOUT_MILLIS,
        block: suspend CoroutineScope.() -> T,
    ): T = runBlocking { withTimeout(millis, block) }

    private fun ParcelFileDescriptor.use(block: (ParcelFileDescriptor) -> Unit) =
        try {
            block(this)
        } finally {
            close()
        }

    private companion object {
        const val STAND_IN_PROCESS = "standin"
        const val LONG_WRITE = 1_000_003
        const val SHORT_WRITE = 70_000
        const val PREAD_OFFSET = 700_001
        const val PREAD_LENGTH = 70_000
        const val PREVIEW_WIDTH = 32
        const val PREVIEW_HEIGHT = 24
        const val TIMEOUT_MILLIS = 30_000L
        const val REBIND_MILLIS = 60_000L
        const val SETTLE_MILLIS = 500L
        const val POLL_MILLIS = 100L
        const val BIND_SECONDS = 10L
        val BEFORE = "kept as it was".toByteArray()

        fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }
    }
}
