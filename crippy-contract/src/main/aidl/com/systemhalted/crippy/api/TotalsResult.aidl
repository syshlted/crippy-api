package com.systemhalted.crippy.api;

parcelable TotalsResult {
    int status;
    /** The bytes of every document under the folder. */
    long bytes;
    /** Documents under the folder, at any depth. */
    int documents;
    /** Folders under the folder, at any depth, not counting the folder itself. */
    int folders;
    @nullable String message;
}
