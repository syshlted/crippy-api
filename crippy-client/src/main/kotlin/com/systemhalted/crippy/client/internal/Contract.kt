package com.systemhalted.crippy.client.internal

import com.systemhalted.crippy.api.Entry
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.CrippyDocument
import com.systemhalted.crippy.client.Failure
import com.systemhalted.crippy.client.Right

/**
 * The contract's vocabulary, where the client reads it (L5, L11). The constants are the contract's own,
 * so they cannot drift; they are named here so nothing public in the client names the contract.
 */
internal object Contract {
    const val PACKAGE: String = ICrippyService.PACKAGE
    const val ACTION_BIND: String = ICrippyService.ACTION_BIND
    const val ACTION_AUTHORIZE: String = ICrippyService.ACTION_AUTHORIZE
    const val ACTION_UNLOCK: String = ICrippyService.ACTION_UNLOCK
    const val EXTRA_RIGHTS: String = ICrippyService.EXTRA_RIGHTS

    /** The oldest contract this client can work with. Every method it calls is in version 1 (L11). */
    const val OLDEST_SERVICE: Int = 1
}

private val RIGHT_BITS =
    mapOf(
        Right.READ to ICrippyService.RIGHT_READ,
        Right.WRITE to ICrippyService.RIGHT_WRITE,
        Right.CREATE to ICrippyService.RIGHT_CREATE,
        Right.DELETE to ICrippyService.RIGHT_DELETE,
        Right.MOVE to ICrippyService.RIGHT_MOVE,
    )

internal fun bitsOf(rights: Set<Right>): Int = rights.fold(0) { bits, right -> bits or RIGHT_BITS.getValue(right) }

/** The rights named by [bits]; a bit this client does not know is dropped rather than guessed at. */
internal fun rightsOf(bits: Int): Set<Right> = RIGHT_BITS.filterValues { bits and it != 0 }.keys

/** What a status the contract answered means here. 0 is an older crippy that did not answer (L2). */
internal fun failureOf(status: Int): Failure =
    when (status) {
        ICrippyService.STATUS_LOCKED -> Failure.LOCKED
        ICrippyService.STATUS_NOT_AUTHORIZED -> Failure.NOT_AUTHORIZED
        ICrippyService.STATUS_ACCESS_OFF -> Failure.ACCESS_OFF
        ICrippyService.STATUS_NOT_FOUND -> Failure.NOT_FOUND
        ICrippyService.STATUS_NAME_TAKEN -> Failure.NAME_TAKEN
        ICrippyService.STATUS_INVALID -> Failure.INVALID
        ICrippyService.STATUS_NO_SPACE -> Failure.NO_SPACE
        ICrippyService.STATUS_UNSUPPORTED -> Failure.UNSUPPORTED
        ICrippyService.STATUS_ENDED -> Failure.ENDED
        ICrippyService.STATUS_CANCELLED -> Failure.CANCELLED
        else -> Failure.FAILED
    }

internal fun documentOf(entry: Entry): CrippyDocument =
    CrippyDocument(
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
