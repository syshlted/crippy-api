// What a session is told while it lasts. oneway, so encri never waits on a client to listen.
package com.systemhalted.encri.api;

oneway interface IVaultListener {
    /** One of IEncriService's STATE_ constants. */
    void onStateChanged(int state);

    /** The children of folderId changed. Best effort, as SAF's change notifications are. */
    void onFolderChanged(String folderId);
}
