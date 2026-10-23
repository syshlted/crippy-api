package com.systemhalted.crippy.client

import com.systemhalted.crippy.api.ICrippyService
import com.systemhalted.crippy.client.internal.SessionListener
import com.systemhalted.crippy.client.internal.bitsOf
import com.systemhalted.crippy.client.internal.documentOf
import com.systemhalted.crippy.client.internal.failureOf
import com.systemhalted.crippy.client.internal.rightsOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The contract's words, as the client's own (L5, L11): statuses, rights, entries and events. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContractMappingTest {
    @Test
    fun `every status the contract names is the failure of the same name`() {
        val named =
            mapOf(
                ICrippyService.STATUS_LOCKED to Failure.LOCKED,
                ICrippyService.STATUS_NOT_AUTHORIZED to Failure.NOT_AUTHORIZED,
                ICrippyService.STATUS_ACCESS_OFF to Failure.ACCESS_OFF,
                ICrippyService.STATUS_NOT_FOUND to Failure.NOT_FOUND,
                ICrippyService.STATUS_NAME_TAKEN to Failure.NAME_TAKEN,
                ICrippyService.STATUS_INVALID to Failure.INVALID,
                ICrippyService.STATUS_NO_SPACE to Failure.NO_SPACE,
                ICrippyService.STATUS_UNSUPPORTED to Failure.UNSUPPORTED,
                ICrippyService.STATUS_FAILED to Failure.FAILED,
                ICrippyService.STATUS_ENDED to Failure.ENDED,
                ICrippyService.STATUS_CANCELLED to Failure.CANCELLED,
            )
        named.forEach { (status, failure) -> assertEquals(failure, failureOf(status)) }
        // A status a newer crippy added, and the 0 an older one answers (L2).
        assertEquals(Failure.FAILED, failureOf(99))
        assertEquals(Failure.FAILED, failureOf(0))
    }

    @Test
    fun `rights travel as bits, and a bit this client does not know is dropped`() {
        val all = Right.entries.toSet()
        assertEquals(all, rightsOf(bitsOf(all)))
        assertEquals(setOf(Right.READ), rightsOf(ICrippyService.RIGHT_READ or 1024))
    }

    @Test
    fun `a folder carries its child count and a document does not`() {
        assertEquals(3, documentOf(entry("f", folder = true)).childCount)
        assertNull(documentOf(entry("d")).childCount)
        assertNull(documentOf(entry("f", folder = true).apply { childCount = -1 }).childCount)
    }

    @Test
    fun `what crippy tells a session becomes its events, and what it does not know says nothing`() =
        runTest {
            val listener = SessionListener()
            val events = mutableListOf<VaultEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { listener.events.toList(events) }
            listener.onStateChanged(ICrippyService.STATE_LOCKED)
            listener.onStateChanged(ICrippyService.STATE_UNLOCKED)
            listener.onStateChanged(99)
            listener.onFolderChanged(null)
            listener.onFolderChanged("f")
            listener.onStateChanged(ICrippyService.STATE_ENDED)
            listener.ended()
            assertEquals(
                listOf(
                    VaultEvent.Locked,
                    VaultEvent.Unlocked,
                    VaultEvent.FolderChanged("f"),
                    VaultEvent.Ended,
                    VaultEvent.Ended,
                ),
                events,
            )
        }
}
