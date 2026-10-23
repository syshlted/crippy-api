package com.systemhalted.encri.client.device

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.systemhalted.encri.api.IEncriService

/**
 * A service built against an older contract, in a process of its own (L13): it knows transaction 1 and
 * nothing after it, so every other method is a transaction it has never heard of. Bound with action
 * [ZERO], it answers version 0 instead, as a binder that is not encri's does (L2).
 *
 * It is the device half of encri-contract's `UnansweredTransactionTest`, which runs the same claim
 * under Robolectric: here the reply comes back through a real BinderProxy and the kernel, which is what
 * an installed app meets.
 */
class OlderEncri : Service() {
    override fun onBind(intent: Intent): IBinder = Older(if (intent.action == ZERO) 0 else IEncriService.VERSION)

    private class Older(
        private val version: Int,
    ) : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(IEncriService.DESCRIPTOR)
            reply?.writeNoException()
            reply?.writeInt(version)
            return true
        }
    }

    companion object {
        /** Bind with this action for a service that answers version 0. */
        const val ZERO = "com.systemhalted.encri.client.device.ZERO"
    }
}
