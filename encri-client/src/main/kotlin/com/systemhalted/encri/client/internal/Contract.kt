package com.systemhalted.encri.client.internal

import com.systemhalted.encri.api.Entry
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.client.EncriDocument
import com.systemhalted.encri.client.Failure
import com.systemhalted.encri.client.Right

/**
 * The contract's vocabulary, where the client reads it (L5, L11). The constants are the contract's own,
 * so they cannot drift; they are named here so nothing public in the client names the contract.
 */
internal object Contract {
    const val PACKAGE: String = IEncriService.PACKAGE
    const val ACTION_BIND: String = IEncriService.ACTION_BIND
    const val ACTION_AUTHORIZE: String = IEncriService.ACTION_AUTHORIZE
    const val ACTION_UNLOCK: String = IEncriService.ACTION_UNLOCK
    const val ACTION_SAVE_BACKUP_FILE: String = IEncriService.ACTION_SAVE_BACKUP_FILE
    const val ACTION_RESTORE: String = IEncriService.ACTION_RESTORE
    const val EXTRA_RIGHTS: String = IEncriService.EXTRA_RIGHTS

    /** The oldest contract this client can work with. Every method it calls is in version 1 (L11). */
    const val OLDEST_SERVICE: Int = 1
}

private val RIGHT_BITS =
    mapOf(
        Right.READ to IEncriService.RIGHT_READ,
        Right.WRITE to IEncriService.RIGHT_WRITE,
        Right.CREATE to IEncriService.RIGHT_CREATE,
        Right.DELETE to IEncriService.RIGHT_DELETE,
        Right.MOVE to IEncriService.RIGHT_MOVE,
    )

internal fun bitsOf(rights: Set<Right>): Int = rights.fold(0) { bits, right -> bits or RIGHT_BITS.getValue(right) }

/** The rights named by [bits]; a bit this client does not know is dropped rather than guessed at. */
internal fun rightsOf(bits: Int): Set<Right> = RIGHT_BITS.filterValues { bits and it != 0 }.keys

/** What a status the contract answered means here. 0 is an older encri that did not answer (L2). */
internal fun failureOf(status: Int): Failure =
    when (status) {
        IEncriService.STATUS_LOCKED -> Failure.LOCKED
        IEncriService.STATUS_NOT_AUTHORIZED -> Failure.NOT_AUTHORIZED
        IEncriService.STATUS_ACCESS_OFF -> Failure.ACCESS_OFF
        IEncriService.STATUS_NOT_FOUND -> Failure.NOT_FOUND
        IEncriService.STATUS_NAME_TAKEN -> Failure.NAME_TAKEN
        IEncriService.STATUS_INVALID -> Failure.INVALID
        IEncriService.STATUS_NO_SPACE -> Failure.NO_SPACE
        IEncriService.STATUS_UNSUPPORTED -> Failure.UNSUPPORTED
        IEncriService.STATUS_ENDED -> Failure.ENDED
        IEncriService.STATUS_CANCELLED -> Failure.CANCELLED
        else -> Failure.FAILED
    }

internal fun documentOf(entry: Entry): EncriDocument =
    EncriDocument(
        id = entry.documentId,
        parentId = entry.parentId,
        name = entry.name,
        mimeType = entry.mimeType,
        isFolder = entry.folder,
        sizeBytes = entry.sizeBytes,
        createdMillis = entry.createdUtcMillis,
        modifiedMillis = entry.modifiedUtcMillis,
        childCount = entry.childCount.takeIf { entry.folder && it >= 0 },
        thumbnailable = entry.thumbnailable,
    )
