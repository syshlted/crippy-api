package com.systemhalted.crippy.api;

parcelable SpaceResult {
    int status;
    /** How much more document content fits, after crippy's padding and framing. */
    long usableBytes;
    /** The size of the volume the vault is on. */
    long totalBytes;
    @nullable String message;
}
