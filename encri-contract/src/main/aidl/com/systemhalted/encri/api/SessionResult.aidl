package com.systemhalted.encri.api;

import com.systemhalted.encri.api.Grant;
import com.systemhalted.encri.api.IEncriSession;

parcelable SessionResult {
    /** IEncriService's STATUS_ constants. 0 means an older encri did not answer (L2). */
    int status;
    @nullable IEncriSession session;
    @nullable Grant grant;
    @nullable String message;
}
