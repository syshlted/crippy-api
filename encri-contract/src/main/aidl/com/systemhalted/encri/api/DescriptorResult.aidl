package com.systemhalted.encri.api;

parcelable DescriptorResult {
    int status;
    /** The caller owns it and closes it. */
    @nullable ParcelFileDescriptor descriptor;
    /** How many bytes there are to read, or -1 for a write. */
    long length = -1;
    @nullable String message;
}
