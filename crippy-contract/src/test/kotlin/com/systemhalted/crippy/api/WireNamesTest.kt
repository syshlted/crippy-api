package com.systemhalted.crippy.api

import android.os.IBinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The names an installed client has already compiled in (L2, L11). Each assertion is a value that
 * cannot change once the contract has shipped, so a failure here is never fixed by updating the
 * expected value: it is fixed by putting the contract back.
 */
class WireNamesTest {
    @Test
    fun `each interface token is the interface's full name`() {
        assertEquals("com.systemhalted.crippy.api.ICrippyService", ICrippyService.DESCRIPTOR)
        assertEquals("com.systemhalted.crippy.api.ICrippySession", ICrippySession.DESCRIPTOR)
        assertEquals("com.systemhalted.crippy.api.IVaultListener", IVaultListener.DESCRIPTOR)
        assertEquals("com.systemhalted.crippy.api.IWriteListener", IWriteListener.DESCRIPTOR)
        assertEquals("com.systemhalted.crippy.api.IThumbnailListener", IThumbnailListener.DESCRIPTOR)
    }

    @Test
    fun `asking for the version is transaction 1`() {
        assertEquals(IBinder.FIRST_CALL_TRANSACTION, ICrippyService.Stub.TRANSACTION_contractVersion)
    }

    @Test
    fun `the service's methods are where they were`() {
        assertEquals(
            listOf("contractVersion", "openSession"),
            positions(ICrippyService.Stub::class.java),
        )
    }

    @Test
    fun `the session's methods are where they were`() {
        assertEquals(
            listOf(
                "grant",
                "stat",
                "list",
                "measure",
                "openRead",
                "openWrite",
                "create",
                "rename",
                "move",
                "delete",
                "setTimes",
                "requestThumbnail",
                "cancelThumbnail",
                "handOff",
                "revokeHandoffs",
                "space",
                "lock",
                "end",
            ),
            positions(ICrippySession.Stub::class.java),
        )
    }

    @Test
    fun `the listeners' methods are where they were`() {
        assertEquals(listOf("onStateChanged", "onFolderChanged"), positions(IVaultListener.Stub::class.java))
        assertEquals(listOf("onWriteFinished"), positions(IWriteListener.Stub::class.java))
        assertEquals(listOf("onThumbnail"), positions(IThumbnailListener.Stub::class.java))
    }

    @Test
    fun `this is the first version of the contract`() {
        assertEquals(1, ICrippyService.VERSION)
    }

    @Test
    fun `where the service is and how it is asked`() {
        assertEquals("com.systemhalted.crippy", ICrippyService.PACKAGE)
        assertEquals("com.systemhalted.crippy.api.action.BIND", ICrippyService.ACTION_BIND)
        assertEquals("com.systemhalted.crippy.api.action.AUTHORIZE", ICrippyService.ACTION_AUTHORIZE)
        assertEquals("com.systemhalted.crippy.api.extra.RIGHTS", ICrippyService.EXTRA_RIGHTS)
        assertEquals("com.systemhalted.crippy.action.UNLOCK", ICrippyService.ACTION_UNLOCK)
        assertEquals("vnd.android.document/directory", ICrippyService.MIME_TYPE_FOLDER)
    }

    @Test
    fun `the rights are distinct bits`() {
        val rights =
            listOf(
                ICrippyService.RIGHT_READ,
                ICrippyService.RIGHT_WRITE,
                ICrippyService.RIGHT_CREATE,
                ICrippyService.RIGHT_DELETE,
                ICrippyService.RIGHT_MOVE,
            )
        assertEquals(listOf(1, 2, 4, 8, 16), rights)
    }

    @Test
    fun `no status is 0, because 0 is what an older crippy answers`() {
        val statuses =
            listOf(
                ICrippyService.STATUS_OK,
                ICrippyService.STATUS_LOCKED,
                ICrippyService.STATUS_NOT_AUTHORIZED,
                ICrippyService.STATUS_ACCESS_OFF,
                ICrippyService.STATUS_NOT_FOUND,
                ICrippyService.STATUS_NAME_TAKEN,
                ICrippyService.STATUS_INVALID,
                ICrippyService.STATUS_NO_SPACE,
                ICrippyService.STATUS_UNSUPPORTED,
                ICrippyService.STATUS_FAILED,
                ICrippyService.STATUS_ENDED,
                ICrippyService.STATUS_CANCELLED,
            )
        assertEquals((1..12).toList(), statuses)
        assertEquals(
            listOf(1, 2, 3),
            listOf(ICrippyService.STATE_LOCKED, ICrippyService.STATE_UNLOCKED, ICrippyService.STATE_ENDED),
        )
        assertEquals(1, ICrippyService.FLAG_FREE_NAME)
    }

    /**
     * Each method's name, in the order of its transaction code. The aidl tool numbers methods by their
     * position in the file, so this is the file's order as the wire sees it.
     */
    private fun positions(stub: Class<*>): List<String> =
        stub.declaredFields
            .filter { it.name.startsWith(PREFIX) && it.type == Int::class.javaPrimitiveType }
            .map { field ->
                field.isAccessible = true
                field.getInt(null) to field.name.removePrefix(PREFIX)
            }.sortedBy { it.first }
            .also { codes ->
                assertEquals(
                    (0 until codes.size).map { IBinder.FIRST_CALL_TRANSACTION + it },
                    codes.map { it.first },
                    "transaction codes must be contiguous from FIRST_CALL_TRANSACTION",
                )
            }.map { it.second }

    private companion object {
        const val PREFIX = "TRANSACTION_"
    }
}
