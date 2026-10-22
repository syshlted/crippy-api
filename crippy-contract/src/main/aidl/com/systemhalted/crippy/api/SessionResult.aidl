package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.Grant;
import com.systemhalted.crippy.api.ICrippySession;

parcelable SessionResult {
    /** ICrippyService's STATUS_ constants. 0 means an older crippy did not answer (L2). */
    int status;
    @nullable ICrippySession session;
    @nullable Grant grant;
    @nullable String message;
}
