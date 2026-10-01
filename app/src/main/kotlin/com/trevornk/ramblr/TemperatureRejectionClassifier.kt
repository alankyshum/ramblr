package com.trevornk.ramblr

import org.json.JSONObject

/** Only explicit structured temperature-parameter errors are negative capability evidence. */
internal object TemperatureRejectionClassifier {
    fun isTemperatureRejection(body: String, httpCode: Int = 400): Boolean {
        if (httpCode != 400) return false
        return try {
            val error = JSONObject(body).optJSONObject("error") ?: return false
            val message = error.optString("message").lowercase()
            val parameter = error.optString("param").lowercase()
            val type = error.optString("type").lowercase()
            val code = error.optString("code").lowercase()
            val details = error.optJSONArray("details")
            val openAi = parameter == "temperature" &&
                (code == "unsupported_value" || code == "unsupported_parameter" ||
                    type == "unsupported_value" || type == "unsupported_parameter") &&
                (message.contains("unsupported") || message.contains("not supported"))
            val google = details != null && (0 until details.length()).any { i ->
                val violations = details.optJSONObject(i)?.optJSONArray("fieldViolations") ?: return@any false
                (0 until violations.length()).any { j ->
                    val violation = violations.optJSONObject(j) ?: return@any false
                    val field = violation.optString("field")
                    val description = violation.optString("description").lowercase()
                    field in setOf("generationConfig.temperature", "generation_config.temperature") &&
                        (description.contains("unsupported") || description.contains("not supported"))
                }
            }
            openAi || google
        } catch (_: Exception) { false }
    }
}
