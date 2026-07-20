package de.versandapp.data.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ClaudeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimaler Client für die Anthropic Messages API (https://docs.anthropic.com).
 * Bewusst per OkHttp statt SDK, passend zum restlichen HTTP-Stack der App.
 */
class ClaudeApi(
    private val client: OkHttpClient = defaultClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Führt einen Messages-Request aus und liefert die geparste Antwort.
     *
     * @param tools optionale Server-Tools, z. B. Web-Suche
     * @param outputFormat optionales JSON-Schema für strukturierte Ausgabe
     *   (nicht zusammen mit Web-Suche verwenden – deren Zitate sind damit
     *   inkompatibel)
     */
    suspend fun createMessage(
        apiKey: String,
        model: String,
        maxTokens: Int,
        system: String,
        userText: String,
        tools: JsonArray? = null,
        outputFormat: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", system)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", userText)
                        }
                    )
                }
            )
            if (tools != null) put("tools", tools)
            if (outputFormat != null) {
                put(
                    "output_config",
                    buildJsonObject { put("format", outputFormat) }
                )
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    throw ClaudeException(
                        "Claude API antwortete mit HTTP ${response.code}: ${text?.take(300)}"
                    )
                }
                text ?: throw ClaudeException("Leere Antwort der Claude API")
            }
        } catch (e: IOException) {
            throw ClaudeException("Netzwerkfehler bei der Claude-Anfrage", e)
        }

        json.parseToJsonElement(responseBody).jsonObject
    }

    /** Verkettet alle Text-Blöcke der Antwort (Web-Search-Blöcke etc. werden übersprungen). */
    fun textContent(response: JsonObject): String =
        response["content"]?.jsonArray.orEmpty()
            .mapNotNull { block ->
                val obj = block.jsonObject
                if (obj["type"]?.jsonPrimitive?.content == "text") {
                    obj["text"]?.jsonPrimitive?.content
                } else {
                    null
                }
            }
            .joinToString("\n")

    companion object {
        // Web-Suche + Modellantwort können deutlich länger dauern als normale Requests
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }
}
