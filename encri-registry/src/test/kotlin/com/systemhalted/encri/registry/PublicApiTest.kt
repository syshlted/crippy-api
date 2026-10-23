package com.systemhalted.encri.registry

import com.systemhalted.encri.apidump.ApiDump
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The registry's public API against `api/encri-registry.api` (L16), which encri compiles against. */
class PublicApiTest {
    private val dump = ApiDump.of(Registry::class.java, "com.systemhalted.encri.registry")

    @Test
    fun `the registry's public API is the one it has committed to`() {
        val problem =
            ApiDump.check(
                dump,
                System.getProperty("encri.api.dump"),
                System.getProperty("encri.api.update") == "true",
            )
        assertNull(problem, problem)
    }

    @Test
    fun `the dump sees the registry at all, and nothing internal`() {
        assertTrue("class com.systemhalted.encri.registry.Registry" in dump, dump)
        assertTrue("RegistryFormat" !in dump && "validate(" !in dump, dump)
    }
}
