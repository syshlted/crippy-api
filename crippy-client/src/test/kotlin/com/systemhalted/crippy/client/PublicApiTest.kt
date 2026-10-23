package com.systemhalted.crippy.client

import com.systemhalted.crippy.apidump.ApiDump
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The client's public API against `api/crippy-client.api` (L16): what every app compiles against, so a change
 * to it is a diff in review rather than an app that stops building. Rewrite the dump with `-PupdateApi`.
 */
class PublicApiTest {
    private val dump = ApiDump.of(CrippySession::class.java, "com.systemhalted.crippy.client")

    @Test
    fun `the client's public API is the one it has committed to`() {
        val problem =
            ApiDump.check(
                dump,
                System.getProperty("crippy.api.dump"),
                System.getProperty("crippy.api.update") == "true",
            )
        assertNull(problem, problem)
    }

    @Test
    fun `the dump sees the client at all, and nothing internal`() {
        // Without this, a dump that found nothing would match an empty file.
        assertTrue("class com.systemhalted.crippy.client.CrippySession" in dump, dump)
        assertTrue("ServiceBinding" !in dump && "SessionListener" !in dump, dump)
    }
}
