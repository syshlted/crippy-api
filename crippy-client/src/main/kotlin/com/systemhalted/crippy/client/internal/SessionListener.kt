package com.systemhalted.crippy.client.internal

import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.api.IVaultListener
import com.systemhalted.crippy.client.VaultEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What crippy tells a session, turned into [VaultEvent]s (L11). crippy calls this oneway, on a binder
 * thread, so it never waits: an event a slow collector has not taken is dropped oldest first rather than
 * holding crippy's thread.
 */
internal class SessionListener : IVaultListener.Stub() {
    private val emitted =
        MutableSharedFlow<VaultEvent>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<VaultEvent> get() = emitted

    /** Ends the session's event stream from this side, when the session's binder died. */
    fun ended() {
        emitted.tryEmit(VaultEvent.Ended)
    }

    override fun onStateChanged(state: Int) {
        when (state) {
            ICrippyService.STATE_LOCKED -> emitted.tryEmit(VaultEvent.Locked)

            ICrippyService.STATE_UNLOCKED -> emitted.tryEmit(VaultEvent.Unlocked)

            ICrippyService.STATE_ENDED -> emitted.tryEmit(VaultEvent.Ended)

            // A state a newer crippy added: this client does not know what it means, so says nothing.
            else -> Unit
        }
    }

    override fun onFolderChanged(folderId: String?) {
        folderId?.let { emitted.tryEmit(VaultEvent.FolderChanged(it)) }
    }

    private companion object {
        const val BUFFER = 64
    }
}
