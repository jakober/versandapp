package de.versandapp.data.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
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

class OpenAiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimaler Client für die OpenAI **Responses API**
 * (https://platform.openai.com/docs/api-reference/responses). Bewusst per
 * OkHttp statt SDK, passend zum restlichen HTTP-Stack der App.
 *
 * Für die Sendungssuche wird das gehostete Web-Suche-Tool aktiviert. Da OpenAI
 * den Tool-Typ zwischen Versionen umbenannt hat ("web_search" ⇄
 * "web_search_preview"), probiert der Client bei einem entsprechenden Fehler
 * automatisch die andere Variante.
 */
class OpenAiApi(
    private val client: OkHttpClient = defaultClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Führt einen Responses-Request aus und liefert den zusammengesetzten
     * Antworttext (alle `output_text`-Blöcke).
     *
     * @param webSearch aktiviert das gehostete Web-Suche-Tool
     */
    suspend fun respond(
        apiKey: String,
        model: String,
        instructions: String,
        input: String,
        webSearch: Boolean,
    ): String = withContext(Dispatchers.IO) {
        if (webSearch) {
            try {
                request(apiKey, model, instructions, input, webSearchTool = "web_search")
            } catch (e: OpenAiException) {
                if (e.message?.contains("web_search", ignoreCase = true) == true) {
                    // Tool-Name in diesem API-Stand anders → mit Preview-Variante erneut
                    request(apiKey, model, instructions, input, webSearchTool = "web_search_preview")
                } else {
                    throw e
                }
            }
        } else {
            request(apiKey, model, instructions, input, webSearchTool = null)
        }
    }

    private fun request(
        apiKey: String,
        model: String,
        instructions: String,
        input: String,
        webSearchTool: String?,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("instructions", instructions)
            put("input", input)
            if (webSearchTool != null) {
                put(
                    "tools",
                    buildJsonArray {
                        add(buildJsonObject { put("type", webSearchTool) })
                    }
                )
            }
        }

        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = try {
            client.newCall(httpRequest).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    throw OpenAiException(
                        "OpenAI API antwortete mit HTTP ${response.code}: ${text?.take(300)}"
                    )
                }
                text ?: throw OpenAiException("Leere Antwort der OpenAI API")
            }
        } catch (e: IOException) {
            throw OpenAiException("Netzwerkfehler bei der OpenAI-Anfrage", e)
        }

        return extractText(responseBody)
    }

    /** Zieht alle `output_text`-Blöcke aus der Responses-Antwort. */
    private fun extractText(responseBody: String): String {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val output = root["output"]?.jsonArray ?: JsonArray(emptyList())
        val text = output.mapNotNull { item ->
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "message") return@mapNotNull null
            obj["content"]?.jsonArray.orEmpty().mapNotNull { part ->
                val partObj = part.jsonObject
                val type = partObj["type"]?.jsonPrimitive?.content
                if (type == "output_text" || type == "text") {
                    partObj["text"]?.jsonPrimitive?.content
                } else {
                    null
                }
            }.joinToString("\n")
        }.joinToString("\n").trim()

        if (text.isEmpty()) throw OpenAiException("Kein Text in der OpenAI-Antwort gefunden")
        return text
    }

    companion object {
        // Web-Suche + Modellantwort können deutlich länger dauern als normale Requests
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build()
    }
}
