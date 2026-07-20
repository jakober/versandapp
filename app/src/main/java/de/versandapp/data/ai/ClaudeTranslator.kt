package de.versandapp.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Übersetzt die (oft englischen) Sendungsverlauf-Texte vorab per Claude Haiku
 * ins Deutsche – ein kurzer, günstiger Aufruf pro Sendung, alle Texte auf
 * einmal. Schlägt der Aufruf fehl, bleiben die Originaltexte erhalten.
 */
class ClaudeTranslator(
    private val api: ClaudeApi = ClaudeApi(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Gibt die Texte in derselben Reihenfolge auf Deutsch zurück (Fallback: Original). */
    suspend fun toGerman(apiKey: String, texts: List<String>): List<String> {
        if (apiKey.isBlank() || texts.isEmpty()) return texts

        return runCatching {
            val input = buildJsonArray { texts.forEach { add(it) } }.toString()
            val response = api.createMessage(
                apiKey = apiKey,
                model = MODEL,
                maxTokens = 1024,
                system = SYSTEM_PROMPT,
                userText = input,
                outputFormat = json.parseToJsonElement(OUTPUT_SCHEMA).jsonObject,
            )
            val root = json.parseToJsonElement(api.textContent(response)).jsonObject
            val translated = root["translations"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: return@runCatching texts
            // Nur übernehmen, wenn die Anzahl passt – sonst lieber Originale behalten
            if (translated.size == texts.size) translated else texts
        }.getOrDefault(texts)
    }

    private companion object {
        const val MODEL = "claude-haiku-4-5"

        val SYSTEM_PROMPT = """
            Übersetze die Paket-Sendungsverlauf-Texte ins Deutsche. Du erhältst
            ein JSON-Array mit Texten und gibst die Übersetzungen in genau
            derselben Reihenfolge und Anzahl zurück. Bereits deutsche Texte
            unverändert lassen. Kurz und natürlich formulieren, wie es ein
            Paketdienst schreiben würde.
        """.trimIndent()

        val OUTPUT_SCHEMA = """
        {
          "type": "json_schema",
          "schema": {
            "type": "object",
            "properties": {
              "translations": { "type": "array", "items": { "type": "string" } }
            },
            "required": ["translations"],
            "additionalProperties": false
          }
        }
        """.trimIndent()
    }
}
