package com.systemhalted.crippy.api;

parcelable ThumbnailTicket {
    int status;
    /** Names the preview to IThumbnailListener and to cancelThumbnail. Never 0. */
    int requestId;
    @nullable String message;
}
