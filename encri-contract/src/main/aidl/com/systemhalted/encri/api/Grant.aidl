// What the user allowed an app (encri D114).
package com.systemhalted.encri.api;

import com.systemhalted.encri.api.Entry;

parcelable Grant {
    /** IEncriService's RIGHT_ constants, or'd together. */
    int rights;
    /** The folder the grant is rooted at: the whole vault, or one folder and everything in it. */
    Entry scope;
}
