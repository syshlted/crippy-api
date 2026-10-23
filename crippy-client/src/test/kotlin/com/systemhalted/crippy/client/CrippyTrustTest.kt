package com.systemhalted.crippy.client

import android.app.Activity
import android.content.Intent
import com.systemhalted.crippy.api.ICrippyService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What an app names crippy by, and what it asks the user with (L12). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrippyTrustTest {
    @Test
    fun `a certificate is named by its SHA-256, in any case and with colons`() {
        val digest = "AB:".repeat(31) + "AB"
        assertTrue(CrippyTrust.alsoTrusting(digest).certificates.contains("ab".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) { CrippyTrust.alsoTrusting("not a digest") }
    }

    @Test
    fun `asking for access always asks for reading, and names crippy`() {
        val asked = Crippy.authorizationIntent(setOf(Right.WRITE))
        assertEquals(ICrippyService.PACKAGE, asked.`package`)
        assertEquals(
            ICrippyService.RIGHT_READ or ICrippyService.RIGHT_WRITE,
            asked.getIntExtra(ICrippyService.EXTRA_RIGHTS, 0),
        )
    }

    @Test
    fun `the user's answer is the rights allowed, or nothing`() {
        val allowed =
            Intent().putExtra(
                ICrippyService.EXTRA_RIGHTS,
                ICrippyService.RIGHT_READ or ICrippyService.RIGHT_MOVE,
            )
        assertEquals(setOf(Right.READ, Right.MOVE), Crippy.authorizationResult(Activity.RESULT_OK, allowed))
        assertNull(Crippy.authorizationResult(Activity.RESULT_CANCELED, allowed))
        assertNull(Crippy.authorizationResult(Activity.RESULT_OK, Intent()))
    }
}
