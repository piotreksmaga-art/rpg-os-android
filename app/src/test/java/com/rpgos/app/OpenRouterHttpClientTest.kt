package com.rpgos.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterHttpClientTest {
    @Test
    fun exchange_usesTheOfficialPkceContractAndAcceptsAValidKeyResponse() {
        var capturedBody = ""
        var capturedAccept = ""
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            capturedBody = chain.request().body?.let { body ->
                okio.Buffer().use { buffer -> body.writeTo(buffer);buffer.readUtf8() }
            }.orEmpty()
            capturedAccept = chain.request().header("Accept").orEmpty()
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"key":"sk-or-v1-provider-issued-test-key","user_id":"user-1"}""".toResponseBody(JSON))
                .build()
        }.build()

        val (key, user) = OpenRouterHttpClient(transport).exchange("AUTH-CODE", "PKCE-VERIFIER")

        val json = JSONObject(capturedBody)
        assertEquals("AUTH-CODE", json.getString("code"))
        assertEquals("PKCE-VERIFIER", json.getString("code_verifier"))
        assertEquals("S256", json.getString("code_challenge_method"))
        assertEquals("application/json", capturedAccept)
        assertEquals("sk-or-v1-provider-issued-test-key", key.concatToString())
        assertEquals("user-1", user)
        key.fill('\u0000')
    }

    @Test
    fun exchange_classifiesNetworkIoWithoutClaimingTheProviderRejectedAuthorization() {
        val transport = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()

        val failure = assertThrows(AiTransportException::class.java) {
            OpenRouterHttpClient(transport).exchange("AUTH-CODE", "PKCE-VERIFIER")
        }

        assertEquals("OPENROUTER_AUTH_NETWORK_IO", failure.reasonUid)
        assertTrue(failure.retryable)
    }

    @Test
    fun exchange_rejectsAResponseWithoutAUsableOpenRouterKey() {
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"user_id":"user-1"}""".toResponseBody(JSON))
                .build()
        }.build()

        val failure = assertThrows(AiTransportException::class.java) {
            OpenRouterHttpClient(transport).exchange("AUTH-CODE", "PKCE-VERIFIER")
        }

        assertEquals("OPENROUTER_AUTH_RESPONSE_INVALID", failure.reasonUid)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
