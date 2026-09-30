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
            val details = error.optJSONArray("details")
            val structuredField = details != null && (0 until details.length()).any { i ->
                val violations = details.optJSONObject(i)?.optJSONArray("fieldViolations") ?: return@any false
                (0 until violations.length()).any { j ->
                    violations.optJSONObject(j)?.optString("field")?.lowercase()?.endsWith("temperature") == true
                }
            }
            (parameter == "temperature" || structuredField) &&
                (message.contains("unsupported") || message.contains("does not support") ||
                    message.contains("not supported") || message.contains("deprecated") || message.contains("invalid value"))
        } catch (_: Exception) { false }
    }
}
