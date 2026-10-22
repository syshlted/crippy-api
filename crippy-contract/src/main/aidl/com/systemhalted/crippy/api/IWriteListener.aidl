package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.EntryResult;

oneway interface IWriteListener {
    /** Whether the write was saved, and the document as it now is if it was. */
    void onWriteFinished(in EntryResult result);
}
