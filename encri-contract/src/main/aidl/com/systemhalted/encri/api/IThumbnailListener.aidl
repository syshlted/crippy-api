package com.systemhalted.encri.api;

import com.systemhalted.encri.api.DescriptorResult;

oneway interface IThumbnailListener {
    /** The preview for requestId: on STATUS_OK, a descriptor over an encoded PNG or JPEG. */
    void onThumbnail(int requestId, in DescriptorResult result);
}
