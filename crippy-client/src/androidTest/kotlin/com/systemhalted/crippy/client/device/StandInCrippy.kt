package com.systemhalted.crippy.client.device

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.systemhalted.crippy.api.DescriptorResult
import com.systemhalted.crippy.api.Entry
import com.systemhalted.crippy.api.EntryResult
import com.systemhalted.crippy.api.Grant
import com.systemhalted.crippy.api.GrantResult
import com.systemhalted.crippy.api.HandoffResult
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.api.ICrippySession
import com.systemhalted.crippy.api.IThumbnailListener
import com.systemhalted.crippy.api.IVaultListener
import com.systemhalted.crippy.api.IWriteListener
import com.systemhalted.crippy.api.Outcome
import com.systemhalted.crippy.api.PageResult
import com.systemhalted.crippy.api.SessionResult
import com.systemhalted.crippy.api.SpaceResult
import com.systemhalted.crippy.api.ThumbnailTicket
import com.systemhalted.crippy.api.TotalsResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * crippy's service as far as the wire goes, in a process of its own (L13), so a session crosses a real
 * Binder: a BinderProxy, parcels two processes read and write, a pipe, a descriptor opened in another
 * process, oneway calls from it, and a process a test can kill.
 *
 * **It is not crippy.** It keeps a few documents in memory and grants everything: what is under test is
 * the client and the wire, not crippy's policy, which crippy's own tests hold (crippy D120). Where crippy
 * answers something a client must handle — a write refused part way, a preview that never comes, a page
 * cap — the stand-in does it on purpose, for a document named for it.
 */
class StandInCrippy : Service() {
    private val vault = StandInVault()

    private val crippy =
        object : ICrippyService.Stub() {
            override fun contractVersion(): Int = ICrippyService.VERSION

            override fun openSession(listener: IVaultListener?): SessionResult =
                SessionResult().apply {
                    status = ICrippyService.STATUS_OK
                    session = StandInSession(vault, listener, cacheDir)
                    grant = vault.grant()
                }
        }

    override fun onBind(intent: Intent): IBinder = crippy

    companion object {
        const val ROOT = "root"

        /** A write to this document is refused once this much has arrived, as crippy refuses a full disk. */
        const val REFUSED_PART_WAY = "refused part way.bin"
        const val REFUSE_AFTER = 256 * 1024

        /** A preview of this document is queued and never sent, as crippy's is while it decodes. */
        const val NEVER_PREVIEWED = "never previewed.png"

        /** crippy caps a page; the stand-in caps it low so that paging crosses the Binder more than once. */
        const val PAGE_CAP = 3

        const val ALL_RIGHTS =
            ICrippyService.RIGHT_READ or ICrippyService.RIGHT_WRITE or ICrippyService.RIGHT_CREATE or
                ICrippyService.RIGHT_DELETE or ICrippyService.RIGHT_MOVE
    }
}

/** The stand-in's documents, in memory, for as long as its process lives. */
internal class StandInVault {
    private class Doc(
        val id: String,
        val parent: String?,
        val name: String,
        val mimeType: String,
        @Volatile var bytes: ByteArray = ByteArray(0),
    ) {
        val folder: Boolean get() = mimeType == ICrippyService.MIME_TYPE_FOLDER
    }

    private val docs = mutableMapOf(StandInCrippy.ROOT to Doc(StandInCrippy.ROOT, null, "stand-in", FOLDER))
    private val ids = AtomicInteger()

    @Synchronized
    fun entry(id: String?): Entry? = docs[id]?.let(::entryOf)

    @Synchronized
    fun children(folderId: String?): List<Entry>? {
        val folder = docs[folderId]?.takeIf { it.folder } ?: return null
        return docs.values
            .filter { it.parent == folder.id }
            .sortedBy { it.name }
            .map(::entryOf)
    }

    @Synchronized
    fun bytesOf(id: String?): ByteArray? = docs[id]?.takeUnless { it.folder }?.bytes

    @Synchronized
    fun nameOf(id: String?): String? = docs[id]?.name

    /** A new document, or null when the parent is not a folder; a taken name is [ICrippyService.STATUS_NAME_TAKEN]. */
    @Synchronized
    fun create(
        parentId: String?,
        name: String,
        mimeType: String,
        freeName: Boolean,
    ): Pair<Int, Entry?> {
        val parent = docs[parentId]?.takeIf { it.folder } ?: return ICrippyService.STATUS_NOT_FOUND to null
        val taken =
            docs.values
                .filter { it.parent == parent.id }
                .map { it.name }
                .toSet()
        val chosen =
            when {
                name !in taken -> name
                freeName -> generateSequence(1) { it + 1 }.map { "$name ($it)" }.first { it !in taken }
                else -> return ICrippyService.STATUS_NAME_TAKEN to null
            }
        val doc = Doc("doc-${ids.incrementAndGet()}", parent.id, chosen, mimeType)
        docs[doc.id] = doc
        return ICrippyService.STATUS_OK to entryOf(doc)
    }

