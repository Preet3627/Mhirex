package com.mhirex.editor.services

import com.mhirex.editor.Branding

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
import com.mhirex.editor.MainActivity
import com.mhirex.editor.R
import com.mhirex.editor.data.media.MediaPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.io.File

class ExportService : Service() {

    private val TAG = "ExportService"
    private val CHANNEL_ID = "ExportChannel"
    private val NOTIFICATION_ID = 1001

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val exportLock = Any()
    private var exportTask: Job? = null
    private var activeRequestId: String? = null
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    companion object {
        const val ACTION_EXPORT_PROGRESS = "com.mhirex.editor.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.mhirex.editor.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.mhirex.editor.ACTION_EXPORT_FAILURE"

        /** P1-8: a user-initiated cancel. Deliberately not [ACTION_EXPORT_FAILURE]. */
        const val ACTION_EXPORT_CANCELLED = "com.mhirex.editor.ACTION_EXPORT_CANCELLED"
        /** Explicit command used to cancel this service's render without touching other FFmpeg work. */
        const val ACTION_CANCEL_EXPORT = "com.mhirex.editor.ACTION_CANCEL_EXPORT"

        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SAVED_URI = "extra_saved_uri"
        const val EXTRA_ERROR = "extra_error"

        const val EXTRA_COMMAND = "extra_command"
        const val EXTRA_TEMP_OUTPUT_PATH = "extra_temp_output_path"
        const val EXTRA_CONCAT_FILE_PATH = "extra_concat_file_path"
        const val EXTRA_TOTAL_DURATION_SECS = "extra_total_duration_secs"
        const val EXTRA_IS_AUDIO_ONLY = "extra_is_audio_only"
        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_GENERATED_INPUT_PATHS = "extra_generated_input_paths"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ffmpegEngine = FFmpegRenderEngine.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_EXPORT) {
            // Cancel only this service's child job, and only when the request identity still
            // matches. A delayed cancel from request A must never stop a newer request B.
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            val task = synchronized(exportLock) {
                if (requestId != null && requestId == activeRequestId) exportTask else null
            }
            if (task == null) {
                Log.w(TAG, "Ignoring stale or unmatched export cancellation: $requestId")
                stopSelf(startId)
            } else {
                task.cancel()
            }
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val command = intent.getStringExtra(EXTRA_COMMAND)
        val tempOutputPath = intent.getStringExtra(EXTRA_TEMP_OUTPUT_PATH)
        if (command.isNullOrBlank() || tempOutputPath.isNullOrBlank()) {
            Log.w(TAG, "Ignoring export request with missing command or output path")
            stopSelf()
            return START_NOT_STICKY
        }
        val concatFilePath = intent.getStringExtra(EXTRA_CONCAT_FILE_PATH)
        val generatedInputPaths = intent.getStringArrayListExtra(EXTRA_GENERATED_INPUT_PATHS).orEmpty()
        val durationSecs = intent.getDoubleExtra(EXTRA_TOTAL_DURATION_SECS, 0.0)
        val isAudioOnly = intent.getBooleanExtra(EXTRA_IS_AUDIO_ONLY, false)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) {
            Log.w(TAG, "Ignoring export request without a request identity")
            deleteFileIfPresent(tempOutputPath?.let(::File), "unowned temp render")
            deleteFileIfPresent(concatFilePath?.let(::File), "unowned concat list")
            generatedInputPaths.forEach { deleteFileIfPresent(File(it), "unowned generated input") }
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // A redelivered/duplicated intent must never overwrite the Job reference for an active
        // render. Keep one export per service instance and let the existing job finish (or be
        // cancelled explicitly) before accepting another request.
        synchronized(exportLock) {
            if (exportTask != null) {
                Log.w(TAG, "Rejecting duplicate export request $requestId while another export is active")
                deleteFileIfPresent(tempOutputPath?.let(::File), "rejected temp render")
                deleteFileIfPresent(concatFilePath?.let(::File), "rejected concat list")
                generatedInputPaths.forEach { deleteFileIfPresent(File(it), "rejected generated input") }
                broadcastFailure("Another export is already in progress", requestId)
                return START_NOT_STICKY
            }
        }

        val tempFile = File(tempOutputPath)
        val concatFile = concatFilePath?.let { File(it) }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0, "Exporting..."))
        } catch (e: Exception) {
            Log.e(TAG, "Could not start export foreground service", e)
            deleteFileIfPresent(tempFile, "temp render")
            deleteFileIfPresent(concatFile, "concat list")
            generatedInputPaths.forEach { deleteFileIfPresent(File(it), "generated input") }
            broadcastFailure(e.message ?: "Could not start export", requestId)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Construct lazily so the task can be published in exportTask before its first suspension
        // point.  This makes cancellation and the finally cleanup identity-safe.
        lateinit var task: Job
        task = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = ffmpegEngine.exportFinal(
                    ffmpegCommand = command,
                    totalDurationSecs = durationSecs,
                    onProgress = { progress ->
                        updateNotification(progress, "Exporting...")
                        broadcastProgress(progress, requestId)
                    }
                )

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        val childJob = currentCoroutineContext()[Job]
                            ?: throw ExportCancelledException("Export job is no longer active")
                        val savedUri = saveVideoToGallery(tempFile, isAudioOnly, requestId, childJob)
                        if (savedUri != null) {
                            showCompletionNotification(
                                "Export Complete",
                                if (isAudioOnly) "Audio saved to gallery" else "Video saved to gallery",
                                savedUri,
                                isAudioOnly
                            )
                            broadcastSuccess(savedUri.toString(), requestId)
                            Log.d(TAG, "Export successful: $savedUri")
                        } else {
                            showCompletionNotification("Export Failed", "Failed to save video to gallery", null)
                            broadcastFailure("Failed to save video to gallery", requestId)
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        showCompletionNotification("Export Failed", "Error during rendering", null)
                        broadcastFailure(result.error, requestId)
                        Log.e(TAG, "Export failed: ${result.error}")
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        // P1-8: a user cancellation is not an error. It used to go out on
                        // ACTION_EXPORT_FAILURE, which raised an error toast for something the
                        // user deliberately did.
                        showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                        broadcastCancelled(requestId)
                    }
                }
            } catch (e: ExportCancelledException) {
                showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                broadcastCancelled(requestId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                showCompletionNotification("Export Cancelled", "The export was cancelled", null)
                broadcastCancelled(requestId)
            } catch (e: Exception) {
                Log.e(TAG, "Export exception", e)
                showCompletionNotification("Export Failed", e.message ?: "Unknown error", null)
                broadcastFailure(e.message ?: "Unknown error", requestId)
            } finally {
                // P1-11: cleanup must be unconditional. It used to sit after the `when` inside the
                // `try`, so any exception thrown while publishing leaked a full-size render plus
                // its concat list into the cache directory on every failed attempt.
                deleteFileIfPresent(tempFile, "temp render")
                deleteFileIfPresent(concatFile, "concat list")
                generatedInputPaths.forEach { deleteFileIfPresent(File(it), "generated input") }
                finishTask(task)
            }
        }

        synchronized(exportLock) {
            exportTask = task
            activeRequestId = requestId
        }
        task.start()

        // Do not blindly redeliver this intent after process death. The command references
        // request-owned cache artifacts and the publish phase has no durable idempotency key;
        // redelivery can duplicate a completed gallery item or reopen a deleted/truncated output.
        // A durable WorkManager request is required before restart semantics can be enabled.
        return START_NOT_STICKY
    }

    private fun deleteFileIfPresent(file: File?, label: String) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete $label: ${file.absolutePath}")
        }
    }

    /** Only the job that still owns the service may tear down its foreground state. */
    private fun finishTask(task: Job) {
        val shouldStop = synchronized(exportLock) {
            if (exportTask !== task) {
                false
            } else {
                exportTask = null
                activeRequestId = null
                true
            }
        }
        if (shouldStop) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

    private fun showCompletionNotification(
        title: String,
        message: String,
        videoUri: Uri?,
        isAudioOnly: Boolean = false
    ) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (videoUri != null) {
                setDataAndType(videoUri, if (isAudioOnly) "audio/*" else "video/*")
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

    private fun broadcastProgress(progress: Int, requestId: String) {
        val intent = Intent(ACTION_EXPORT_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSuccess(savedUri: String, requestId: String) {
        val intent = Intent(ACTION_EXPORT_SUCCESS).apply {
            putExtra(EXTRA_SAVED_URI, savedUri)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailure(error: String, requestId: String) {
        val intent = Intent(ACTION_EXPORT_FAILURE).apply {
            putExtra(EXTRA_ERROR, error)
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * P1-8: cancellation is its own outcome, distinct from failure.
     *
     * Reporting it on [ACTION_EXPORT_FAILURE] made the editor show an error state for a render the
     * user asked to stop, and invited a retry of work they had just abandoned.
     */
    private fun broadcastCancelled(requestId: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_EXPORT_CANCELLED).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
        })
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
    private fun saveVideoToGallery(
        videoFile: File,
        isAudioOnly: Boolean,
        requestId: String,
        exportJob: Job,
    ): Uri? {
        val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
        val extension = if (isAudioOnly) ".mp3" else ".mp4"
        val prefix = if (isAudioOnly) Branding.AUDIO_FILE_PREFIX else Branding.VIDEO_FILE_PREFIX
        val displayName = "${prefix}${requestId.take(12)}_${System.currentTimeMillis()}$extension"

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
            // Observe this export's child job, not the service parent.  The parent remains active
            // while a child is being cancelled, so using it here allowed a cancelled copy to finish
            // publishing a file after the user tapped Cancel.
            isCancelled = { !exportJob.isActive },
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
