// What the user allowed an app (crippy D114).
package com.systemhalted.crippy.api;

import com.systemhalted.crippy.api.Entry;

parcelable Grant {
    /** ICrippyService's RIGHT_ constants, or'd together. */
    int rights;
    /** The folder the grant is rooted at: the whole vault, or one folder and everything in it. */
    Entry scope;
}
