package com.systemhalted.crippy.api

import android.os.IBinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The names an installed client has already compiled in (L2). Each assertion is a value that cannot
 * change once the contract has shipped, so a failure here is never fixed by updating the expected
 * value: it is fixed by putting the contract back.
 */
class WireNamesTest {
    @Test
    fun `the interface token is the interface's full name`() {
        assertEquals("com.systemhalted.crippy.api.ICrippyService", ICrippyService.DESCRIPTOR)
    }

    @Test
    fun `asking for the version is transaction 1`() {
        assertEquals(IBinder.FIRST_CALL_TRANSACTION, ICrippyService.Stub.TRANSACTION_contractVersion)
    }

    @Test
    fun `this is the first version of the contract`() {
        assertEquals(1, ICrippyService.VERSION)
    }
}
