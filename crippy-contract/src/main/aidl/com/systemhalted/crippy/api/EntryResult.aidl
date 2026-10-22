package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.Entry;

parcelable EntryResult {
    int status;
    @nullable Entry entry;
    /** Why it failed, in English, for a developer rather than a user. */
    @nullable String message;
}
