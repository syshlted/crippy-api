package com.systemhalted.encri.api;

import com.systemhalted.encri.api.EntryResult;

oneway interface IWriteListener {
    /** Whether the write was saved, and the document as it now is if it was. */
    void onWriteFinished(in EntryResult result);
}
