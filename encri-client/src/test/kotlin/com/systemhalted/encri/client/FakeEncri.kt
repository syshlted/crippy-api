package com.systemhalted.encri.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import com.systemhalted.encri.api.DescriptorResult
import com.systemhalted.encri.api.Entry
import com.systemhalted.encri.api.EntryResult
import com.systemhalted.encri.api.Grant
import com.systemhalted.encri.api.GrantResult
import com.systemhalted.encri.api.HandoffResult
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.api.IEncriSession
import com.systemhalted.encri.api.IThumbnailListener
import com.systemhalted.encri.api.IVaultListener
import com.systemhalted.encri.api.IWriteListener
import com.systemhalted.encri.api.Outcome
import com.systemhalted.encri.api.PageResult
import com.systemhalted.encri.api.SessionResult
import com.systemhalted.encri.api.SpaceResult
import com.systemhalted.encri.api.ThumbnailTicket
import com.systemhalted.encri.api.TotalsResult
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

/** Installs a package named encri, signed with [signature], into Robolectric's package manager. */
internal fun installEncri(
    context: Context,
    signature: Signature,
    rotatedFrom: Signature? = null,
) {
    val info =
        PackageInfo().apply {
            packageName = IEncriService.PACKAGE
            @Suppress("DEPRECATION")
            signatures = arrayOf(signature)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                signingInfo =
                    SigningInfo().also {
                        Shadow.extract<ShadowSigningInfo>(it).setSignatures(arrayOf(signature))
                        if (rotatedFrom != null) {
                            Shadow
                                .extract<ShadowSigningInfo>(
                                    it,
                                ).setPastSigningCertificates(arrayOf(rotatedFrom, signature))
                        }
                    }
            }
        }
    shadowOf(context.packageManager).installPackage(info)
}

/** Answers a bind for encri's action with [service], as encri's process would. */
internal fun serveEncri(service: IEncriService.Stub) {
    val intent = Intent(IEncriService.ACTION_BIND).setPackage(IEncriService.PACKAGE)
    shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindServiceForIntent(
        intent,
        ComponentName(IEncriService.PACKAGE, "com.systemhalted.encri.provider.companion.CompanionService"),
        service,
    )
}

/** encri's service, answering what a test says it does. */
internal class FakeService(
    var version: Int = IEncriService.VERSION,
    var opening: (IVaultListener?) -> SessionResult? = { null },
) : IEncriService.Stub() {
    var listener: IVaultListener? = null

    override fun contractVersion(): Int = version

    override fun openSession(listener: IVaultListener?): SessionResult? {
        this.listener = listener
        return opening(listener)
    }
}

internal fun entry(
    id: String,
    name: String = "notes.txt",
    folder: Boolean = false,
) = Entry().apply {
    documentId = id
    parentId = ROOT
    this.name = name
    mimeType = if (folder) IEncriService.MIME_TYPE_FOLDER else "text/plain"
    this.folder = folder
    sizeBytes = if (folder) 0 else 12
    createdUtcMillis = 1_000
    modifiedUtcMillis = 2_000
    childCount = if (folder) 3 else -1
    thumbnailable = false
}

internal fun opened(session: IEncriSession) =
    SessionResult().apply {
        status = IEncriService.STATUS_OK
        this.session = session
        grant =
            Grant().apply {
                rights = IEncriService.RIGHT_READ or IEncriService.RIGHT_WRITE
                scope = entry(ROOT, "encri vault", folder = true).apply { parentId = null }
            }
    }

internal fun refused(status: Int) = SessionResult().apply { this.status = status }

internal const val ROOT = "00000000000000000000000000000001"

/** A session whose every answer a test sets. Unset answers are null: an older encri (L2). */
@Suppress("TooManyFunctions")
internal open class FakeSession : IEncriSession.Stub() {
    var grant: () -> GrantResult? = { null }
    var stat: (String?) -> EntryResult? = { null }
    var pages: (String?, String?, Int) -> PageResult? = { _, _, _ -> null }
    var totals: (String?) -> TotalsResult? = { null }
    var read: (String?) -> DescriptorResult? = { null }
    var write: (String?, IWriteListener?) -> DescriptorResult? = { _, _ -> null }

