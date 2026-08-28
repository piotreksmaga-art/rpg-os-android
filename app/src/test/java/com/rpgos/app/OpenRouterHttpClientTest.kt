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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.InetAddress
import okhttp3.Dns
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterHttpClientTest {
    @Test
    fun openRouterDns_usesEncryptedFallbackOnlyAfterSystemFailureForTheOfficialHost() {
        val resolved=InetAddress.getByAddress(byteArrayOf(104,18,2,115))
        var fallbackCalls=0
        val dns=OpenRouterFallbackDns(
            Dns{throw UnknownHostException(it)},
            Dns{fallbackCalls++;listOf(resolved)}
        )

        assertEquals(listOf(resolved),dns.lookup("openrouter.ai"))
        assertEquals(1,fallbackCalls)
    }

    @Test
    fun openRouterDns_neverBypassesSystemDnsForAnUnrelatedHost() {
        var fallbackCalls=0
        val dns=OpenRouterFallbackDns(
            Dns{throw UnknownHostException(it)},
            Dns{fallbackCalls++;emptyList()}
        )

        assertThrows(UnknownHostException::class.java){dns.lookup("example.com")}
        assertEquals(0,fallbackCalls)
    }

    @Test
    fun exchange_usesTheOfficialPkceContractAndAcceptsAValidKeyResponse() {
        var capturedBody = ""
        var capturedAccept = ""
        var capturedUserAgent = ""
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            capturedBody = chain.request().body?.let { body ->
                okio.Buffer().use { buffer -> body.writeTo(buffer);buffer.readUtf8() }
            }.orEmpty()
            capturedAccept = chain.request().header("Accept").orEmpty()
            capturedUserAgent = chain.request().header("User-Agent").orEmpty()
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
        assertTrue(capturedUserAgent.startsWith("RPG-OS-Android/"))
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

    @Test fun exchange_reportsTheActualAndroidNetworkLayer(){
        assertEquals("OPENROUTER_AUTH_DNS",openRouterIoReason(UnknownHostException("openrouter.ai")))
        assertEquals("OPENROUTER_AUTH_TLS",openRouterIoReason(SSLHandshakeException("handshake")))
        assertEquals("OPENROUTER_AUTH_TIMEOUT",openRouterIoReason(SocketTimeoutException("timeout")))
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
