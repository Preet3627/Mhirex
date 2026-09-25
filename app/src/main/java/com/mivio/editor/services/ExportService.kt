package com.mivio.editor.services

import com.mivio.editor.Branding

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mivio.editor.MainActivity
import com.mivio.editor.R
import com.mivio.editor.data.media.MediaPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class ExportService : Service() {

    private val TAG = "ExportService"
    private val CHANNEL_ID = "ExportChannel"
    private val NOTIFICATION_ID = 1001

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var exportTask: Job? = null
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    companion object {
        const val ACTION_EXPORT_PROGRESS = "com.mivio.editor.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.mivio.editor.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.mivio.editor.ACTION_EXPORT_FAILURE"

        /** P1-8: a user-initiated cancel. Deliberately not [ACTION_EXPORT_FAILURE]. */
        const val ACTION_EXPORT_CANCELLED = "com.mivio.editor.ACTION_EXPORT_CANCELLED"
        /** Explicit command used to cancel this service's render without touching other FFmpeg work. */
        const val ACTION_CANCEL_EXPORT = "com.mivio.editor.ACTION_CANCEL_EXPORT"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_ERROR = "extra_error"

        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ffmpegEngine = FFmpegRenderEngine.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_EXPORT) {
            // Cancel only this service's child job. The shared FFmpeg engine may also be running
            // a scrub proxy for the editor, and cancelAllSessions() would incorrectly abort it.
            val hadExport = exportTask != null
            exportTask?.cancel()
            if (!hadExport) stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH) ?: return START_NOT_STICKY
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)

        startForeground(NOTIFICATION_ID, buildNotification(0, "Exporting..."))

        val tempFile = File(tempOutputPath)
        val concatFile = concatFilePath?.let { File(it) }

        exportTask = serviceScope.launch {
            try {
                val result = ffmpegEngine.exportFinal(
                    ffmpegCommand = command,
                    totalDurationSecs = durationSecs,
                    onProgress = { progress ->
                        updateNotification(progress, "Exporting...")
                        broadcastProgress(progress)
                    }
                )

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        val savedUri = saveVideoToGallery(tempFile, isAudioOnly)
                        if (savedUri != null) {
                            showCompletionNotification("Export Complete", "Video saved to gallery", savedUri)
                            broadcastSuccess(savedUri.toString())
                            Log.d(TAG, "Export successful: $savedUri")
                        } else {
                            showCompletionNotification("Export Failed", "Failed to save video to gallery", null)
                            broadcastFailure("Failed to save video to gallery")
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        showCompletionNotification("Export Failed", "Error during rendering", null)
                        broadcastFailure(result.error)
                        Log.e(TAG, "Export failed: ${result.error}")
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        // P1-8: a user cancellation is not an error. It used to go out on
                        // ACTION_EXPORT_FAILURE, which raised an error toast for something the
                        // user deliberately did.
                        showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                        broadcastCancelled()
                    }
                }
            } catch (e: ExportCancelledException) {
                showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                broadcastCancelled()
            } catch (e: kotlinx.coroutines.CancellationException) {
                showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                broadcastCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Export exception", e)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error")
            } finally {
                // P1-11: cleanup must be unconditional. It used to sit after the `when` inside the
                // `try`, so any exception thrown while publishing leaked a full-size render plus
                // its concat list into the cache directory on every failed attempt.
                if (tempFile.exists() && !tempFile.delete()) {
                    Log.w(TAG, "Could not delete temp render: ${tempFile.absolutePath}")
                }
                if (concatFile?.exists() == true && !concatFile.delete()) {
                    Log.w(TAG, "Could not delete concat list: ${concatFile.absolutePath}")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        // P1-10: this was START_NOT_STICKY, so a low-memory kill silently abandoned the export and
        // the user never got a result. Redeliver the intent so the render restarts.
        return START_REDELIVER_INTENT
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: Int, title: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$progress%" else "Starting...")
            .setSmallIcon(R.drawable.ic_save_24)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int, title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(progress, title))
    }

    private fun showCompletionNotification(title: String, message: String, videoUri: Uri?) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (videoUri != null) {
                setDataAndType(videoUri, if (message.contains("Audio", true)) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                setClass(this@ExportService, MainActivity::class.java)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_check_24)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun broadcastProgress(progress: Int) {
        val intent = Intent(ACTION_EXPORT_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSuccess(savedUri: String) {
        val intent = Intent(ACTION_EXPORT_SUCCESS).apply {
            putExtra(EXTRA_SAVED_URI, savedUri)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailure(error: String) {
        val intent = Intent(ACTION_EXPORT_FAILURE).apply {
            putExtra(EXTRA_ERROR, error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * P1-8: cancellation is its own outcome, distinct from failure.
     *
     * Reporting it on [ACTION_EXPORT_FAILURE] made the editor show an error state for a render the
     * user asked to stop, and invited a retry of work they had just abandoned.
     */
    private fun broadcastCancelled() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_EXPORT_CANCELLED))
    }

    /**
     * Publishes the finished render to user-visible storage.
     *
     * P0-6: this used to insert a MediaStore row and write the bytes in place, with no
     * `IS_PENDING` flag. A process death or OOM kill mid-copy therefore left a truncated,
     * permanently indexed, unplayable file in the user's camera roll with no way to tell it
     * apart from a good export. It now routes through [MediaPublisher], which sets
     * `IS_PENDING=1` and only clears it after the final byte lands, deleting the row otherwise.
     */
    private fun saveVideoToGallery(videoFile: File, isAudioOnly: Boolean): Uri? {
        val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
        val extension = if (isAudioOnly) ".mp3" else ".mp4"
        val prefix = if (isAudioOnly) Branding.AUDIO_FILE_PREFIX else Branding.VIDEO_FILE_PREFIX
        val displayName = "${prefix}${System.currentTimeMillis()}$extension"

        val sharedPreferences = getSharedPreferences(Branding.PREFS_NAME, Context.MODE_PRIVATE)
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customTreeUri = sharedPreferences.getString(prefKey, null)
            ?.let(Uri::parse)
            ?.takeIf { it.scheme == "content" }

        val outcome = MediaPublisher.publish(
            context = this,
            source = videoFile,
            mimeType = mimeType,
            displayName = displayName,
            collection = if (isAudioOnly) MediaPublisher.Collection.AUDIO else MediaPublisher.Collection.VIDEO,
            customTreeUri = customTreeUri,
            isCancelled = { !serviceJob.isActive },
        )

        return when (outcome) {
            is MediaPublisher.Outcome.Published -> outcome.uri
            is MediaPublisher.Outcome.Cancelled -> {
                Log.i(TAG, "Publish cancelled: ${outcome.reason}")
                throw ExportCancelledException(outcome.reason)
            }
            is MediaPublisher.Outcome.Failed -> {
                Log.e(TAG, "Could not publish to gallery: ${outcome.reason}", outcome.cause)
                null
            }
        }
    }

    /** Raised when a publish is interrupted because the user cancelled the export. */
    private class ExportCancelledException(message: String) : Exception(message)

    override fun onDestroy() {
        exportTask?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
