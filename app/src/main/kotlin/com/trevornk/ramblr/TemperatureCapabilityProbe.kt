package com.trevornk.ramblr

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ConcurrentHashMap

/** Coalesces explicit configuration-save probes; never runs at application startup. */
internal object TemperatureCapabilityProbe {
    private val handler = Handler(Looper.getMainLooper())
    private val kinds = CopyOnWriteArraySet<ProviderKind>()
    private val active = ConcurrentHashMap<ProviderKind, CopyOnWriteArraySet<InFlightCall>>()
    @Volatile private var appContext: Context? = null
    private val drain = Runnable { probeSavedEntries() }

    fun schedule(context: Context, kind: ProviderKind) {
        if (kind == ProviderKind.LOCAL || kind == ProviderKind.ANTHROPIC) return
        appContext = context.applicationContext
        supersede(kind)
        kinds.add(kind)
        handler.removeCallbacks(drain)
        handler.postDelayed(drain, 100)
    }

    fun supersede(kind: ProviderKind) {
        if (kind == ProviderKind.LOCAL || kind == ProviderKind.ANTHROPIC) return
        active[kind]?.forEach(InFlightCall::cancel)
        kinds.remove(kind)
    }

    private fun probeSavedEntries() {
        val context = appContext ?: return
        val requested = kinds.toSet()
        kinds.removeAll(requested)
        val chain = ProviderChainStore.load(context)
        val cache = TemperatureCapabilityStore.forContext(context)
        chain.entries.filter { it.kind in requested }.forEach { entry ->
            val key = ProviderCredentialStore.get(context, entry.kind)
            if (key.isBlank()) return@forEach
            val (url, headers, body, identity) = when (entry.kind) {
                ProviderKind.GEMINI -> {
                    val url = GeminiCleanupProvider.endpointUrl(entry.model)
                    val headers = GeminiCleanupProvider.headers(key)
                    val body = GeminiCleanupProvider.buildRequestBody("Reply OK", "Reply OK")
                    Quad(url, headers, body.toString(), TemperatureCapabilityStore.identity(entry.kind, url, headers, entry.model, "", "", 0.0))
                }
                ProviderKind.OPENAI, ProviderKind.OMNIROUTE -> {
                    val base = if (entry.kind == ProviderKind.OMNIROUTE) OmniRoute.BASE_URL else entry.baseUrlOverride ?: PostProcessor.DEFAULT_BASE_URL
                    val url = PostProcessor.endpointUrl(base)
                    val headers = mapOf("Authorization" to "Bearer $key")
                    val body = PostProcessor.buildRequestBody("Reply OK", "Reply OK", entry.model)
                    Quad(url, headers, body.toString(), TemperatureCapabilityStore.identity(entry.kind, url, headers, entry.model, "", "stream=false", 0.0))
                }
                else -> return@forEach
            }
            if (cache.state(identity) != TemperatureCapabilityStore.State.UNKNOWN) return@forEach
            val probeCall = InFlightCall().apply { beginWork() }
            val activeForKind = active.computeIfAbsent(entry.kind) { CopyOnWriteArraySet() }
            activeForKind.add(probeCall)
            val observationSequence = cache.beginObservation(identity)
            RealCleanupHttpTransport.send(url, headers, body, CleanupStepTimeouts(1_500, 6_500, 7_000), probeCall) { outcome ->
                activeForKind.remove(probeCall)
                when (outcome) {
                    is CleanupHttpOutcome.Ok -> {
                        val parsed = when (entry.kind) {
                            ProviderKind.GEMINI -> GeminiCleanupProvider.parseResponse(outcome.body)
                            else -> PostProcessor.parseResponse(outcome.body)
                        }
                        if (!parsed.text.isNullOrBlank()) cache.record(identity, TemperatureCapabilityStore.State.SUPPORTED, observationSequence)
                        else cache.finishObservation(identity, observationSequence)
                    }
                    is CleanupHttpOutcome.HttpError -> {
                        if (TemperatureRejectionClassifier.isTemperatureRejection(outcome.body, outcome.code))
                            cache.record(identity, TemperatureCapabilityStore.State.UNSUPPORTED, observationSequence)
                        else cache.finishObservation(identity, observationSequence)
                    }
                    else -> cache.finishObservation(identity, observationSequence)
                }
            }
        }
    }

    private data class Quad(val url: String, val headers: Map<String, String>, val body: String, val identity: TemperatureCapabilityStore.Identity)
}
