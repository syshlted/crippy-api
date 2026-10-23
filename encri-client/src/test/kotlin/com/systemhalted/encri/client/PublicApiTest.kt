package com.systemhalted.encri.client

import com.systemhalted.encri.apidump.ApiDump
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The client's public API against `api/encri-client.api` (L16): what every app compiles against, so a change
 * to it is a diff in review rather than an app that stops building. Rewrite the dump with `-PupdateApi`.
 */
class PublicApiTest {
    private val dump = ApiDump.of(EncriSession::class.java, "com.systemhalted.encri.client")

    @Test
    fun `the client's public API is the one it has committed to`() {
        val problem =
            ApiDump.check(
                dump,
                System.getProperty("encri.api.dump"),
                System.getProperty("encri.api.update") == "true",
            )
        assertNull(problem, problem)
    }

    @Test
    fun `the dump sees the client at all, and nothing internal`() {
        // Without this, a dump that found nothing would match an empty file.
        assertTrue("class com.systemhalted.encri.client.EncriSession" in dump, dump)
        assertTrue("ServiceBinding" !in dump && "SessionListener" !in dump, dump)
    }
}
