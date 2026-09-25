package com.mivio.editor.services

import com.mivio.editor.Branding

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
import com.mivio.editor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class ProxyGenerationService : Service() {

    private val TAG = "ProxyGenerationService"
    private val CHANNEL_ID = "ProxyChannel"
    private val NOTIFICATION_ID = 1002

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    companion object {
        const val ACTION_PROXY_GENERATED = "com.mivio.editor.ACTION_PROXY_GENERATED"
        const val EXTRA_SOURCE_URI = "extra_source_uri"
        const val EXTRA_PROXY_URI = "extra_proxy_uri"
        const val EXTRA_DEPENDENCY_ID = "extra_dependency_id"
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

        val sourceUriStr = intent.getStringExtra(EXTRA_SOURCE_URI) ?: return START_NOT_STICKY
        val sourceUri = Uri.parse(sourceUriStr)
        val dependencyId = intent.getStringExtra(EXTRA_DEPENDENCY_ID) ?: return START_NOT_STICKY
        
        // Ensure cache directory exists for proxy
        val cacheDir = File(cacheDir, "proxies")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
        val proxyFileName = "proxy_${System.currentTimeMillis()}.mp4"
        val proxyFile = File(cacheDir, proxyFileName)

        startForeground(NOTIFICATION_ID, buildNotification("Generating optimized playback proxy..."))

        serviceScope.launch {
            var temporarySource: File? = null
            try {
                // FFmpegKit can consume a SAF path, but some providers return a value that its
                // demuxers cannot open. Resolve it once and keep a cache copy as a fallback.
                val resolvedSource = getSafPath(sourceUri)
                if (resolvedSource == null) {
                    Log.e(TAG, "Could not resolve proxy source URI: $sourceUri")
                    return@launch
                }
                temporarySource = resolvedSource.temporaryFile

                val result = ffmpegEngine.generateScrubProxy(
                    sourceFilePath = resolvedSource.path,
                    outputFilePath = proxyFile.absolutePath
                )

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        val proxyUri = Uri.fromFile(proxyFile)
                        Log.d(TAG, "Proxy generated successfully: $proxyUri")
                        broadcastSuccess(sourceUriStr, proxyUri.toString(), dependencyId)
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        Log.e(TAG, "Proxy generation failed: ${result.error}")
                        if (proxyFile.exists()) proxyFile.delete()
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        Log.w(TAG, "Proxy generation cancelled")
                        if (proxyFile.exists()) proxyFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy generation exception", e)
                if (proxyFile.exists()) proxyFile.delete()
            } finally {
                temporarySource?.let { source ->
                    if (source.exists() && !source.delete()) {
                        Log.w(TAG, "Could not delete temporary proxy source: ${source.absolutePath}")
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }
    
    private data class ResolvedSource(
        val path: String,
        val temporaryFile: File? = null,
    )

    /** Resolve a URI on the service's IO coroutine, copying only when SAF cannot be used. */
    private fun getSafPath(uri: Uri): ResolvedSource? {
        if (uri.scheme == "content") {
            try {
                val safPath = com.antonkarpenko.ffmpegkit.FFmpegKitConfig
                    .getSafParameterForRead(this, uri)
                if (!safPath.isNullOrBlank()) return ResolvedSource(safPath)
            } catch (e: Exception) {
                Log.w(TAG, "SAF path unavailable for $uri; copying to cache: ${e.message}")
            }

            val mime = contentResolver.getType(uri)
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            val extension = when {
                mime == "video/webm" -> ".webm"
                mime == "video/quicktime" -> ".mov"
                mime == "video/x-matroska" -> ".mkv"
                mime?.startsWith("video/") == true -> ".mp4"
                mime?.startsWith("image/") == true -> ".jpg"
                else -> ".mp4"
            }
            val proxyCache = File(cacheDir, "proxies").apply { mkdirs() }
            val cached = File.createTempFile("proxy_source_", extension, proxyCache)
            return try {
                val input = contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("ContentResolver returned no stream for $uri")
                input.use { stream ->
                    FileOutputStream(cached).use { output -> stream.copyTo(output) }
                }
                if (cached.length() == 0L) throw java.io.IOException("Copied 0 bytes from $uri")
                ResolvedSource(cached.absolutePath, cached)
            } catch (e: Exception) {
                if (cached.exists() && !cached.delete()) {
                    Log.w(TAG, "Could not delete failed proxy source: ${cached.absolutePath}")
                }
                Log.e(TAG, "Could not copy proxy source $uri: ${e.message}", e)
                null
            }
        }

        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        return ResolvedSource(path)
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
            .setSmallIcon(R.drawable.ic_save_24) // Reusing save icon
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
    }

    private fun broadcastSuccess(sourceUriStr: String, proxyUriStr: String, dependencyId: String) {
        val intent = Intent(ACTION_PROXY_GENERATED).apply {
            putExtra(EXTRA_SOURCE_URI, sourceUriStr)
            putExtra(EXTRA_PROXY_URI, proxyUriStr)
            putExtra(EXTRA_DEPENDENCY_ID, dependencyId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
