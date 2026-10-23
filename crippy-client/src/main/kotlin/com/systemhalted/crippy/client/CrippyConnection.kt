package com.systemhalted.crippy.client

import android.os.RemoteException
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.internal.ServiceBinding
import com.systemhalted.crippy.client.internal.SessionListener
import com.systemhalted.crippy.client.internal.documentOf
import com.systemhalted.crippy.client.internal.failureOf
import com.systemhalted.crippy.client.internal.rightsOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A connection to crippy's service, from [Crippy.connect]. Close it when done: that unbinds, and every
 * session opened through it ends.
 */
public class CrippyConnection internal constructor(
    private val binding: ServiceBinding,
    /** The contract version the installed crippy implements (L2). */
    public val contractVersion: Int,
    private val io: CoroutineDispatcher,
) : AutoCloseable {
    private val sessions = ConcurrentHashMap.newKeySet<CrippySession>()

    /** Whether crippy's service is bound now. After crippy's process restarts, Android binds it again. */
    public val isConnected: Boolean get() = binding.service != null

    /**
     * Opens a session for this app (crippy D114). A locked vault answers [SessionOutcome.Locked] to every
     * app alike, because a locked vault cannot say which apps it allows.
     */
    @Suppress("ReturnCount", "SwallowedException") // One return per answer.
    public suspend fun openSession(): SessionOutcome =
        withContext(io) {
            val service =
                binding.service
                    ?: return@withContext SessionOutcome.Failed(Failure.DISCONNECTED, "crippy's service is not bound")
            val listener = SessionListener()
            val answer =
                try {
                    service.openSession(listener)
                } catch (gone: RemoteException) {
                    return@withContext SessionOutcome.Failed(Failure.DISCONNECTED, gone.message)
                } ?: return@withContext SessionOutcome.Failed(Failure.FAILED, "crippy did not answer")
            when (answer.status) {
                ICrippyService.STATUS_OK -> {
                    val remote = answer.session
                    val grant = answer.grant
                    if (remote == null || grant == null) {
                        SessionOutcome.Failed(Failure.FAILED, "crippy opened a session and sent no grant")
                    } else {
                        val session =
                            CrippySession.open(
                                remote = remote,
                                grant = CrippyGrant(rightsOf(grant.rights), documentOf(grant.scope)),
                                listener = listener,
                                io = io,
                                onClosed = sessions::remove,
                            )
                        sessions += session
                        SessionOutcome.Opened(session)
                    }
                }

                ICrippyService.STATUS_LOCKED -> {
                    SessionOutcome.Locked
                }

                ICrippyService.STATUS_NOT_AUTHORIZED -> {
                    SessionOutcome.NotAuthorized
                }

                ICrippyService.STATUS_ACCESS_OFF -> {
                    SessionOutcome.AccessOff
                }

                else -> {
                    SessionOutcome.Failed(failureOf(answer.status), answer.message)
                }
            }
        }

    /** Ends every session opened through this connection, then unbinds. */
    override fun close() {
        sessions.toList().forEach(CrippySession::close)
        binding.unbind()
    }
}
