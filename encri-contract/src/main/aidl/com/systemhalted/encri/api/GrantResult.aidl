package com.systemhalted.encri.api;

import com.systemhalted.encri.api.Grant;

parcelable GrantResult {
    int status;
    @nullable Grant grant;
    @nullable String message;
}
