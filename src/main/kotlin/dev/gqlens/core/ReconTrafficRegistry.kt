package dev.gqlens.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Recognizes only this extension's currently armed, exact introspection document.
 * No identifying header is sent to the target. No request bodies are saved here.
 *
 * EXTENSIONS is required to avoid attributing an identical Repeater request to
 * this extension. Context is a best-effort label if Burp exposes the HTTP event.
 */
class ReconTrafficRegistry {
    private data class Expected(val endpoint: String, val fingerprint: String, val expiry: Long)
    private val expected = LinkedHashMap<String, Expected>()
    private val inFlight = LinkedHashSet<Int>()

    @Synchronized
    fun arm(endpoint: String, mode: SchemaRecon.Mode, now: Long = System.currentTimeMillis()) {
        val key = key(endpoint, fingerprint(SchemaRecon.document(mode)))
        expected[key] = Expected(endpoint, fingerprint(SchemaRecon.document(mode)), now + 15_000)
        while (expected.size > 8) expected.remove(expected.keys.first())
    }

    @Synchronized
    fun disarm(endpoint: String, mode: SchemaRecon.Mode) {
        expected.remove(key(endpoint, fingerprint(SchemaRecon.document(mode))))
    }

    @Synchronized
    fun labelRequest(
        tool: String,
        endpoint: String,
        fingerprints: List<String>,
        messageId: Int,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (tool != "EXTENSIONS") return false
        expected.entries.removeIf { it.value.expiry < now }
        val matched = fingerprints.any { hash ->
            expected.containsKey(key(endpoint, hash))
        }
        if (matched) {
            inFlight.add(messageId)
            while (inFlight.size > 128) inFlight.remove(inFlight.first())
        }
        return matched
    }

    @Synchronized
    fun labelResponse(tool: String, messageId: Int): Boolean =
        if (tool == "EXTENSIONS") inFlight.remove(messageId) else false

    @Synchronized
    fun clear() {
        expected.clear()
        inFlight.clear()
    }

    private fun key(endpoint: String, fingerprint: String): String =
        endpoint + "\u0000" + fingerprint

    companion object {
        /** Matches GraphqlAnalyzer's 16-character document SHA-256 prefix. */
        fun fingerprint(document: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(document.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16)
    }
}