    /** What every request that answers a document answers, with the request's name and flags. */
    var changed: (String, Int) -> EntryResult? = { _, _ -> null }
    var outcome: (String) -> Outcome? = { null }
    var handedOff: (List<String>) -> HandoffResult? = { null }
    var spaceLeft: () -> SpaceResult? = { null }
    var thumbnail: (String?, Int, Int, IThumbnailListener?) -> ThumbnailTicket? = { _, _, _, _ -> null }
    val cancelled = mutableListOf<Int>()
    var ended = false

    /** What a request was given, by name, for a test to read back. */
    val asked = mutableListOf<String>()

    /** Whether the binder is dead before the session can watch it. */
    var deadOnArrival = false

    /** The death recipient the session linked, so a test can play encri's process dying. */
    var recipient: IBinder.DeathRecipient? = null

    /** A binder that records what is linked to its death; a local binder never dies. */
    private val watched =
        object : IBinder by this@FakeSession {
            override fun linkToDeath(
                recipient: IBinder.DeathRecipient,
                flags: Int,
            ) {
                if (deadOnArrival) throw RemoteException("encri's process is already gone")
                this@FakeSession.recipient = recipient
            }

            override fun unlinkToDeath(
                recipient: IBinder.DeathRecipient,
                flags: Int,
            ): Boolean {
                this@FakeSession.recipient = null
                return true
            }
        }

    override fun asBinder(): IBinder = watched

    override fun grant(): GrantResult? = grant.invoke()

    override fun stat(documentId: String?): EntryResult? = stat.invoke(documentId)

    override fun list(
        folderId: String?,
        after: String?,
        limit: Int,
    ): PageResult? = pages(folderId, after, limit)

    override fun measure(folderId: String?): TotalsResult? = totals(folderId)

    override fun openRead(documentId: String?): DescriptorResult? = read(documentId)

    override fun openWrite(
        documentId: String?,
        listener: IWriteListener?,
    ): DescriptorResult? = write(documentId, listener)

    override fun create(
        parentId: String?,
        name: String?,
        mimeType: String?,
        flags: Int,
    ): EntryResult? {
        asked += "create $parentId $name $mimeType"
        return changed("create", flags)
    }

    override fun rename(
        documentId: String?,
        name: String?,
        flags: Int,
    ): EntryResult? {
        asked += "rename $documentId $name"
        return changed("rename", flags)
    }

    override fun move(
        documentId: String?,
        parentId: String?,
        flags: Int,
    ): EntryResult? {
        asked += "move $documentId $parentId"
        return changed("move", flags)
    }

    override fun delete(documentId: String?): Outcome? = outcome("delete $documentId")

    override fun setTimes(
        documentId: String?,
        createdUtcMillis: Long,
        modifiedUtcMillis: Long,
    ): EntryResult? {
        asked += "setTimes $documentId $createdUtcMillis $modifiedUtcMillis"
        return changed("setTimes", 0)
    }

    override fun requestThumbnail(
        documentId: String?,
        width: Int,
        height: Int,
        listener: IThumbnailListener?,
    ): ThumbnailTicket? = thumbnail(documentId, width, height, listener)

    override fun cancelThumbnail(requestId: Int) {
        cancelled += requestId
    }

    override fun handOff(documentIds: Array<out String>?): HandoffResult? = handedOff(documentIds.orEmpty().toList())

    override fun revokeHandoffs(uris: Array<out String>?): Outcome? = outcome("revoke ${uris.orEmpty().joinToString()}")

    override fun space(): SpaceResult? = spaceLeft()

    override fun lock(): Outcome? = outcome("lock")

    override fun end() {
        ended = true
    }
}

internal fun ok(entry: Entry) =
    EntryResult().apply {
        status = IEncriService.STATUS_OK
        this.entry = entry
    }

internal fun outcomeOf(status: Int) = Outcome().apply { this.status = status }
