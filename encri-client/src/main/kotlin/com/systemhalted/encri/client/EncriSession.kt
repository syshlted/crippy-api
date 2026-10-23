package com.systemhalted.encri.client

import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.systemhalted.encri.api.DescriptorResult
import com.systemhalted.encri.api.EntryResult
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.api.IEncriSession
import com.systemhalted.encri.api.IThumbnailListener
import com.systemhalted.encri.api.IWriteListener
import com.systemhalted.encri.client.internal.SessionListener
import com.systemhalted.encri.client.internal.documentOf
import com.systemhalted.encri.client.internal.failureOf
import com.systemhalted.encri.client.internal.rightsOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This app's session with the vault (encri D114; L11, L12).
 *
 * **Every request answers a [EncriResult]**, never an exception: a status encri sent, encri's process
 * going away, and a session that is over are all a [Failure], because an app holding the raw contract
 * would otherwise have to catch them all itself (L5). Each request moves its own work off the caller's
 * thread.
 *
 * **A session outlives a lock.** While the vault is locked every request answers [Failure.LOCKED], and
 * after the next unlock they work again; [events] says which. It ends when the user revokes this app,
 * turns access for other apps off, the app calls [close], or encri's process goes away.
 */
@Suppress("TooManyFunctions") // One per request the contract has.
public class EncriSession private constructor(
    private val remote: IEncriSession,
    /** What the user allowed this app, as it was when the session opened. */
    public val grant: EncriGrant,
    private val listener: SessionListener,
    private val io: CoroutineDispatcher,
    private val onClosed: (EncriSession) -> Unit,
) : AutoCloseable {
    private val ended = AtomicBoolean(false)
    private val waiting = ConcurrentHashMap.newKeySet<CompletableDeferred<*>>()

    private val death =
        IBinder.DeathRecipient {
            ended.set(true)
            onClosed(this)
            listener.ended()
            waiting.toList().forEach { it.cancel(CancellationException(GONE)) }
        }

    init {
        // A binder already dead throws here, and is a session that has already ended.
        if (runCatching { remote.asBinder().linkToDeath(death, 0) }.isFailure) ended.set(true)
    }

    /** What encri tells this session while it lasts: lock, unlock, the end, and folders that changed. */
    public val events: SharedFlow<VaultEvent> get() = listener.events

    /** Whether the session can still be used. */
    public val isOpen: Boolean get() = !ended.get()

    /** The grant as it is now, which is the same as [grant] unless encri's answer has changed. */
    public suspend fun refreshGrant(): EncriResult<EncriGrant> =
        call({ it.grant() }, { it.status }, { it.message }) { answer ->
            val granted = requireNotNull(answer.grant) { "encri answered a grant with nothing in it" }
            EncriGrant(rightsOf(granted.rights), documentOf(granted.scope))
        }

    public suspend fun stat(documentId: String): EncriResult<EncriDocument> = entry { it.stat(documentId) }

    /** One page of [folderId]'s children in name order: [after] is null for the first page. */
    public suspend fun listPage(
        folderId: String,
        after: String? = null,
        limit: Int = DEFAULT_PAGE,
    ): EncriResult<EncriPage> =
        call({ it.list(folderId, after, limit) }, { it.status }, { it.message }) { answer ->
            EncriPage(answer.entries.orEmpty().map(::documentOf), answer.next)
        }

    /** All of [folderId]'s children in name order, a page at a time. */
    public suspend fun list(folderId: String): EncriResult<List<EncriDocument>> {
        val all = mutableListOf<EncriDocument>()
        var after: String? = null
        do {
            val page =
                when (val result = listPage(folderId, after)) {
                    is EncriResult.Ok -> result.value
                    is EncriResult.Failed -> return result
                }
            all += page.documents
            after = page.next
        } while (after != null)
        return EncriResult.Ok(all)
    }

    /** Everything under [folderId], counted. */
    public suspend fun measure(folderId: String): EncriResult<FolderTotals> =
        call(
            { it.measure(folderId) },
            { it.status },
            { it.message },
        ) { FolderTotals(it.bytes, it.documents, it.folders) }

    /**
     * A read-only descriptor over the document's content that seeks, which the app owns and closes.
     * It may be handed to another app, and mapped (encri D12). It stops at its next read if this app is
     * revoked or access is turned off (encri D10).
     */
    public suspend fun openRead(documentId: String): EncriResult<ParcelFileDescriptor> =
        call({ it.openRead(documentId) }, { it.status }, { it.message }) {
            requireNotNull(it.descriptor) { "encri opened a document and sent no descriptor" }
        }

    /**
     * Replaces the document's content with what [writer] writes, and answers once encri has saved it
     * (L11). Writes go over a pipe in 64 KiB pieces, so the stream is sequential: it does not seek.
     *
     * **A write that does not finish is never saved.** If [writer] throws, or the coroutine is cancelled,
     * the write is abandoned and the document is left as it was.
     */
    @Suppress("TooGenericExceptionCaught") // Whatever stops the writer, the write is abandoned.
    public suspend fun write(
        documentId: String,
        writer: suspend (OutputStream) -> Unit,
    ): EncriResult<EncriDocument> =
        withContext(io) {
            val finished = CompletableDeferred<EntryResult?>()
            val told =
                object : IWriteListener.Stub() {
                    override fun onWriteFinished(result: EntryResult?) {
                        finished.complete(result)
                    }
                }
            val opened =
                when (val result = descriptor { it.openWrite(documentId, told) }) {
                    is EncriResult.Ok -> result.value
                    is EncriResult.Failed -> return@withContext result
                }
            val stream = BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(opened), PIECE)
            try {
                writer(stream)
                stream.close()
            } catch (cancelled: CancellationException) {
                // Closed with an error, not closed: encri saves nothing a writer did not finish.
                runCatching { opened.closeWithError(cancelled.message ?: "the write was cancelled") }
                throw cancelled
            } catch (stopped: Throwable) {
                runCatching { opened.closeWithError(stopped.message ?: stopped.javaClass.simpleName) }
                return@withContext refusedOrStopped(finished, stopped)
            }
            entryOf(awaiting(finished))
        }

    /**
     * Why a write stopped. A writer that failed because encri refused the write and closed its end of
     * the pipe — a full disk, the vault locking — is told encri's reason, which arrives on the listener
     * a moment after the pipe breaks; anything else is the writer's own failure.
     */
    private suspend fun refusedOrStopped(
        finished: CompletableDeferred<EntryResult?>,
        stopped: Throwable,
    ): EncriResult<EncriDocument> {
        val told = if (stopped is IOException) withTimeoutOrNull(REASON_WAIT_MILLIS) { finished.await() } else null
        return if (told != null && told.status != IEncriService.STATUS_OK) {
            failed(told.status, told.message)
        } else {
            EncriResult.Failed(Failure.CANCELLED, stopped.message ?: stopped.javaClass.simpleName)
        }
    }

    /** A new empty document in [parentId]. */
    public suspend fun createFile(
        parentId: String,
        name: String,
        mimeType: String,
        onNameTaken: NameConflict = NameConflict.REFUSE,
    ): EncriResult<EncriDocument> = entry { it.create(parentId, name, mimeType, flagsOf(onNameTaken)) }

    public suspend fun createFolder(
        parentId: String,
        name: String,
        onNameTaken: NameConflict = NameConflict.REFUSE,
    ): EncriResult<EncriDocument> =
        entry {
            it.create(parentId, name, IEncriService.MIME_TYPE_FOLDER, flagsOf(onNameTaken))
        }

    public suspend fun rename(
        documentId: String,
        name: String,
        onNameTaken: NameConflict = NameConflict.REFUSE,
    ): EncriResult<EncriDocument> = entry { it.rename(documentId, name, flagsOf(onNameTaken)) }

    /** Moves a document or folder into [parentId]. A folder cannot be moved into itself. */
    public suspend fun move(
        documentId: String,
        parentId: String,
        onNameTaken: NameConflict = NameConflict.REFUSE,
    ): EncriResult<EncriDocument> = entry { it.move(documentId, parentId, flagsOf(onNameTaken)) }

    /** Deletes a document, or a folder and everything in it. */
    public suspend fun delete(documentId: String): EncriResult<Unit> =
        call({ it.delete(documentId) }, { it.status }, { it.message }) { }

    /** Sets a document's times; a null one is left as it is. A file manager copying a file in keeps its dates. */
    public suspend fun setTimes(
        documentId: String,
        createdMillis: Long? = null,
        modifiedMillis: Long? = null,
    ): EncriResult<EncriDocument> = entry { it.setTimes(documentId, createdMillis ?: KEEP, modifiedMillis ?: KEEP) }

    /**
     * A preview no larger than [width] by [height], encoded as PNG or JPEG, made in encri (encri D50,
     * D53). Cancelling the coroutine cancels it in encri too. A type encri cannot render is
     * [Failure.UNSUPPORTED].
     */
    @Suppress("ReturnCount") // One per way a preview does not arrive.
    public suspend fun thumbnail(
        documentId: String,
        width: Int,
        height: Int,
    ): EncriResult<ByteArray> =
        withContext(io) {
            val delivered = CompletableDeferred<DescriptorResult?>()
            val told =
                object : IThumbnailListener.Stub() {
                    override fun onThumbnail(
                        requestId: Int,
                        result: DescriptorResult?,
                    ) {
                        if (!delivered.complete(result)) result?.descriptor?.close()
                    }
                }
            val ticket =
                when (
                    val result =
                        call({ it.requestThumbnail(documentId, width, height, told) }, { it.status }, { it.message }) {
                            it.requestId
                        }
                ) {
                    is EncriResult.Ok -> result.value
                    is EncriResult.Failed -> return@withContext result
                }
            val waited =
                try {
                    awaiting(delivered)
                } catch (cancelled: CancellationException) {
                    if (isOpen) runCatching { remote.cancelThumbnail(ticket) }
                    throw cancelled
                }
            if (waited is EncriResult.Failed) return@withContext waited
            val answer =
                waited as DescriptorResult?
                    ?: return@withContext EncriResult.Failed(Failure.FAILED, "encri sent no preview")
            val descriptor = answer.descriptor
            if (answer.status != IEncriService.STATUS_OK || descriptor == null) {
                descriptor?.close()
                return@withContext EncriResult.Failed(failureOf(answer.status), answer.message)
            }
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                EncriResult.Ok(input.readBytes())
            }
        }

    /**
     * `content://` URIs another app can open the documents through, granted to this app for reading
     * (encri D117). Pass one on with `FLAG_GRANT_READ_URI_PERMISSION`, to *open with* or to the share
     * sheet. They stop working when the vault locks, this app is revoked, or [revokeHandoffs].
     */
    public suspend fun handOff(documentIds: List<String>): EncriResult<List<Uri>> =
        call({ it.handOff(documentIds.toTypedArray()) }, { it.status }, { it.message }) { answer ->
            answer.uris.orEmpty().map(Uri::parse)
        }

    public suspend fun revokeHandoffs(uris: List<Uri>): EncriResult<Unit> =
        call({ it.revokeHandoffs(uris.map(Uri::toString).toTypedArray()) }, { it.status }, { it.message }) { }

    public suspend fun space(): EncriResult<VaultSpace> =
        call({ it.space() }, { it.status }, { it.message }) { VaultSpace(it.usableBytes, it.totalBytes) }

    /** Locks the vault, for every app and for encri. */
    public suspend fun lock(): EncriResult<Unit> = call({ it.lock() }, { it.status }, { it.message }) { }

    /** Ends the session. Its descriptors, writes and handoffs end with it. */
    override fun close() {
        if (!ended.compareAndSet(false, true)) return
        onClosed(this)
        runCatching { remote.end() }
        runCatching { remote.asBinder().unlinkToDeath(death, 0) }
        waiting.toList().forEach { it.cancel(CancellationException(CLOSED)) }
    }

    // ---- How a request is made -----------------------------------------------------------------

    private suspend fun entry(ask: (IEncriSession) -> EntryResult?): EncriResult<EncriDocument> =
        withContext(io) { entryOf(remoteOrNull(ask)) }

    @Suppress("ReturnCount") // One per way an answer is not a document.
    private fun entryOf(answer: Any?): EncriResult<EncriDocument> {
        if (answer is EncriResult.Failed) return answer
        val result = answer as? EntryResult ?: return EncriResult.Failed(Failure.FAILED, "encri did not answer")
        if (result.status != IEncriService.STATUS_OK) return failed(result.status, result.message)
        val entry = result.entry ?: return EncriResult.Failed(Failure.FAILED, "encri answered with no document")
        return EncriResult.Ok(documentOf(entry))
    }

    private suspend fun descriptor(ask: (IEncriSession) -> DescriptorResult?): EncriResult<ParcelFileDescriptor> =
        call(ask, { it.status }, { it.message }) { requireNotNull(it.descriptor) { "encri sent no descriptor" } }

    /**
     * One request: asked off the caller's thread, and answered as [transform] makes of a success or as the
     * [Failure] its status names. A null answer is an older encri that does not have the method (L2).
     */
    @Suppress("ReturnCount", "SwallowedException") // One per way a request ends; a hollow answer is one.
    private suspend fun <A, T> call(
        ask: (IEncriSession) -> A?,
        status: (A) -> Int,
        message: (A) -> String?,
        transform: (A) -> T,
    ): EncriResult<T> =
        withContext(io) {
            val answer = remoteOrNull(ask)
            if (answer is EncriResult.Failed) return@withContext answer
            @Suppress("UNCHECKED_CAST")
            val typed = answer as A? ?: return@withContext EncriResult.Failed(Failure.FAILED, "encri did not answer")
            val code = status(typed)
            if (code != IEncriService.STATUS_OK) return@withContext failed(code, message(typed))
            // A success missing what it should carry is encri failing, and is answered as one: the
            // transforms `require` what they read, and nothing they throw may reach the app (L5).
            try {
                EncriResult.Ok(transform(typed))
            } catch (hollow: IllegalArgumentException) {
                EncriResult.Failed(Failure.FAILED, hollow.message)
            }
        }

    /** Asks encri, turning a process that went away into [Failure.DISCONNECTED]. */
    @Suppress("SwallowedException") // A process that is gone is the answer.
    private fun <A> remoteOrNull(ask: (IEncriSession) -> A?): Any? {
        if (ended.get()) return EncriResult.Failed(Failure.ENDED, "this session has ended")
        return try {
            ask(remote)
        } catch (gone: RemoteException) {
            ended.set(true)
            EncriResult.Failed(Failure.DISCONNECTED, GONE)
        }
    }

    private fun failed(
        status: Int,
        message: String?,
    ): EncriResult.Failed {
        val failure = failureOf(status)
        if (failure == Failure.ENDED) ended.set(true)
        return EncriResult.Failed(failure, message)
    }

    /**
     * Waits on a listener's answer: the answer, or [Failure.DISCONNECTED] when encri's process went away
     * or the session was closed under the wait. A caller that cancels is cancelled, as ever.
     */
    private suspend fun awaiting(answer: CompletableDeferred<*>): Any? {
        waiting += answer
        if (ended.get()) answer.cancel(CancellationException(GONE))
        return try {
            answer.await()
        } catch (cancelled: CancellationException) {
            // The session's, not the caller's: it went away under the wait.
            if (answer.isCancelled && ended.get()) {
                EncriResult.Failed(Failure.DISCONNECTED, GONE)
            } else {
                throw cancelled
            }
        } finally {
            waiting -= answer
        }
    }

    internal companion object {
        /**
         * A session over [remote]. A factory rather than an internal constructor, and `@JvmSynthetic`,
         * because an internal constructor is public in bytecode and would name the contract to any Java
         * caller (L5); a synthetic method is one neither language can call from outside this module.
         */
        @JvmSynthetic
        internal fun open(
            remote: IEncriSession,
            grant: EncriGrant,
            listener: SessionListener,
            io: CoroutineDispatcher,
            onClosed: (EncriSession) -> Unit = {},
        ): EncriSession = EncriSession(remote, grant, listener, io, onClosed)

        private const val DEFAULT_PAGE = 200

        /** How much a write hands encri at once: D2's block, and what a pipe's buffer holds. */
        private const val PIECE = 64 * 1024

        /** How long a broken write waits for encri's reason: it is sent as the pipe breaks. */
        private const val REASON_WAIT_MILLIS = 2_000L

        /** setTimes' "leave it as it is" on the wire (L11). */
        private const val KEEP = -1L

        private const val GONE = "encri's process went away"
        private const val CLOSED = "the session was closed"

        private fun flagsOf(onNameTaken: NameConflict): Int =
            if (onNameTaken == NameConflict.PICK_FREE_NAME) IEncriService.FLAG_FREE_NAME else 0
    }
}
