package com.trevornk.ramblr

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Persistent, private cache for temperature capability. Only a digest and state metadata are stored. */
internal class TemperatureCapabilityStore internal constructor(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val now: () -> Long,
) {
    enum class State { UNKNOWN, SUPPORTED, UNSUPPORTED }
    data class Identity(val digest: String)

    fun state(identity: Identity): State = synchronized(storeLock) {
        val entries = readEntries()
        val item = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .firstOrNull { it.optString("key") == identity.digest } ?: return@synchronized State.UNKNOWN
        val timestamp = item.optLong("at", -1L)
        if (timestamp <= 0 || timestamp > now() || now() - timestamp >= TTL_MS) return@synchronized State.UNKNOWN
        runCatching { State.valueOf(item.getString("state")) }.getOrDefault(State.UNKNOWN)
    }

    fun record(identity: Identity, state: State) {
        record(identity, state, beginObservation(identity))
    }

    /** Sequence is process-local: no in-flight request can survive restart, so it is not persisted. */
    fun beginObservation(identity: Identity): Long = synchronized(storeLock) {
        observationCounter.incrementAndGet().also { latestObservations[identity.digest] = it }
    }

    fun finishObservation(identity: Identity, sequence: Long) = synchronized(storeLock) {
        if (latestObservations[identity.digest] == sequence) latestObservations.remove(identity.digest)
    }

    fun record(identity: Identity, state: State, sequence: Long) = synchronized(storeLock) {
        if (latestObservations[identity.digest] != sequence) return@synchronized
        latestObservations.remove(identity.digest)
        val entries = readEntries()
        val existing = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .firstOrNull { it.optString("key") == identity.digest }
        val timestamp = existing?.optLong("at", -1L) ?: -1L
        val age = now() - timestamp
        val negativeIsFresh = timestamp > 0 && timestamp <= now() && age < TTL_MS
        if (existing?.optString("state") == State.UNSUPPORTED.name && state == State.SUPPORTED && negativeIsFresh)
            return@synchronized
        val positiveIsFresh = timestamp > 0 && timestamp <= now() && age < TTL_MS / 2
        if (existing?.optString("state") == State.SUPPORTED.name && state == State.SUPPORTED && positiveIsFresh)
            return@synchronized
        val updated = JSONArray()
        (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
            .filterNot { it.optString("key") == identity.digest }.forEach(updated::put)
        updated.put(JSONObject().put("key", identity.digest).put("state", state.name).put("at", now()))
        while (updated.length() > MAX_ENTRIES) {
            val oldest = (0 until updated.length()).minByOrNull { updated.optJSONObject(it)?.optLong("at") ?: 0L } ?: break
            val trimmed = JSONArray()
            (0 until updated.length()).filterNot { it == oldest }.forEach { trimmed.put(updated.get(it)) }
            while (updated.length() > 0) updated.remove(updated.length() - 1)
            (0 until trimmed.length()).forEach { updated.put(trimmed.get(it)) }
        }
        write(JSONObject().put("schema", SCHEMA).put("entries", updated).toString())
    }

    private fun readEntries(): JSONArray {
        return try {
            val root = read()?.let(::JSONObject) ?: return JSONArray()
            if (root.optInt("schema") != SCHEMA) return JSONArray()
            root.optJSONArray("entries") ?: JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    companion object {
        private const val SCHEMA = 1
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_ENTRIES = 128
        private val storeLock = Any()
        private val observationCounter = AtomicLong()
        private val latestObservations = mutableMapOf<String, Long>()
        fun forContext(context: Context): TemperatureCapabilityStore {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("ramblr", Context.MODE_PRIVATE)
            return TemperatureCapabilityStore(
                { prefs.getString("temperature_capabilities", null) },
                { prefs.edit().putString("temperature_capabilities", it).apply() },
                System::currentTimeMillis,
            )
        }

        fun identity(provider: ProviderKind, endpoint: String, headers: Map<String, String>, model: String,
                     reasoning: String, options: String, temperature: Double, entryId: String = ""): Identity {
            val canonical = JSONArray().put(provider.name).put(endpoint)
                .put(JSONArray(headers.toSortedMap().map { "${it.key}:${it.value}" }))
                .put(model).put(reasoning).put(options).put(temperature).put(entryId).toString()
            return Identity(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) })
        }
    }
}
