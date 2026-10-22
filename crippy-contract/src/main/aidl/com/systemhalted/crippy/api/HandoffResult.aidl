package com.systemhalted.crippy.api;

parcelable HandoffResult {
    int status;
    /** One content:// URI per document asked for, in the same order. */
    @nullable String[] uris;
    @nullable String message;
}
