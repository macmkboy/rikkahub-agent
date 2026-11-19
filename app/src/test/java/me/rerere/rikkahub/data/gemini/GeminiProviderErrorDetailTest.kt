package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers `parseErrorMessage`'s rendering of `error.status` and `error.details[]`
 * (`fieldViolations` in particular) for Cloud Code Assist's `INVALID_ARGUMENT` responses. It is a
 * file-private top-level function, so reflection targets the `GeminiProviderKt` facade class like
 * [GeminiProviderRequestTest].
 */
class GeminiProviderErrorDetailTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String?): String? {
        val method = geminiProviderKt.getDeclaredMethod(
            "parseErrorMessage",
            String::class.java,
            Json::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, body, json) as String?
    }

    @Test
    fun `a fieldViolations entry surfaces the offending field and description`() {
        val body = """
            {
              "error": {
                "code": 400,
                "message": "Request contains an invalid argument.",
                "status": "INVALID_ARGUMENT",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.BadRequest",
                    "fieldViolations": [
                      {
                        "field": "generationConfig.thinkingConfig.thinkingLevel",
                        "description": "Invalid value at 'thinking_level'"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val message = parse(body)
        assertTrue(message?.startsWith("Cloud Code Assist error (400): Request contains an invalid argument.") == true)
        assertTrue(message?.contains("status=INVALID_ARGUMENT") == true)
        assertTrue(message?.contains("field=generationConfig.thinkingConfig.thinkingLevel") == true)
        assertTrue(message?.contains("description=Invalid value at 'thinking_level'") == true)
    }

    @Test
    fun `a non-BadRequest detail renders its type plus scalar fields`() {
        val body = """
            {
              "error": {
                "code": 400,
                "message": "denied",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "API_KEY_INVALID",
                    "domain": "googleapis.com"
                  }
                ]
              }
            }
        """.trimIndent()
        val message = parse(body)
        assertTrue(message?.contains("type.googleapis.com/google.rpc.ErrorInfo") == true)
        assertTrue(message?.contains("reason=API_KEY_INVALID") == true)
    }

    @Test
    fun `an empty details array behaves like today - message unchanged`() {
        val body = """{"error":{"code":400,"message":"invalid argument","details":[]}}"""
        assertEquals("Cloud Code Assist error (400): invalid argument", parse(body))
    }

    @Test
    fun `details in an unexpected shape degrade to just the prefix rather than throw`() {
        val body = """{"error":{"code":400,"message":"invalid argument","details":"not-an-array"}}"""
        assertEquals("Cloud Code Assist error (400): invalid argument", parse(body))
    }

    @Test
    fun `a fieldViolations entry missing both field and description is skipped, not thrown`() {
        val body = """
            {"error":{"code":400,"message":"invalid argument","details":[
                {"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{}]}
            ]}}
        """.trimIndent()
        val message = parse(body)
        assertTrue(message?.startsWith("Cloud Code Assist error (400): invalid argument") == true)
    }

    @Test
    fun `a non-JSON body degrades to null rather than throw`() {
        assertNull(parse("<html>upstream down</html>"))
    }

    @Test
    fun `a null body still returns null`() {
        assertNull(parse(null))
    }

    @Test
    fun `resolveGeminiEmbeddedStreamError returns HttpException with statusCode for SSE 400 error`() {
        val data = """{"error":{"code":400,"message":"Model gemini-4-hydrogen-sonar not supported","status":"INVALID_ARGUMENT"}}"""
        val errorObj = json.parseToJsonElement(data).jsonObject["error"]!!.jsonObject
        val (exception, classification) = resolveGeminiEmbeddedStreamError(data, errorObj, json)
        assertEquals(400, exception.statusCode)
        assertEquals(data, exception.rawBody)
        assertTrue(exception.message.orEmpty().contains("gemini-4-hydrogen-sonar"))
        assertFalse(classification.retryable)
    }

    @Test
    fun `resolveGeminiEmbeddedStreamError preserves 429 RESOURCE_EXHAUSTED for GenerationLoop`() {
        val data = """{"error":{"code":429,"message":"You have exhausted your capacity on this model.","status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"RATE_LIMIT_EXCEEDED"}]}}"""
        val errorObj = json.parseToJsonElement(data).jsonObject["error"]!!.jsonObject
        val (exception, classification) = resolveGeminiEmbeddedStreamError(data, errorObj, json)
        assertEquals(429, exception.statusCode)
        assertTrue(exception.message.orEmpty().contains("RESOURCE_EXHAUSTED"))
        assertFalse(classification.retryable)
    }

    @Test
    fun `tagErrorWithAccount prefixes account email to HttpException message`() {
        val account = GeminiAccount(
            id = "id-1",
            name = "Test User",
            email = "user@example.com",
            projectId = "proj-1",
            accessToken = "tok",
            refreshToken = "ref",
            expiresAt = Long.MAX_VALUE,
        )
        val original = me.rerere.ai.util.HttpException("Resource exhausted", statusCode = 429, rawBody = "raw")
        val tagged = tagErrorWithAccount(original, account)
        assertEquals("[user@example.com] Resource exhausted", tagged.message)
        assertTrue(tagged is me.rerere.ai.util.HttpException)
        assertEquals(429, (tagged as me.rerere.ai.util.HttpException).statusCode)
        assertEquals("raw", tagged.rawBody)
    }

    @Test
    fun `appendGeminiTrace keeps HttpException status and appends trace`() {
        val original = me.rerere.ai.util.HttpException("Resource exhausted", statusCode = 429, rawBody = "raw")
        val traced = appendGeminiTrace(original, "model=m, body=10KB", listOf("a@daily=429"))
        assertTrue(traced is me.rerere.ai.util.HttpException)
        assertEquals(429, (traced as me.rerere.ai.util.HttpException).statusCode)
        assertEquals("raw", traced.rawBody)
        assertTrue(traced.message.orEmpty().startsWith("Resource exhausted"))
        assertTrue(traced.message.orEmpty().contains("[trace] model=m, body=10KB"))
        assertTrue(traced.message.orEmpty().contains("[attempts] a@daily=429"))
    }

    @Test
    fun `describeGeminiRequest counts tools system chars and contents`() {
        val inner = json.parseToJsonElement(
            """{"systemInstruction":{"parts":[{"text":"${"x".repeat(2500)}"}]},""" +
                """"contents":[{"role":"user"},{"role":"model"}],""" +
                """"generationConfig":{"thinkingConfig":{"thinkingLevel":"high"}},""" +
                """"tools":[{"functionDeclarations":[{"name":"a"},{"name":"b"}]},{"googleSearch":{}}]}"""
        ).jsonObject
        val trace = describeGeminiRequest("x".repeat(8192), inner, "gemini-3.8-flash")
        assertTrue(trace, trace.contains("model=gemini-3.8-flash"))
        assertTrue(trace, trace.contains("body=8KB"))
        assertTrue(trace, trace.contains("tools=2"))
        assertTrue(trace, trace.contains("system=2K chars"))
        assertTrue(trace, trace.contains("contents=2"))
        assertTrue(trace, trace.contains("thinking=high"))
    }

    @Test
    fun `only quota and credential failures trigger account failover`() {
        assertTrue(shouldFailoverGeminiAccount(429))
        assertTrue(shouldFailoverGeminiAccount(401))
        assertTrue(shouldFailoverGeminiAccount(403))
        assertFalse(shouldFailoverGeminiAccount(400))
        assertFalse(shouldFailoverGeminiAccount(null))
    }

    @Test
    fun `endpoint labels distinguish daily sandbox and prod`() {
        assertEquals("daily", geminiEndpointLabel(ANTIGRAVITY_DAILY_ENDPOINT))
        assertEquals("sandbox", geminiEndpointLabel(ANTIGRAVITY_DAILY_SANDBOX_ENDPOINT))
        assertEquals("prod", geminiEndpointLabel(GeminiAccountRepository.CODE_ASSIST_ENDPOINT))
    }
}
