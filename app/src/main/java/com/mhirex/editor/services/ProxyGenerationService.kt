package com.mhirex.editor.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mhirex.editor.Branding
import com.mhirex.editor.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Generates low-resolution playback proxies without coupling proxy lifetime to the editor UI.
 *
 * Requests carry caller-owned session/request identities. A newer request for the same
 * session/dependency supersedes the older one, while cancellation is scoped to that request's Job
 * and therefore to its own FFmpeg session.
 */
class ProxyGenerationService : Service() {

    private val TAG = "ProxyGenerationService"
    private val CHANNEL_ID = "ProxyChannel"
    // ExportService uses 1002 for its completion notification; never share IDs with it.
    private val NOTIFICATION_ID = 1003

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val requestLock = Any()
    private val activeJobs = mutableSetOf<Job>()
    private val jobsByRequestId = mutableMapOf<String, Job>()
    private val jobsByDependency = mutableMapOf<String, Job>()
    private val latestRequestByDependency = mutableMapOf<String, String>()
    private var latestStartId = 0
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    companion object {
        const val ACTION_PROXY_GENERATED = "com.mhirex.editor.ACTION_PROXY_GENERATED"
        const val ACTION_PROXY_FAILED = "com.mhirex.editor.ACTION_PROXY_FAILED"
        const val ACTION_CANCEL_PROXY = "com.mhirex.editor.ACTION_CANCEL_PROXY"
        const val EXTRA_SOURCE_URI = "extra_source_uri"
        const val EXTRA_PROXY_URI = "extra_proxy_uri"
        const val EXTRA_DEPENDENCY_ID = "extra_dependency_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_ERROR = "extra_error"

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "mpeg", "mpg", "ts", "m2ts"
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ffmpegEngine = FFmpegRenderEngine.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent.action == ACTION_CANCEL_PROXY) {
            cancelRequest(intent.getStringExtra(EXTRA_REQUEST_ID), startId)
            return START_NOT_STICKY
        }

