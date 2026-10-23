package com.systemhalted.crippy.client.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.ConnectResult
import com.systemhalted.crippy.client.CrippyConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The binding to crippy's service (crippy D113; L11, L12): by action, with the package set, so only a
 * service in crippy's package can answer, and checked once more when it does.
 *
 * Android rebinds by itself after crippy's process restarts, with a new binder; [service] is whichever
 * binder is current, and null between the two. Sessions opened on the old one died with it, and say so.
 */
internal class ServiceBinding internal constructor(
    private val context: Context,
) : ServiceConnection {
    private val first = CompletableDeferred<IBinder?>()

    @Volatile
    var service: ICrippyService? = null
        private set

    @Volatile
    private var unbound = false

    override fun onServiceConnected(
        name: ComponentName,
        binder: IBinder,
    ) {
        // The package was set on the intent, so this is crippy's; checked again because it costs nothing.
        val ours = name.packageName == Contract.PACKAGE
        service = if (ours) ICrippyService.Stub.asInterface(binder) else null
        first.complete(if (ours) binder else null)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    override fun onBindingDied(name: ComponentName) {
        service = null
        first.complete(null)
    }

    override fun onNullBinding(name: ComponentName) {
        first.complete(null)
    }

    /** Lets go of the service. Safe to call more than once. */
    @Suppress("SwallowedException") // Already unbound is unbound.
    fun unbind() {
        if (unbound) return
        unbound = true
        service = null
        try {
            context.unbindService(this)
        } catch (notBound: IllegalArgumentException) {
            // Nothing to let go of.
        }
    }

    companion object {
        /** How long crippy has to answer a bind before the connection gives up: a cold start of its process. */
        const val BIND_TIMEOUT_MILLIS = 10_000L

        @Suppress("ReturnCount", "SwallowedException") // One return per way a connection does not happen.
        suspend fun connect(
            context: Context,
            io: CoroutineDispatcher,
        ): ConnectResult {
            val binding = ServiceBinding(context)
            val intent = Intent(Contract.ACTION_BIND).setPackage(Contract.PACKAGE)
            val bound =
                try {
                    context.bindService(intent, binding, Context.BIND_AUTO_CREATE)
                } catch (refused: SecurityException) {
                    binding.unbind()
                    return ConnectResult.Unavailable(refused.message ?: "crippy's service refused the bind")
                }
            if (!bound) {
                binding.unbind()
                return ConnectResult.Unavailable("crippy's service could not be bound")
            }
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { binding.first.await() }
            val service = binding.service
            if (binder == null || service == null) {
                binding.unbind()
                return ConnectResult.Unavailable("crippy's service did not answer")
            }
            // An older crippy would not get this far; a service that answers 0 is not crippy's (L2).
            val version =
                withContext(io) {
                    try {
                        service.contractVersion()
                    } catch (gone: RemoteException) {
                        0
                    }
                }
            return when {
                version == 0 -> {
                    binding.unbind()
                    ConnectResult.Unavailable("the service bound to did not answer as crippy does")
                }

                version < Contract.OLDEST_SERVICE -> {
                    binding.unbind()
                    ConnectResult.TooOld(version)
                }

                else -> {
                    ConnectResult.Connected(CrippyConnection(binding, version, io))
                }
            }
        }
    }
}
