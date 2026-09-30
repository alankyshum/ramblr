package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureCapabilityStoreTest {
    @Test fun persistsStateAndExpiresAtSevenDays() {
        var now = 1_000L
        var persisted: String? = null
        val store = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val identity = TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/v1/chat/completions",
            mapOf("Authorization" to "Bearer private"), "model", "reasoning", "options", 0.0)
        store.record(identity, TemperatureCapabilityStore.State.UNSUPPORTED)
        assertEquals(TemperatureCapabilityStore.State.UNSUPPORTED, store.state(identity))
        assertFalse(persisted!!.contains("private"))
        now += 7L * 24 * 60 * 60 * 1000
        assertEquals(TemperatureCapabilityStore.State.UNKNOWN, store.state(identity))
    }

    @Test fun configurationDimensionsIsolateEntries() {
        val base = TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/a",
            mapOf("Authorization" to "Bearer a"), "m", "r", "o", 0.0)
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/b", mapOf("Authorization" to "Bearer a"), "m", "r", "o", 0.0))
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/a", mapOf("Authorization" to "Bearer b"), "m", "r", "o", 0.0))
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/a", mapOf("Authorization" to "Bearer a"), "m2", "r", "o", 0.0))
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/a", mapOf("Authorization" to "Bearer a"), "m", "r2", "o", 0.0))
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/a", mapOf("Authorization" to "Bearer a"), "m", "r", "o2", 0.0))
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OPENAI, "https://host/a", mapOf("Authorization" to "Bearer a"), "m", "r", "o", 1.0))
        assertNotEquals(base, TemperatureCapabilityStore.identity(ProviderKind.OMNIROUTE, "https://host/a", mapOf("Authorization" to "Bearer a"), "m", "r", "o", 0.0))
    }

    @Test fun staleProbeCannotOverwriteNegativeRuntimeEvidence() {
        var now = 10_000L
        var persisted: String? = null
        val store = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val id = TemperatureCapabilityStore.Identity("digest")
        store.record(id, TemperatureCapabilityStore.State.UNSUPPORTED)
        now++
        store.record(id, TemperatureCapabilityStore.State.SUPPORTED)
        assertEquals(TemperatureCapabilityStore.State.UNSUPPORTED, store.state(id))
    }

    @Test fun expiredNegativeCanBeReplacedBySuccessfulRetest() {
        var now = 10_000L
        var persisted: String? = null
        val store = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val id = TemperatureCapabilityStore.Identity("same-config")
        store.record(id, TemperatureCapabilityStore.State.UNSUPPORTED)
        now += 7L * 24 * 60 * 60 * 1000
        assertEquals(TemperatureCapabilityStore.State.UNKNOWN, store.state(id))
        store.record(id, TemperatureCapabilityStore.State.SUPPORTED)
        assertEquals(TemperatureCapabilityStore.State.SUPPORTED, store.state(id))
    }

    @Test fun olderProbeRejectionCannotOverwriteNewerRuntimeSuccessAtSameClockTime() {
        var now = 20_000L
        var persisted: String? = null
        val probeStore = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val runtimeStore = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val id = TemperatureCapabilityStore.Identity("same-config")
        val olderProbe = probeStore.beginObservation(id)
        val newerRuntime = runtimeStore.beginObservation(id)
        runtimeStore.record(id, TemperatureCapabilityStore.State.SUPPORTED, newerRuntime)
        probeStore.record(id, TemperatureCapabilityStore.State.UNSUPPORTED, olderProbe)
        assertEquals(TemperatureCapabilityStore.State.SUPPORTED, runtimeStore.state(id))
    }

    @Test fun olderProbeSuccessCannotOverwriteNewerRuntimeRejectionAtSameClockTime() {
        val now = 20_000L
        var persisted: String? = null
        val probeStore = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val runtimeStore = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val id = TemperatureCapabilityStore.Identity("same-config")
        val olderProbe = probeStore.beginObservation(id)
        val newerRuntime = runtimeStore.beginObservation(id)
        runtimeStore.record(id, TemperatureCapabilityStore.State.UNSUPPORTED, newerRuntime)
        probeStore.record(id, TemperatureCapabilityStore.State.SUPPORTED, olderProbe)
        assertEquals(TemperatureCapabilityStore.State.UNSUPPORTED, runtimeStore.state(id))
    }

    @Test fun malformedFutureAndOverCapacityStateStayBoundedAndUnknown() {
        var now = 10_000L
        var persisted: String? = "not-json"
        val store = TemperatureCapabilityStore({ persisted }, { persisted = it }, { now })
        val future = TemperatureCapabilityStore.Identity("future")
        persisted = org.json.JSONObject().put("schema", 1).put("entries", org.json.JSONArray().put(
            org.json.JSONObject().put("key", "future").put("state", "UNSUPPORTED").put("at", now + 1),
        )).toString()
        assertEquals(TemperatureCapabilityStore.State.UNKNOWN, store.state(future))
        for (i in 0 until 130) {
            store.record(TemperatureCapabilityStore.Identity("entry-$i"), TemperatureCapabilityStore.State.SUPPORTED)
            now++
        }
        val entries = org.json.JSONObject(persisted!!).getJSONArray("entries")
        assertEquals(128, entries.length())
        assertEquals(TemperatureCapabilityStore.State.UNKNOWN, store.state(TemperatureCapabilityStore.Identity("entry-0")))
        assertEquals(TemperatureCapabilityStore.State.SUPPORTED, store.state(TemperatureCapabilityStore.Identity("entry-129")))
    }
}
