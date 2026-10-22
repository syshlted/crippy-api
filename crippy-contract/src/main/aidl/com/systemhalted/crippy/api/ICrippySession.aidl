// One app's session with the vault (crippy D114). crippy hands it out from openSession and answers
// each call for the app it was opened by, inside the grant that app holds. Every call returns at
// once (crippy D113): what takes time, a preview and the end of a write, is answered on a listener.
//
// Methods are only ever appended (L2).
package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.DescriptorResult;
import com.systemhalted.crippy.api.EntryResult;
import com.systemhalted.crippy.api.GrantResult;
import com.systemhalted.crippy.api.HandoffResult;
import com.systemhalted.crippy.api.IThumbnailListener;
import com.systemhalted.crippy.api.IWriteListener;
import com.systemhalted.crippy.api.Outcome;
import com.systemhalted.crippy.api.PageResult;
import com.systemhalted.crippy.api.SpaceResult;
import com.systemhalted.crippy.api.ThumbnailTicket;
import com.systemhalted.crippy.api.TotalsResult;

interface ICrippySession {
    /** What this session may do and the folder it is rooted at, as the user allowed it. */
    GrantResult grant();

    /** One document or folder. */
    EntryResult stat(String documentId);

    /**
     * The folder's children, in name order, a page at a time. after is null for the first page and
     * PageResult.next for every one after it; a null next means the last page. limit is capped by
     * crippy.
     */
    PageResult list(String folderId, @nullable String after, int limit);

    /** Everything under a folder, counted: bytes, documents and folders. */
    TotalsResult measure(String folderId);

    /** A read-only descriptor that seeks, over the document's current content. */
    DescriptorResult openRead(String documentId);

    /**
     * The write end of a pipe that replaces the document's content. Write the new content and
     * close it; listener then says whether it was saved. Closing with an error, or the client
     * dying, saves nothing and leaves the document as it was.
     */
    DescriptorResult openWrite(String documentId, IWriteListener listener);

    /** A new empty document, or a folder when mimeType is MIME_TYPE_FOLDER. */
    EntryResult create(String parentId, String name, String mimeType, int flags);

    EntryResult rename(String documentId, String name, int flags);

    EntryResult move(String documentId, String parentId, int flags);

    /** A document, or a folder and everything in it. */
    Outcome delete(String documentId);

    /** Sets a document's times; -1 leaves one as it is. */
    EntryResult setTimes(String documentId, long createdUtcMillis, long modifiedUtcMillis);

    /**
     * Queues a preview no larger than width by height. On STATUS_OK, listener is later handed the
     * encoded image, or the reason there is none, under the ticket's request id.
     */
    ThumbnailTicket requestThumbnail(String documentId, int width, int height, IThumbnailListener listener);

    /** Gives up on a queued preview. */
    oneway void cancelThumbnail(int requestId);

    /**
     * content:// URIs another app can open the documents through (crippy D117), granted to the
     * calling app for reading, which it may pass on with FLAG_GRANT_READ_URI_PERMISSION. One URI
     * per document, in order. They stop working when the vault locks, the user revokes this app,
     * or revokeHandoffs is called.
     */
    HandoffResult handOff(in String[] documentIds);

    /** Ends handoffs this session made. */
    Outcome revokeHandoffs(in String[] uris);

    /** How much more the vault can hold. */
    SpaceResult space();

    /** Locks the vault, for every app and for crippy. */
    Outcome lock();

    /** Ends the session. Its open descriptors, writes and handoffs end with it. */
    oneway void end();
}
