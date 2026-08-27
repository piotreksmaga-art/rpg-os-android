package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class OpenRouterConnectionRecoveryTest {
    @Test
    fun androidDefault_isTheMobileBielikExecuTorchProfile() {
        val profile = BielikLocalModelProfiles.DEFAULT_ANDROID

        assertEquals("speakleash/bielik-1.5b-v3.0-instruct", profile.modelUid)
        assertEquals(2_048, profile.recommendedContextUnits)
        assertEquals(2_048, profile.maximumContextUnits)
        val variant = profile.variants.single()
        assertEquals(LocalArtifactFormat.EXECUTORCH, variant.format)
        assertEquals(923_083_008L, variant.expectedBytes)
        assertEquals("4e5a6b8e6684e94d794a609a2f76cfb56f3b3ddef3dfc96904cd10f40244457e", variant.sha256)
    }

    @Test
    fun loopbackPage_reportsSuccessOnlyAfterTheExchangeCompleted() {
        val server = OpenRouterLoopbackCallbackServer()
        var exchanged = false
        server.onCallback { callback ->
            assertEquals("AUTH-CODE", callback.authorizationCode)
            exchanged = true
            CloudConnectionStatus("OPENROUTER", CloudAuthState.CONNECTED)
        }
        val endpoint = server.create("a".repeat(24))

        val body = URL("${endpoint.callbackUrl}?code=AUTH-CODE").readText()

        assertTrue(exchanged)
        assertTrue(body.contains("RPG OS połączono z OpenRouter"))
    }

    @Test
    fun loopbackPage_doesNotClaimSuccessWhenTheExchangeFails() {
        val server = OpenRouterLoopbackCallbackServer()
        server.onCallback {
            CloudConnectionStatus("OPENROUTER", CloudAuthState.ERROR, reasonUid = "OPENROUTER_AUTH_HTTP_403")
        }
        val endpoint = server.create("b".repeat(24))

        val body = URL("${endpoint.callbackUrl}?code=REJECTED-CODE").readText()

        assertTrue(body.contains("nie zakończył połączenia"))
        assertTrue(body.contains("OPENROUTER_AUTH_HTTP_403"))
        assertTrue(!body.contains("RPG OS połączono z OpenRouter"))
    }

    @Test
    fun codeExchange_preservesTheProviderFailureReason() {
        val auth = OpenRouterPkceAuthPort(
            MemorySecretStore(),
            callbackFactory(),
            OpenRouterCodeExchange { _, _ ->
                throw AiTransportException("OPENROUTER_AUTH_HTTP_403")
            },
        )

        val authorization = auth.beginConnect()
        val result = auth.complete(CloudAuthCallback(authorization.callbackUrl, "AUTH-CODE"))

        assertEquals(CloudAuthState.ERROR, result.state)
        assertEquals("OPENROUTER_AUTH_HTTP_403", result.reasonUid)
        assertEquals(CloudAuthState.DISCONNECTED, auth.status().state)
    }

    @Test
    fun successfulExchange_failsClosedWhenCredentialCannotBePersistedAndClearsReturnedKey() {
        val exchangedKey = "sk-or-v1-key-returned-by-openrouter".toCharArray()
        val auth = OpenRouterPkceAuthPort(
            object : SecretStore {
                override fun put(secretUid: String, value: CharArray) = error("keystore unavailable")
                override fun get(secretUid: String): CharArray? = null
                override fun remove(secretUid: String) = Unit
            },
            callbackFactory(),
            OpenRouterCodeExchange { _, _ -> exchangedKey to "user-1" },
        )

        val authorization = auth.beginConnect()
        val result = auth.complete(CloudAuthCallback(authorization.callbackUrl, "AUTH-CODE"))

        assertEquals(CloudAuthState.ERROR, result.state)
        assertEquals("OPENROUTER_CREDENTIAL_STORAGE_FAILED", result.reasonUid)
        assertTrue(exchangedKey.all { it == '\u0000' })
    }

    @Test
    fun successfulExchange_failsClosedWhenCredentialReadbackDoesNotMatch() {
        val auth = OpenRouterPkceAuthPort(
            object : SecretStore {
                override fun put(secretUid: String, value: CharArray) = Unit
                override fun get(secretUid: String): CharArray = "sk-or-v1-different-key".toCharArray()
                override fun remove(secretUid: String) = Unit
            },
            callbackFactory(),
            OpenRouterCodeExchange { _, _ -> "sk-or-v1-key-returned-by-openrouter".toCharArray() to null },
        )

        val authorization = auth.beginConnect()
        val result = auth.complete(CloudAuthCallback(authorization.callbackUrl, "AUTH-CODE"))

        assertEquals(CloudAuthState.ERROR, result.state)
        assertEquals("OPENROUTER_CREDENTIAL_STORAGE_FAILED", result.reasonUid)
    }

    @Test
    fun storageReadFailure_isReportedWithoutCrashingSettings() {
        val auth = OpenRouterPkceAuthPort(
            object : SecretStore {
                override fun put(secretUid: String, value: CharArray) = Unit
                override fun get(secretUid: String): CharArray? = error("invalid restored keystore entry")
                override fun remove(secretUid: String) = Unit
            },
            callbackFactory(),
            OpenRouterCodeExchange { _, _ -> error("unused") },
        )

        val result = auth.status()

        assertEquals(CloudAuthState.ERROR, result.state)
        assertEquals("OPENROUTER_CREDENTIAL_STORAGE_UNAVAILABLE", result.reasonUid)
    }

    @Test
    fun manualCredential_isStoredAndTheCallerBufferIsCleared() {
        val secrets = MemorySecretStore()
        val auth = OpenRouterPkceAuthPort(
            secrets,
            callbackFactory(),
            OpenRouterCodeExchange { _, _ -> error("unused") },
        )
        val supplied = "sk-or-v1-valid-mobile-test-key".toCharArray()

        val result = auth.connectWithCredential(supplied)

        assertEquals(CloudAuthState.CONNECTED, result.state)
        assertTrue(supplied.all { it == '\u0000' })
        assertEquals(CloudAuthState.CONNECTED, auth.status().state)
        val stored = auth.accessCredential()
        assertEquals("sk-or-v1-valid-mobile-test-key", stored?.concatToString())
        stored?.fill('\u0000')
    }

    @Test
    fun invalidManualCredential_isRejectedClearedAndNotStored() {
        val auth = OpenRouterPkceAuthPort(
            MemorySecretStore(),
            callbackFactory(),
            OpenRouterCodeExchange { _, _ -> error("unused") },
        )
        val supplied = "not-a-key".toCharArray()

        val result = auth.connectWithCredential(supplied)

        assertEquals(CloudAuthState.ERROR, result.state)
        assertEquals("MANUAL_API_KEY_REJECTED", result.reasonUid)
        assertTrue(supplied.all { it == '\u0000' })
        assertNull(auth.accessCredential())
    }

    @Test
    fun manualCredential_reportsStorageFailureInsteadOfInvalidFormat() {
        val auth = OpenRouterPkceAuthPort(
            object : SecretStore {
                override fun put(secretUid: String, value: CharArray) = error("keystore unavailable")
                override fun get(secretUid: String): CharArray? = null
                override fun remove(secretUid: String) = Unit
            },
            callbackFactory(),
            OpenRouterCodeExchange { _, _ -> error("unused") },
        )
        val supplied = "sk-or-v1-valid-mobile-test-key".toCharArray()

        val result = auth.connectWithCredential(supplied)

        assertEquals(CloudAuthState.ERROR, result.state)
        assertEquals("OPENROUTER_CREDENTIAL_STORAGE_FAILED", result.reasonUid)
        assertTrue(supplied.all { it == '\u0000' })
    }

    private fun callbackFactory() = OpenRouterCallbackEndpointFactory { nonce ->
        OpenRouterCallbackEndpoint("http://127.0.0.1:7777/callback/$nonce", "H:$nonce")
    }

    private class MemorySecretStore : SecretStore {
        private val values = mutableMapOf<String, CharArray>()

        override fun put(secretUid: String, value: CharArray) {
            values.put(secretUid, value.copyOf())?.fill('\u0000')
        }

        override fun get(secretUid: String): CharArray? = values[secretUid]?.copyOf()

        override fun remove(secretUid: String) {
            values.remove(secretUid)?.fill('\u0000')
        }
    }
}