    @Synchronized
    fun replace(
        id: String?,
        bytes: ByteArray,
    ): Entry? {
        val doc = docs[id]?.takeUnless { it.folder } ?: return null
        doc.bytes = bytes
        return entryOf(doc)
    }

    @Synchronized
    fun delete(id: String?): Boolean {
        val doc = docs[id]?.takeIf { it.id != StandInCrippy.ROOT } ?: return false
        docs.values
            .filter { it.parent == doc.id }
            .map { it.id }
            .forEach(::delete)
        docs.remove(doc.id)
        return true
    }

    @Synchronized
    fun totals(folderId: String?): TotalsResult? {
        val folder = docs[folderId]?.takeIf { it.folder } ?: return null
        val under = mutableListOf<Doc>()
        var frontier = listOf(folder.id)
        while (frontier.isNotEmpty()) {
            val next = docs.values.filter { it.parent in frontier }
            under += next
            frontier = next.map { it.id }
        }
        return TotalsResult().apply {
            status = ICrippyService.STATUS_OK
            bytes = under.sumOf { it.bytes.size.toLong() }
            documents = under.count { !it.folder }
            folders = under.count { it.folder }
        }
    }

    @Synchronized
    fun grant(): Grant =
        Grant().apply {
            rights = StandInCrippy.ALL_RIGHTS
            scope = entryOf(docs.getValue(StandInCrippy.ROOT))
        }

    private fun entryOf(doc: Doc): Entry =
        Entry().apply {
            documentId = doc.id
            parentId = doc.parent
            name = doc.name
            mimeType = doc.mimeType
            folder = doc.folder
            sizeBytes = doc.bytes.size.toLong()
            createdUtcMillis = CREATED
            modifiedUtcMillis = CREATED
            childCount = if (doc.folder) docs.values.count { it.parent == doc.id } else -1
            thumbnailable = doc.mimeType == "image/png"
        }

    private companion object {
        const val FOLDER = ICrippyService.MIME_TYPE_FOLDER
        const val CREATED = 1_700_000_000_000L
    }
}