        // A foreground-service start must be satisfied promptly, including for a malformed or
        // duplicate request. Work validation happens after this call so the platform never sees a
        // started foreground service without a notification.
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Generating optimized playback proxy..."))
        } catch (e: Exception) {
            Log.e(TAG, "Could not start proxy foreground service", e)
            rejectStart(startId)
            return START_NOT_STICKY
        }

        val sourceUriStr = intent.getStringExtra(EXTRA_SOURCE_URI)?.trim()
        val dependencyId = intent.getStringExtra(EXTRA_DEPENDENCY_ID)?.trim()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)?.trim().orEmpty()
        if (sourceUriStr.isNullOrBlank() || dependencyId.isNullOrBlank() || requestId.isBlank()) {
            Log.w(TAG, "Ignoring proxy request with missing source, dependency, session, or request id")
            rejectStart(startId)
            return START_NOT_STICKY
        }

        val sourceUri = try {
            Uri.parse(sourceUriStr)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring invalid proxy source URI: $sourceUriStr", e)
            rejectStart(startId)
            return START_NOT_STICKY
        }
        if (sourceUri.scheme !in setOf("content", "file") || sourceUri.path.isNullOrBlank()) {
            Log.w(TAG, "Ignoring proxy source with unsupported URI: $sourceUri")
            rejectStart(startId)
            return START_NOT_STICKY
        }

        val dependencyKey = dependencyKey(sessionId, dependencyId)
        val supersededJob: Job?
        val duplicate: Boolean
        synchronized(requestLock) {
            latestStartId = maxOf(latestStartId, startId)
            duplicate = jobsByRequestId.containsKey(requestId)
            if (duplicate) {
                supersededJob = null
            } else {
                supersededJob = jobsByDependency[dependencyKey]
                latestRequestByDependency[dependencyKey] = requestId
            }
        }
        if (duplicate) {
            Log.w(TAG, "Ignoring duplicate proxy request: $requestId")
            rejectStart(startId)
            return START_NOT_STICKY
        }
        // Cancelling the old job is safe even when it is still blocked in a provider copy; the
        // coroutine checks cancellation at each chunk boundary and its finally owns cleanup.
        supersededJob?.cancel()

        val proxyCache = File(cacheDir, "proxies")
        if (!proxyCache.exists() && !proxyCache.mkdirs()) {
            Log.e(TAG, "Could not create proxy cache: ${proxyCache.absolutePath}")
            rejectStart(startId)
            return START_NOT_STICKY
        }
        val workFile = try {
            File.createTempFile("proxy_work_${safeId(requestId)}_", ".mp4", proxyCache)
        } catch (e: Exception) {
            Log.e(TAG, "Could not allocate proxy output", e)
            rejectStart(startId)
            return START_NOT_STICKY
        }
        val finalFile = File(proxyCache, "proxy_${safeId(requestId)}_${System.currentTimeMillis()}.mp4")

        lateinit var task: Job
        task = serviceScope.launch(start = CoroutineStart.LAZY) {
            var temporarySource: File? = null
            var committed = false
            try {
                // FFmpegKit can consume a SAF path, but some providers return a value that its
                // demuxers cannot open. Resolve it once and keep a cache copy as a fallback.
                val resolvedSource = getSafPath(sourceUri)
                if (resolvedSource == null) {
                    broadcastFailure(sourceUriStr, dependencyId, sessionId, requestId, "Could not read video source")
                    return@launch
                }
                temporarySource = resolvedSource.temporaryFile

                val result = ffmpegEngine.generateScrubProxy(
                    sourceFilePath = resolvedSource.path,
                    outputFilePath = workFile.absolutePath
                )

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        if (!workFile.isFile || workFile.length() == 0L) {
                            Log.e(TAG, "FFmpeg reported success but proxy is missing/empty: ${workFile.absolutePath}")
                            broadcastFailure(sourceUriStr, dependencyId, sessionId, requestId, "Proxy output is empty")
                        } else {
                            // Commit the completed file under its final name only after a successful
                            // render. The service never broadcasts a partially written .mp4 path.
                            val accepted = synchronized(requestLock) {
                                if (latestRequestByDependency[dependencyKey] != requestId) {
                                    false
                                } else if (finalFile.exists() && !finalFile.delete()) {
                                    false
                                } else {
                                    workFile.renameTo(finalFile)
                                }
                            }
                            if (accepted) {
                                committed = true
                                val proxyUri = Uri.fromFile(finalFile)
                                val record = ProxyResultRecord(
                                    requestId = requestId,
                                    sessionId = sessionId,
                                    sourceUri = sourceUriStr,
                                    dependencyId = dependencyId,
                                    proxyUri = proxyUri.toString(),
                                )
                                ProxyResultStore.put(this@ProxyGenerationService, record)
                                Log.d(TAG, "Proxy generated successfully: $proxyUri")
                                broadcastSuccess(record)
                            } else {
                                Log.d(TAG, "Discarding stale proxy result for $dependencyId")
                                deleteQuietly(finalFile, "stale proxy")
                            }
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        Log.e(TAG, "Proxy generation failed: ${result.error}")
                        broadcastFailure(sourceUriStr, dependencyId, sessionId, requestId, result.error)
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        Log.w(TAG, "Proxy generation cancelled")
                        broadcastFailure(sourceUriStr, dependencyId, sessionId, requestId, "Proxy generation cancelled")
                    }
                }
            } catch (e: CancellationException) {
                broadcastFailure(sourceUriStr, dependencyId, sessionId, requestId, "Proxy generation cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Proxy generation exception", e)
                broadcastFailure(sourceUriStr, dependencyId, sessionId, requestId, e.message ?: "Proxy generation failed")
            } finally {
                // Work files are always owned by this request. A committed final artifact is
                // retained for the consumer; an uncommitted work file is never leaked.
                if (!committed) deleteQuietly(workFile, "proxy work file")
                deleteQuietly(temporarySource, "temporary proxy source")
                finishTask(task, requestId, dependencyKey)
            }
        }

        synchronized(requestLock) {
            // Another start command cannot interleave on the main service thread, but retain the
            // identity check so the map never loses ownership of a running request.
            if (latestRequestByDependency[dependencyKey] != requestId) {
                deleteQuietly(workFile, "superseded proxy work file")
                task.cancel()
            } else {
                activeJobs += task
                jobsByRequestId[requestId] = task
                jobsByDependency[dependencyKey] = task
            }
        }
        task.start()
        return START_NOT_STICKY
    }

    private data class ResolvedSource(
        val path: String,
        val temporaryFile: File? = null,
    )

    private fun dependencyKey(sessionId: String, dependencyId: String): String =
        "$sessionId\u0000$dependencyId"

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "request" }

    private fun isCurrentRequest(dependencyKey: String, requestId: String): Boolean =
        synchronized(requestLock) { latestRequestByDependency[dependencyKey] == requestId }

    private fun finishTask(task: Job, requestId: String, dependencyKey: String) {
        val stopId = synchronized(requestLock) {
            activeJobs -= task
            if (jobsByRequestId[requestId] === task) jobsByRequestId.remove(requestId)
            if (jobsByDependency[dependencyKey] === task) jobsByDependency.remove(dependencyKey)
            if (activeJobs.isEmpty()) latestStartId else null
        }
        if (stopId != null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(stopId)
        }
    }

    private fun cancelRequest(requestId: String?, startId: Int) {
        val task = requestId?.let { synchronized(requestLock) { jobsByRequestId[it] } }
        if (task == null) {
            Log.w(TAG, "Ignoring stale or unmatched proxy cancellation: $requestId")
            val idle = synchronized(requestLock) {
                latestStartId = maxOf(latestStartId, startId)
                activeJobs.isEmpty()
            }
            if (idle) stopSelfResult(startId)
        } else {
            task.cancel()
        }
    }

    private fun rejectStart(startId: Int) {
        val idle = synchronized(requestLock) {
            latestStartId = maxOf(latestStartId, startId)
            activeJobs.isEmpty()
        }
        if (idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun deleteQuietly(file: File?, label: String) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete $label: ${file.absolutePath}")
        }
    }

    /** Resolve a URI on the service's IO coroutine, copying only when SAF cannot be used. */
    private suspend fun getSafPath(uri: Uri): ResolvedSource? {
        if (uri.scheme == "content") {
            val mime = contentResolver.getType(uri)
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (mime != null && (mime.startsWith("image/") || mime.startsWith("audio/"))) {
                Log.w(TAG, "Proxy source is not a video: $mime")
                return null
            }
            val displayName = runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull()
            val displayExtension = displayName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
            if (mime == null && displayExtension !in VIDEO_EXTENSIONS) {
                Log.w(TAG, "Proxy source has no authoritative video MIME type or extension: $uri")
                return null
            }

            try {
                val safPath = com.antonkarpenko.ffmpegkit.FFmpegKitConfig
                    .getSafParameterForRead(this, uri)
                if (!safPath.isNullOrBlank()) {
                    val safFile = File(safPath)
                    if (safFile.isFile && safFile.canRead() && safFile.length() > 0L) {
                        return ResolvedSource(safFile.absolutePath)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "SAF path unavailable for $uri; copying to cache: ${e.message}")
            }

            val extension = when (mime) {
                "video/webm" -> ".webm"
                "video/quicktime" -> ".mov"
                "video/x-matroska" -> ".mkv"
                "video/3gpp" -> ".3gp"
                "video/x-msvideo" -> ".avi"
                else -> ".mp4"
            }
            val proxyCache = File(cacheDir, "proxies").apply { mkdirs() }
            val cached = File.createTempFile("proxy_source_", extension, proxyCache)
            return try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("ContentResolver returned no stream for $uri")
                input.use { stream ->
                    FileOutputStream(cached).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read
                        }
                        output.flush()
                        if (copied == 0L) throw java.io.IOException("Copied 0 bytes from $uri")
                    }
                }
                ResolvedSource(cached.absolutePath, cached)
            } catch (e: CancellationException) {
                deleteQuietly(cached, "cancelled proxy source")
                throw e
            } catch (e: Exception) {
                deleteQuietly(cached, "failed proxy source")
                Log.e(TAG, "Could not copy proxy source $uri: ${e.message}", e)
                null
            }
        }

        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() <= 0L) {
            Log.w(TAG, "Proxy source file is not a readable regular file: $path")
            return null
        }
        val extension = file.extension.lowercase()
        if (extension !in VIDEO_EXTENSIONS) {
            Log.w(TAG, "Proxy source extension is not a known video type: $extension")
            return null
        }
        return ResolvedSource(file.absolutePath)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Generation",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(Branding.APP_NAME)
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_save_24)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
    }

    private fun broadcastSuccess(record: ProxyResultRecord) {
        val intent = Intent(ACTION_PROXY_GENERATED).apply {
            putExtra(EXTRA_SOURCE_URI, record.sourceUri)
            putExtra(EXTRA_PROXY_URI, record.proxyUri)
            putExtra(EXTRA_DEPENDENCY_ID, record.dependencyId)
            putExtra(EXTRA_REQUEST_ID, record.requestId)
            putExtra(EXTRA_SESSION_ID, record.sessionId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailure(
        sourceUri: String,
        dependencyId: String,
        sessionId: String,
        requestId: String,
        error: String,
    ) {
        val intent = Intent(ACTION_PROXY_FAILED).apply {
            putExtra(EXTRA_SOURCE_URI, sourceUri)
            putExtra(EXTRA_DEPENDENCY_ID, dependencyId)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_ERROR, error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val jobs = synchronized(requestLock) { activeJobs.toList() }
        jobs.forEach { it.cancel() }
        serviceJob.cancel()
        synchronized(requestLock) {
            activeJobs.clear()
            jobsByRequestId.clear()
            jobsByDependency.clear()
            latestRequestByDependency.clear()
        }
        super.onDestroy()
    }
}
