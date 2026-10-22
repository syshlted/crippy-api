// What a session is told while it lasts. oneway, so crippy never waits on a client to listen.
package com.systemhalted.crippy.api;

oneway interface IVaultListener {
    /** One of ICrippyService's STATE_ constants. */
    void onStateChanged(int state);

    /** The children of folderId changed. Best effort, as SAF's change notifications are. */
    void onFolderChanged(String folderId);
}
