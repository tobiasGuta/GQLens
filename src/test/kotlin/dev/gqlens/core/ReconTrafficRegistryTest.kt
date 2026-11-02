package dev.gqlens.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReconTrafficRegistryTest {
    private val endpoint = "https://api.example.test/graphql"

    @Test fun labelsOnlyExactArmedExtensionRequestsNotRepeaterTraffic() {
        val registry = ReconTrafficRegistry()
        val mode = SchemaRecon.Mode.ROOT_FIELDS
        val fingerprint = ReconTrafficRegistry.fingerprint(SchemaRecon.document(mode))
        registry.arm(endpoint, mode, 1000)
        assertFalse(registry.labelRequest("REPEATER", endpoint, listOf(fingerprint), 1, 1001))
        assertFalse(registry.labelRequest("EXTENSIONS", "https://other.test/graphql",
            listOf(fingerprint), 2, 1001))
        assertFalse(registry.labelRequest("EXTENSIONS", endpoint, listOf("wrong"), 3, 1001))
        assertTrue(registry.labelRequest("EXTENSIONS", endpoint, listOf(fingerprint), 4, 1001))
        assertFalse(registry.labelResponse("REPEATER", 4))
        assertTrue(registry.labelResponse("EXTENSIONS", 4))
        assertFalse(registry.labelResponse("EXTENSIONS", 4))
        registry.disarm(endpoint, mode)
        assertFalse(registry.labelRequest("EXTENSIONS", endpoint, listOf(fingerprint), 5, 1002))
    }

    @Test fun staleArmedRequestsExpire() {
        val registry = ReconTrafficRegistry()
        val mode = SchemaRecon.Mode.TYPE_FIELDS
        registry.arm(endpoint, mode, 100)
        assertFalse(registry.labelRequest("EXTENSIONS", endpoint,
            listOf(ReconTrafficRegistry.fingerprint(SchemaRecon.document(mode))), 9, 15_101))
    }
}
