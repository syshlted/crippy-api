package com.systemhalted.encri.api

import android.os.Binder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the generated proxy makes of an answer, and of no answer (L2). A service that does not know a
 * transaction answers zero rather than an error, which is why version 0 can never be encri's and why
 * a client must never call above the service's version. The claim was read from the aidl tool's
 * output; this is it run. A real Parcel and Binder are Robolectric's, because the mockable
 * android.jar has neither (L8).
 *
 * Every case goes through the proxy: `Stub.asInterface` hands back the stub itself for an object in
 * the same process, so [Remote] keeps the stub behind a binder of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 36])
class UnansweredTransactionTest {
    /** The control: without it, a Parcel that read zero for everything would pass the case below. */
    @Test
    fun `a service that implements the method answers through the proxy`() {
        val encri =
            object : IEncriService.Stub() {
                override fun contractVersion(): Int = IEncriService.VERSION

                override fun openSession(listener: IVaultListener?): SessionResult? = null
            }
        assertEquals(IEncriService.VERSION, proxyOver(Remote(encri)).contractVersion())
    }

    @Test
    fun `a service that does not implement the method answers zero rather than failing`() {
        assertEquals(0, proxyOver(Remote(null)).contractVersion())
    }

    @Test
    fun `a session asked of a service that does not have the method reads as no answer`() {
        // A parcelable result reads as null from an empty reply, which is why every result carries a
        // status that is never 0 and a client treats null as "not answered" rather than as a refusal.
        assertNull(proxyOver(Remote(null)).openSession(null))
    }

    private fun proxyOver(binder: Binder): IEncriService = IEncriService.Stub.asInterface(binder)

    /** A binder the proxy cannot see into, answering only the transactions [service] knows. */
    private class Remote(
        private val service: IEncriService.Stub?,
    ) : Binder() {
        // What a BinderProxy answers, which is what makes `asInterface` build a proxy. Without it, API
        // 27's Binder dereferences a descriptor this binder never attached and throws.
        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = service?.onTransact(code, data, reply, flags) ?: super.onTransact(code, data, reply, flags)
    }
}