/** A session over [vault]: real enough to cross the Binder, and refusing what the tests do not need. */
@Suppress("TooManyFunctions") // One per method the contract has.
internal class StandInSession(
    private val vault: StandInVault,
    private val listener: IVaultListener?,
    private val scratch: File,
) : ICrippySession.Stub() {
    private val work = Executors.newCachedThreadPool()
    private val tickets = AtomicInteger()

    override fun grant(): GrantResult =
        GrantResult().apply {
            status = ICrippyService.STATUS_OK
            grant = vault.grant()
        }

    override fun stat(documentId: String?): EntryResult = entryResult(vault.entry(documentId))

    override fun list(
        folderId: String?,
        after: String?,
        limit: Int,
    ): PageResult {
        val children =
            vault.children(folderId) ?: return PageResult().apply { status = ICrippyService.STATUS_NOT_FOUND }
        val rest = children.filter { after == null || it.name > after }
        val page = rest.take(limit.coerceIn(1, StandInCrippy.PAGE_CAP))
        return PageResult().apply {
            status = ICrippyService.STATUS_OK
            entries = page.toTypedArray()
            next = if (rest.size > page.size) page.last().name else null
        }
    }

    override fun measure(folderId: String?): TotalsResult =
        vault.totals(folderId) ?: TotalsResult().apply { status = ICrippyService.STATUS_NOT_FOUND }

    /** A file in the stand-in's own cache, unlinked once it is open: a descriptor from another process that seeks. */
    override fun openRead(documentId: String?): DescriptorResult {
        val bytes =
            vault.bytesOf(documentId) ?: return DescriptorResult().apply { status = ICrippyService.STATUS_NOT_FOUND }
        val file = File.createTempFile("read", ".bin", scratch)
        file.writeBytes(bytes)
        val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        file.delete()
        return DescriptorResult().apply {
            status = ICrippyService.STATUS_OK
            descriptor = opened
            length = bytes.size.toLong()
        }
    }

    /**
     * The write end of a reliable pipe, as crippy hands one out (L11). What arrives is saved only if the
     * writer closed its end cleanly; [StandInCrippy.REFUSED_PART_WAY] is refused once enough has arrived,
     * by closing the read end and telling the listener why, in that order, as crippy does.
     */
    override fun openWrite(
        documentId: String?,
        listener: IWriteListener?,
    ): DescriptorResult {
        if (vault.bytesOf(documentId) ==
            null
        ) {
            return DescriptorResult().apply { status = ICrippyService.STATUS_NOT_FOUND }
        }
        val (readEnd, writeEnd) = ParcelFileDescriptor.createReliablePipe()
        val refuse = vault.nameOf(documentId) == StandInCrippy.REFUSED_PART_WAY
        work.execute { receive(documentId, readEnd, refuse, listener) }
        return DescriptorResult().apply {
            status = ICrippyService.STATUS_OK
            descriptor = writeEnd
        }
    }

    private fun receive(
        documentId: String?,
        readEnd: ParcelFileDescriptor,
        refuse: Boolean,
        listener: IWriteListener?,
    ) {
        val content = ByteArrayOutputStream()
        // Not `use`: the stream does not own the descriptor, and the pipe's own close is what tells the
        // writer how it ended. Closing it twice would be fdsan's business on a newer platform.
        val input = FileInputStream(readEnd.fileDescriptor)
        val buffer = ByteArray(BUFFER)
        val answer =
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    content.write(buffer, 0, read)
                    if (refuse && content.size() >= StandInCrippy.REFUSE_AFTER) {
                        readEnd.close()
                        listener?.onWriteFinished(result(ICrippyService.STATUS_NO_SPACE, "the stand-in is full"))
                        return
                    }
                }
                // The writer closed: cleanly, or with an error or by dying, which checkError tells apart.
                readEnd.checkError()
                entryResult(vault.replace(documentId, content.toByteArray()))
            } catch (stopped: IOException) {
                result(ICrippyService.STATUS_CANCELLED, stopped.message)
            }
        runCatching { readEnd.close() }
        listener?.onWriteFinished(answer)
    }

    override fun create(
        parentId: String?,
        name: String?,
        mimeType: String?,
        flags: Int,
    ): EntryResult {
        val (status, entry) =
            vault.create(parentId, name.orEmpty(), mimeType.orEmpty(), flags and ICrippyService.FLAG_FREE_NAME != 0)
        return if (entry != null) entryResult(entry) else result(status, null)
    }

    override fun rename(
        documentId: String?,
        name: String?,
        flags: Int,
    ): EntryResult = unsupported()

    override fun move(
        documentId: String?,
        parentId: String?,
        flags: Int,
    ): EntryResult = unsupported()

    override fun delete(documentId: String?): Outcome =
        Outcome().apply {
            status =
                if (vault.delete(documentId)) ICrippyService.STATUS_OK else ICrippyService.STATUS_NOT_FOUND
        }

    override fun setTimes(
        documentId: String?,
        createdUtcMillis: Long,
        modifiedUtcMillis: Long,
    ): EntryResult = unsupported()

    /** A ticket now and a PNG on a pipe a moment later, oneway; [StandInCrippy.NEVER_PREVIEWED] never comes. */
    override fun requestThumbnail(
        documentId: String?,
        width: Int,
        height: Int,
        listener: IThumbnailListener?,
    ): ThumbnailTicket {
        val name =
            vault.nameOf(documentId) ?: return ThumbnailTicket().apply { status = ICrippyService.STATUS_NOT_FOUND }
        val ticket = tickets.incrementAndGet()
        if (name !=
            StandInCrippy.NEVER_PREVIEWED
        ) {
            work.execute { listener?.onThumbnail(ticket, preview(width, height)) }
        }
        return ThumbnailTicket().apply {
            status = ICrippyService.STATUS_OK
            requestId = ticket
        }
    }

    /** Oneway, so nothing a test in another process could read back; the unit tests hold that it is sent. */
    override fun cancelThumbnail(requestId: Int) = Unit

    override fun handOff(documentIds: Array<out String>?): HandoffResult =
        HandoffResult().apply { status = ICrippyService.STATUS_UNSUPPORTED }

    override fun revokeHandoffs(uris: Array<out String>?): Outcome =
        Outcome().apply {
            status =
                ICrippyService.STATUS_UNSUPPORTED
        }

    override fun space(): SpaceResult =
        SpaceResult().apply {
            status = ICrippyService.STATUS_OK
            usableBytes = scratch.usableSpace
            totalBytes = scratch.totalSpace
        }

    /** Tells the session's listener, oneway, as crippy tells every session when the vault locks. */
    override fun lock(): Outcome {
        listener?.onStateChanged(ICrippyService.STATE_LOCKED)
        return Outcome().apply { status = ICrippyService.STATUS_OK }
    }

    override fun end() {
        work.shutdown()
    }

    private fun preview(
        width: Int,
        height: Int,
    ): DescriptorResult {
        val png =
            ByteArrayOutputStream()
                .also { out ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.MAGENTA)
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                    bitmap.recycle()
                }.toByteArray()
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        work.execute { ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { it.write(png) } }
        return DescriptorResult().apply {
            status = ICrippyService.STATUS_OK
            descriptor = readEnd
            length = png.size.toLong()
        }
    }

    private fun entryResult(entry: Entry?): EntryResult =
        if (entry != null) {
            EntryResult().apply {
                status = ICrippyService.STATUS_OK
                this.entry = entry
            }
        } else {
            result(ICrippyService.STATUS_NOT_FOUND, null)
        }

    private fun unsupported(): EntryResult = result(ICrippyService.STATUS_UNSUPPORTED, "the stand-in does not do this")

    private fun result(
        status: Int,
        message: String?,
    ): EntryResult =
        EntryResult().apply {
            this.status = status
            this.message = message
        }

    private companion object {
        const val BUFFER = 64 * 1024
        const val PNG_QUALITY = 100
    }
}
