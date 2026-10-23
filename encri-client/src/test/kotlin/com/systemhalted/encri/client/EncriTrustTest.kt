package com.systemhalted.encri.client

import android.app.Activity
import android.content.Intent
import com.systemhalted.encri.api.IEncriService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What an app names encri by, and what it asks the user with (L12). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncriTrustTest {
    @Test
    fun `a certificate is named by its SHA-256, in any case and with colons`() {
        val digest = "AB:".repeat(31) + "AB"
        assertTrue(EncriTrust.alsoTrusting(digest).certificates.contains("ab".repeat(32)))
        assertThrows(IllegalArgumentException::class.java) { EncriTrust.alsoTrusting("not a digest") }
    }

    @Test
    fun `asking for access always asks for reading, and names encri`() {
        val asked = Encri.authorizationIntent(setOf(Right.WRITE))
        assertEquals(IEncriService.PACKAGE, asked.`package`)
        assertEquals(
            IEncriService.RIGHT_READ or IEncriService.RIGHT_WRITE,
            asked.getIntExtra(IEncriService.EXTRA_RIGHTS, 0),
        )
    }

    @Test
    fun `the user's answer is the rights allowed, or nothing`() {
        val allowed =
            Intent().putExtra(
                IEncriService.EXTRA_RIGHTS,
                IEncriService.RIGHT_READ or IEncriService.RIGHT_MOVE,
            )
        assertEquals(setOf(Right.READ, Right.MOVE), Encri.authorizationResult(Activity.RESULT_OK, allowed))
        assertNull(Encri.authorizationResult(Activity.RESULT_CANCELED, allowed))
        assertNull(Encri.authorizationResult(Activity.RESULT_OK, Intent()))
    }
}
