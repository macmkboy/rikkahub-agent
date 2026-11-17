package me.rerere.ai.provider.providers.google

import java.io.IOException
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleProviderErrorTest {

    @Test
    fun `preserves transport throwable when provided`() {
        val io = IOException("connection reset")
        val resolved = resolveGoogleStreamFailure(
            t = io,
            responseCode = 500,
            responseMessage = "Internal Server Error",
            bodyStr = """{"error":{"message":"ignored"}}""",
        )
        assertSame(io, resolved)
    }

    @Test
    fun `parses json object error into HttpException with statusCode`() {
        val body = """{"error":{"code":400,"message":"Invalid thinkingLevel","status":"INVALID_ARGUMENT"}}"""
        val resolved = resolveGoogleStreamFailure(
            t = null,
            responseCode = 400,
            responseMessage = "Bad Request",
            bodyStr = body,
        )
        assertTrue(resolved is HttpException)
        val http = resolved as HttpException
        assertEquals(400, http.statusCode)
        assertEquals(body, http.rawBody)
        assertTrue(http.message.orEmpty().contains("Invalid thinkingLevel"))
    }

    @Test
    fun `parses json array error from streamGenerateContent into HttpException with statusCode`() {
        val body = """[{"error":{"code":400,"message":"Model gemini-4-hydrogen-sonar not supported","status":"INVALID_ARGUMENT"}}]"""
        val resolved = resolveGoogleStreamFailure(
            t = null,
            responseCode = 400,
            responseMessage = "Bad Request",
            bodyStr = body,
        )
        assertTrue(resolved is HttpException)
        val http = resolved as HttpException
        assertEquals(400, http.statusCode)
        assertEquals(body, http.rawBody)
        assertTrue(http.message.orEmpty().contains("gemini-4-hydrogen-sonar"))
    }

    @Test
    fun `preserves 429 statusCode and rawBody for quota exhausted errors`() {
        val body = """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}"""
        val resolved = resolveGoogleStreamFailure(
            t = null,
            responseCode = 429,
            responseMessage = "Too Many Requests",
            bodyStr = body,
        )
        assertTrue(resolved is HttpException)
        val http = resolved as HttpException
        assertEquals(429, http.statusCode)
        assertTrue(http.message.orEmpty().contains("RESOURCE_EXHAUSTED"))
    }

    @Test
    fun `falls back to HttpException with statusCode for non-json or empty error body`() {
        val htmlResolved = resolveGoogleStreamFailure(
            t = null,
            responseCode = 502,
            responseMessage = "Bad Gateway",
            bodyStr = "<html><body>502 Bad Gateway</body></html>",
        )
        assertTrue(htmlResolved is HttpException)
        assertEquals(502, (htmlResolved as HttpException).statusCode)
        assertTrue(htmlResolved.message.orEmpty().contains("502 Bad Gateway"))

        val emptyResolved = resolveGoogleStreamFailure(
            t = null,
            responseCode = 404,
            responseMessage = "Not Found",
            bodyStr = "",
        )
        assertTrue(emptyResolved is HttpException)
        assertEquals(404, (emptyResolved as HttpException).statusCode)
        assertTrue(emptyResolved.message.orEmpty().contains("Not Found"))
    }
}
