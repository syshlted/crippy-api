package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.Entry;

parcelable PageResult {
    int status;
    @nullable Entry[] entries;
    /** What to pass as after for the next page, or null when this is the last. Opaque. */
    @nullable String next;
    @nullable String message;
}
