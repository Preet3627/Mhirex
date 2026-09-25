package com.mhirex.editor.services

import android.content.Context
import org.json.JSONObject

/**
 * Small durable hand-off for proxy results.
 *
 * LocalBroadcastManager is only a wake-up hint: a project-import proxy can finish before the
 * editor has registered its receiver. Keeping the terminal result in preferences means the editor
 * can reconcile it after loading the project instead of silently losing the generated artifact.
 */
data class ProxyResultRecord(
    val requestId: String,
    val sessionId: String,
    val sourceUri: String,
    val dependencyId: String,
    val proxyUri: String,
    val createdAt: Long = System.currentTimeMillis(),
)

object ProxyResultStore {
    private const val PREFS_NAME = "mhirex_proxy_results"
    private const val KEY_PREFIX = "result_"
    private const val MAX_RESULTS = 64

    fun put(context: Context, record: ProxyResultRecord) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(prefs) {
            val json = JSONObject().apply {
                put("requestId", record.requestId)
                put("sessionId", record.sessionId)
                put("sourceUri", record.sourceUri)
                put("dependencyId", record.dependencyId)
                put("proxyUri", record.proxyUri)
                put("createdAt", record.createdAt)
            }
            val editor = prefs.edit().putString(KEY_PREFIX + record.requestId, json.toString())
            val existing = prefs.all.keys
                .filter { it.startsWith(KEY_PREFIX) }
                .mapNotNull { key ->
                    prefs.getString(key, null)?.let { value -> key to value }
                }
            if (existing.size >= MAX_RESULTS) {
                existing.sortedBy { (_, value) ->
                    runCatching { JSONObject(value).optLong("createdAt", 0L) }.getOrDefault(0L)
                }.take(existing.size - MAX_RESULTS + 1).forEach { (key, _) ->
                    editor.remove(key)
                }
            }
            // commit() makes the record visible before the broadcast is sent.
            editor.commit()
        }
    }

    /** Atomically claims results for one editor session, leaving other sessions untouched. */
    fun consumeForSession(context: Context, sessionId: String): List<ProxyResultRecord> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(prefs) {
            val selected = prefs.all
                .filterKeys { it.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) ->
                    val raw = value as? String ?: return@mapNotNull null
                    val record = runCatching { parse(JSONObject(raw)) }.getOrNull()
                    if (record != null && (record.sessionId == sessionId || record.sessionId.isBlank())) {
                        key to record
                    } else {
                        null
                    }
                }
            if (selected.isNotEmpty()) {
                val editor = prefs.edit()
                selected.forEach { (key, _) -> editor.remove(key) }
                editor.commit()
            }
            return selected.map { it.second }
        }
    }

    fun discard(context: Context, requestId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_PREFIX + requestId).commit()
    }

    private fun parse(json: JSONObject): ProxyResultRecord? {
        val requestId = json.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        val sessionId = json.optString("sessionId")
        val sourceUri = json.optString("sourceUri").takeIf { it.isNotBlank() } ?: return null
        val dependencyId = json.optString("dependencyId").takeIf { it.isNotBlank() } ?: return null
        val proxyUri = json.optString("proxyUri").takeIf { it.isNotBlank() } ?: return null
        return ProxyResultRecord(
            requestId = requestId,
            sessionId = sessionId,
            sourceUri = sourceUri,
            dependencyId = dependencyId,
            proxyUri = proxyUri,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}
