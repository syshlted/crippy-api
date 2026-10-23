package com.systemhalted.crippy.client

/** What an app may do in the vault, as the user allowed it on crippy's own screen (crippy D114). */
public enum class Right {
    /** List, look up, read, preview and hand off documents. Every grant carries it. */
    READ,

    /** Replace a document's content, rename it and set its times. */
    WRITE,

    /** Create documents and folders. */
    CREATE,

    /** Delete documents and folders, a folder with everything in it. */
    DELETE,

    /** Move documents and folders to another folder. */
    MOVE,
}

/**
 * A document or a folder in the vault.
 *
 * [id] is stable for the life of the document and never reused (crippy D15). [parentId] is null for the
 * folder the app's grant is rooted at: nothing above it is shown.
 */
public data class CrippyDocument(
    val id: String,
    val parentId: String?,
    val name: String,
    val mimeType: String,
    val isFolder: Boolean,
    /** A document's length in bytes; 0 for a folder. */
    val sizeBytes: Long,
    val createdMillis: Long,
    val modifiedMillis: Long,
    /** How many documents and folders are directly in a folder; null for a document. */
    val childCount: Int?,
    /** Whether [CrippySession.thumbnail] can make a preview of it. */
    val thumbnailable: Boolean,
)

/** What the user allowed this app: its rights, and the folder its access is rooted at. */
public data class CrippyGrant(
    val rights: Set<Right>,
    /** The whole vault's root, or the one folder the user chose. */
    val scope: CrippyDocument,
)

/** One page of a folder's children. Pass [next] to get the page after it; null means this is the last. */
public data class CrippyPage(
    val documents: List<CrippyDocument>,
    val next: String?,
)

/** Everything under a folder, counted: at any depth, not counting the folder itself. */
public data class FolderTotals(
    val bytes: Long,
    val documents: Int,
    val folders: Int,
)

/** How much more the vault can hold, after crippy's padding and framing, and the volume's size. */
public data class VaultSpace(
    val usableBytes: Long,
    val totalBytes: Long,
)

/** What to do when a name is already taken in the folder a document is going into. */
public enum class NameConflict {
    /** Answer [Failure.NAME_TAKEN] and change nothing. */
    REFUSE,

    /** Use the first free name instead: `notes (1).txt`. */
    PICK_FREE_NAME,
}

/** Why something did not happen. */
public enum class Failure {
    /** The vault is locked. Start [Crippy.unlockIntent] and try again once it is unlocked. */
    LOCKED,

    /** This app has no grant, the grant does not cover what was asked, or the user revoked it. */
    NOT_AUTHORIZED,

    /** The user turned access for other apps off in crippy's settings. */
    ACCESS_OFF,

    /** No such document, or one outside what this app was given: the two are not told apart. */
    NOT_FOUND,

    /** A document of that name already exists in that folder. */
    NAME_TAKEN,

    /** The request itself is wrong: a bad name, a file where a folder is needed, a folder moved into itself. */
    INVALID,

    /** The device does not have room for it. */
    NO_SPACE,

    /** crippy does not do this for that document, such as a preview of a type it cannot render. */
    UNSUPPORTED,

    /** Something went wrong inside crippy; the message says what. */
    FAILED,

    /** The session is over: open a new one. */
    ENDED,

    /** It was cancelled before it finished. */
    CANCELLED,

    /** crippy's process went away mid-call, or the connection was closed. Connect again. */
    DISCONNECTED,
}

/** The answer to one request: what it produced, or why it did not happen. */
public sealed interface CrippyResult<out T> {
    public data class Ok<out T>(
        val value: T,
    ) : CrippyResult<T>

    public data class Failed(
        val failure: Failure,
        /** Why, in English, for a developer rather than a user. */
        val message: String?,
    ) : CrippyResult<Nothing>
}

/** The value, or null for a failure. */
public fun <T> CrippyResult<T>.valueOrNull(): T? = (this as? CrippyResult.Ok)?.value

/** What a session is told while it lasts, on [CrippySession.events]. */
public sealed interface VaultEvent {
    /** The vault locked. The session stays, and answers [Failure.LOCKED] until the next unlock. */
    public data object Locked : VaultEvent

    public data object Unlocked : VaultEvent

    /** The session is over: the user revoked this app, turned access off, or the connection went. */
    public data object Ended : VaultEvent

    /** The children of a folder changed. Best effort, as SAF's notifications are. */
    public data class FolderChanged(
        val folderId: String,
    ) : VaultEvent
}
