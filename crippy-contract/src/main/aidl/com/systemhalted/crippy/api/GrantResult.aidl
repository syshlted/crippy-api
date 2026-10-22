package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.Grant;

parcelable GrantResult {
    int status;
    @nullable Grant grant;
    @nullable String message;
}
