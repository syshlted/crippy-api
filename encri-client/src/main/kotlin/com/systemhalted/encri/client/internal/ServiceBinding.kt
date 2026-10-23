package com.systemhalted.encri.client.internal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import com.systemhalted.encri.api.IEncriService
import com.systemhalted.encri.client.ConnectResult
import com.systemhalted.encri.client.EncriConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The binding to encri's service (encri D113; L11, L12): by action, with the package set, so only a
 * service in encri's package can answer, and checked once more when it does.
 *
 * Android rebinds by itself after encri's process restarts, with a new binder; [service] is whichever
 * binder is current, and null between the two. Sessions opened on the old one died with it, and say so.
 */
internal class ServiceBinding internal constructor(
    private val context: Context,
    /** The package a service must be in to be answered to: encri's, except in a device test (L13). */
    private val expectedPackage: String = Contract.PACKAGE,
) : ServiceConnection {
    private val first = CompletableDeferred<IBinder?>()

    @Volatile
    var service: IEncriService? = null
        private set

    @Volatile
    private var unbound = false

    override fun onServiceConnected(
        name: ComponentName,
        binder: IBinder,
    ) {
        // The package was set on the intent, so this is encri's; checked again because it costs nothing.
        val ours = name.packageName == expectedPackage
        service = if (ours) IEncriService.Stub.asInterface(binder) else null
        first.complete(if (ours) binder else null)
    }

    // The three below never read the name, so they take one that is null: Android always sends one, and
    // Robolectric's unbind sends none, which made a unit test fail whenever the looper ran the disconnect
    // before the test's own thread had finished (L13).
    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
    }

    override fun onBindingDied(name: ComponentName?) {
        service = null
        first.complete(null)
    }

    override fun onNullBinding(name: ComponentName?) {
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
        /** How long encri has to answer a bind before the connection gives up: a cold start of its process. */
        const val BIND_TIMEOUT_MILLIS = 10_000L

        suspend fun connect(
            context: Context,
            io: CoroutineDispatcher,
        ): ConnectResult =
            connect(context, io, Intent(Contract.ACTION_BIND).setPackage(Contract.PACKAGE), Contract.PACKAGE)

        /**
         * [connect] to the service [intent] names, in [expectedPackage]. Only a device test binds anything
         * but encri, to a stand-in in a process of its own (L13): everything after the bind is the path an
         * app takes.
         */
        @Suppress("ReturnCount", "SwallowedException") // One return per way a connection does not happen.
        suspend fun connect(
            context: Context,
            io: CoroutineDispatcher,
            intent: Intent,
            expectedPackage: String,
        ): ConnectResult {
            val binding = ServiceBinding(context, expectedPackage)
            val bound =
                try {
                    context.bindService(intent, binding, Context.BIND_AUTO_CREATE)
                } catch (refused: SecurityException) {
                    binding.unbind()
                    return ConnectResult.Unavailable(refused.message ?: "encri's service refused the bind")
                }
            if (!bound) {
                binding.unbind()
                return ConnectResult.Unavailable("encri's service could not be bound")
            }
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { binding.first.await() }
            val service = binding.service
            if (binder == null || service == null) {
                binding.unbind()
                return ConnectResult.Unavailable("encri's service did not answer")
            }
            // An older encri would not get this far; a service that answers 0 is not encri's (L2).
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
                    ConnectResult.Unavailable("the service bound to did not answer as encri does")
                }

                version < Contract.OLDEST_SERVICE -> {
                    binding.unbind()
                    ConnectResult.TooOld(version)
                }

                else -> {
                    ConnectResult.Connected(EncriConnection(binding, version, io))
                }
            }
        }
    }
}
