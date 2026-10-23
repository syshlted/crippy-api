// A document or folder, as a session sees it. Fields are only ever appended (L11): a structured
// parcelable writes its own length first, so an older reader skips what it does not know and a
// newer reader finds a field an older writer did not send at its default.
package com.systemhalted.encri.api;

parcelable Entry {
    String documentId;
    /** The parent's id, or null for the folder the grant is rooted at: nothing above it is shown. */
    @nullable String parentId;
    String name;
    /** IEncriService.MIME_TYPE_FOLDER for a folder. */
    String mimeType;
    boolean folder;
    /** A document's length in bytes; 0 for a folder. */
    long sizeBytes;
    long createdUtcMillis;
    long modifiedUtcMillis;
    /** How many documents and folders are directly in a folder; -1 for a document. */
    int childCount = -1;
    /** Whether requestThumbnail can make a preview of it. */
    boolean thumbnailable;
}
