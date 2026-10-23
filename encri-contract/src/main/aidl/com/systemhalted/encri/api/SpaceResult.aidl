package com.systemhalted.encri.api;

parcelable SpaceResult {
    int status;
    /** How much more document content fits, after encri's padding and framing. */
    long usableBytes;
    /** The size of the volume the vault is on. */
    long totalBytes;
    @nullable String message;
}
