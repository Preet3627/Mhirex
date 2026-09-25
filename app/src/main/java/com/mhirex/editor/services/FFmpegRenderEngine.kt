package com.mhirex.editor.services

import android.content.Context
import android.net.Uri
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Level
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File

import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FFmpegRenderEngine handles all FFmpeg operations for the video editor.
 *
 * FONT NOTE:
 * drawtext requires fontfile= pointing to a TTF/OTF file on disk.
 * font= does NOT exist in FFmpeg's drawtext filter — it always causes:
 *   "Error applying option 'font' to filter 'drawtext': Option not found"
 *
 * Usage:
 *   1. Place TTF at app/src/main/assets/fonts/Roboto-Regular.ttf
 *   2. Call copyFontToCache() once in Activity.onCreate
 *   3. Pass the returned path as fontFilePath to all text render methods
 */
class FFmpegRenderEngine private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var sharedInstance: FFmpegRenderEngine? = null
        private val instanceLock = Any()

        /**
         * FFmpegKit is process-global. Sharing one wrapper keeps Activity, ExportService, and
         * ProxyGenerationService on the same session registry, so a service render is visible to
         * the editor's cancellation path and no duplicate global log callback is installed.
         */
        fun getInstance(context: Context): FFmpegRenderEngine {
            return sharedInstance ?: synchronized(instanceLock) {
                sharedInstance ?: FFmpegRenderEngine(context.applicationContext ?: context)
                    .also { sharedInstance = it }
            }
        }
    }

    private val activeSessions = ConcurrentHashMap<Long, FFmpegSession>()
    private val sessionLogBuffers = ConcurrentHashMap<Long, ConcurrentLinkedQueue<com.antonkarpenko.ffmpegkit.Log>>()
    private val TAG = "FFmpegRenderEngine"

    init {
        // System fonts as a last-resort fallback — not reliable on all Android devices.
        // Always supply an explicit fontFilePath for guaranteed text rendering.
        // Probe multiple vendor font directories since OEMs differ.
        val fontDir = listOf(
            "/system/fonts",
            "/system/font",
            "/data/fonts",
            "/product/fonts"
        ).firstOrNull { File(it).isDirectory } ?: "/system/fonts"

        try {
            FFmpegKitConfig.setFontDirectory(context, fontDir, null)
            Log.d(TAG, "Font directory set to: $fontDir")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set font directory '$fontDir': ${e.message}")
        }

        // Register a global log callback so every FFmpeg stderr line hits logcat and session buffer.
        try {
            FFmpegKitConfig.enableLogCallback { log ->
                val msg = log.message?.trimEnd() ?: return@enableLogCallback
                if (msg.isEmpty()) return@enableLogCallback

                // Session diagnostics are captured by the per-session callback in
                // executeTrackedCommand(). Recording here as well would duplicate every line when
                // FFmpegKit invokes both callbacks, halving the useful diagnostic window.

                when (log.level) {
                    Level.AV_LOG_ERROR, Level.AV_LOG_FATAL, Level.AV_LOG_PANIC, Level.AV_LOG_STDERR
                        -> Log.e(TAG, "[ffmpeg] $msg")
                    Level.AV_LOG_WARNING
                        -> Log.w(TAG, "[ffmpeg] $msg")
                    else -> Log.v(TAG, "[ffmpeg] $msg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register FFmpegKit global log callback: ${e.message}")
        }
    }

    // ── Result type ───────────────────────────────────────────────────────────

    sealed class RenderResult {
        data class Success(val outputPath: String, val session: FFmpegSession) : RenderResult()
        data class Failure(val error: String, val session: FFmpegSession? = null) : RenderResult()
        object Cancelled : RenderResult()
    }

    // ── Font helper ───────────────────────────────────────────────────────────

    /**
     * Copies a font from assets to cacheDir so FFmpeg can access it as a plain path.
     *
     * @param assetPath  e.g. "fonts/Roboto-Regular.ttf"
     * @return           Absolute path to the cached file, or null on failure.
     */
    @Synchronized
    fun copyFontToCache(assetPath: String = "fonts/Roboto-Regular.ttf"): String? {
        return try {
            val fileName = assetPath.substringAfterLast('/')
            val fontFile = File(context.cacheDir, fileName)
            if (!fontFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    fontFile.outputStream().use { input.copyTo(it) }
                }
                Log.d(TAG, "Font copied to cache: ${fontFile.absolutePath}")
            } else {
                Log.d(TAG, "Font already in cache: ${fontFile.absolutePath}")
            }
            
            val fontMap = mutableMapOf<String, String>()
            val alias = fileName.substringBeforeLast('.')
            fontMap[alias] = fileName
            FFmpegKitConfig.setFontDirectory(context, context.cacheDir.absolutePath, fontMap)

            // P0-5: return the absolute path, not the alias.
            //
            // Callers assign this to `fontFilePath` and interpolate it straight into
            // `drawtext=fontfile='$fontFilePath'`. The old return value (`"Roboto-Regular"`) was a
            // bare alias with no directory, so libfreetype could not resolve it and every text and
            // subtitle overlay was silently dropped from the export while still appearing
            // correctly in the preview — the preview draws with Android's own font stack, not
            // FFmpeg. Registering the font directory above does not make a relative alias valid
            // in a filter argument; the filter needs a real filesystem path.
            fontFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy font to cache: ${e.message}", e)
            null
        }
    }

    // ── Core execution ────────────────────────────────────────────────────────

    /**
     * Execute an FFmpeg command and return the result.
     * Runs on IO dispatcher for non-blocking execution.
     */
    suspend fun executeCommand(command: String, isRetry: Boolean = false): RenderResult {
        return executeTrackedCommand(command, isRetry = isRetry)
    }

    // ── Session management ────────────────────────────────────────────────────

    suspend fun cancelAllSessions() {
        withContext(Dispatchers.IO) {
            // Do not call FFmpegKit.cancel() here: it is process-global and can cancel an
            // unrelated FFmpegKit user. Every render launched by this app is registered, so cancel
            // each known session by ID. Remove only the ids observed in this snapshot; a render
            // launched concurrently must remain cancellable by its own coroutine handler.
            activeSessions.keys.toList().forEach { sessionId ->
                if (requestSessionCancellation(sessionId)) {
                    unregisterSession(sessionId)
                }
            }
            Log.d(TAG, "Cancelled all tracked FFmpeg sessions")
        }
    }

    suspend fun cancelSession(sessionId: Long) {
        withContext(Dispatchers.IO) {
            if (requestSessionCancellation(sessionId)) {
                unregisterSession(sessionId)
            }
            Log.d(TAG, "Cancellation requested for session: $sessionId")
        }
    }

    fun hasActiveSessions(): Boolean = activeSessions.isNotEmpty()
    fun getActiveSessionCount(): Int = activeSessions.size
    fun getLatestSession(): FFmpegSession? = activeSessions.values.maxByOrNull { it.sessionId }

    private fun registerSession(session: FFmpegSession) {
        // Install the buffer before publishing the session in the active registry.  This keeps
        // callbacks race-free without allowing an unregistered session to create state lazily.
        sessionLogBuffers[session.sessionId] = ConcurrentLinkedQueue()
        activeSessions[session.sessionId] = session
    }

    private fun unregisterSession(sessionId: Long) {
        activeSessions.remove(sessionId)
        sessionLogBuffers.remove(sessionId)
    }

    /**
     * Requests cancellation for one native session.  A native cancellation failure is not treated
     * as proof that the session stopped: leave it registered so a later cleanup/completion can
     * retry instead of losing the only handle to a still-running render.
     */
    private fun requestSessionCancellation(sessionId: Long): Boolean {
        return runCatching {
            FFmpegKit.cancel(sessionId)
        }.onFailure {
            Log.w(TAG, "Could not cancel FFmpeg session $sessionId: ${it.message}")
        }.isSuccess
    }

    // ── Preview helpers ───────────────────────────────────────────────────────

    suspend fun renderTrimPreview(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun renderCropPreview(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = buildCropFilter(aspectRatio)
            ?: return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        val command = "-i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Render a preview with a text overlay.
     *
     * @param fontFilePath  Absolute path to a TTF file. Use [copyFontToCache] to get this.
     */
    suspend fun renderTextPreview(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): RenderResult {
        val textFilter = buildDrawtextFilter(text, fontSize, positionParam, fontFilePath)
        val command = "-i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    // ── Full-quality operations ───────────────────────────────────────────────

    /** Export the final video using the consolidated command from the ViewModel. */
    suspend fun exportFinal(
        ffmpegCommand: String,
        totalDurationSecs: Double? = null,
        onProgress: ((Int) -> Unit)? = null,
        isRetry: Boolean = false
    ): RenderResult {
        return executeTrackedCommand(
            command = ffmpegCommand,
            isRetry = isRetry,
            totalDurationSecs = totalDurationSecs,
            onProgress = onProgress
        )
    }

    /**
     * Starts one cancellable FFmpeg session and evaluates its result.
     *
     * FFmpegKit.execute() blocks until completion, which meant there was no session id to cancel
     * while a render was actually running. All editor operations therefore use executeAsync() and
     * register the returned session immediately. The continuation is resumed only when the render
     * completes, and its cancellation handler cancels that one session rather than the process-wide
     * FFmpegKit.cancel() overload.
     */
    private suspend fun executeTrackedCommand(
        command: String,
        isRetry: Boolean,
        totalDurationSecs: Double? = null,
        onProgress: ((Int) -> Unit)? = null
    ): RenderResult = withContext(Dispatchers.IO) {
        var session: FFmpegSession? = null
        val cancellationRequested = AtomicBoolean(false)
        try {
            Log.d(TAG, "Executing FFmpeg command: $command")

            val completedSession = suspendCancellableCoroutine<FFmpegSession> { cont ->
                // FFmpegKit is allowed to invoke the completion callback from its worker thread.
                // If it does so before executeAsync() returns, registration happens immediately
                // afterwards; remember the edge case so a cancelled continuation cannot leave a
                // just-registered native session behind.
                val completedBeforeRegistration = AtomicBoolean(false)
                val asyncSession = try {
                    FFmpegKit.executeAsync(
                        command,
                        { completeSession ->
                            if (cont.isActive) {
                                cont.resume(completeSession)
                            } else {
                                completedBeforeRegistration.set(true)
                                // If the session was cancelled, clean up once the native callback
                                // proves that it really stopped.  No global FFmpeg cancellation is
                                // involved here.
                                if (session?.sessionId == completeSession.sessionId) {
                                    unregisterSession(completeSession.sessionId)
                                }
                            }
                        },
                        { log ->
                            val msg = log.message?.trimEnd() ?: return@executeAsync
                            if (msg.isNotEmpty()) recordSessionLog(log)
                        },
                        { statistics ->
                            if (!cont.isActive || totalDurationSecs == null || totalDurationSecs <= 0) {
                                return@executeAsync
                            }
                            val timeMs = statistics.time
                            if (timeMs > 0) {
                                val timeSecs = timeMs.toDouble() / 1000.0
                                val progress = (timeSecs / totalDurationSecs * 100).toInt()
                                runCatching { onProgress?.invoke(progress.coerceIn(0, 100)) }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                    return@suspendCancellableCoroutine
                }

                session = asyncSession
                registerSession(asyncSession)

                // Covers a completion callback that raced ahead of registration while the
                // continuation was already cancelled.  The normal cancellation handler below
                // handles cancellations that happen after this point.
                if (!cont.isActive && completedBeforeRegistration.get()) {
                    unregisterSession(asyncSession.sessionId)
                }

                cont.invokeOnCancellation {
                    cancellationRequested.set(true)
                    // Keep cancellation scoped to this render. The no-argument cancel() would
                    // cancel unrelated work from any other FFmpegKit user in the process.
                    if (requestSessionCancellation(asyncSession.sessionId)) {
                        unregisterSession(asyncSession.sessionId)
                    } else {
                        // Retain the registry entry when the native request itself failed.  The
                        // completion callback (or a later cancelAllSessions/cleanup call) can
                        // still dispose of this exact session; dropping the handle here would
                        // leave an untracked render running in the process.
                        Log.w(TAG, "Retaining FFmpeg session ${asyncSession.sessionId} for completion cleanup")
                    }
                }
            }

            session = completedSession
            currentCoroutineContext().ensureActive()

            val returnCode = completedSession.getReturnCode()
            Log.d(TAG, "FFmpeg completed with return code: ${returnCode?.getValue()}")
            if (returnCode != null && ReturnCode.isCancel(returnCode)) {
                return@withContext RenderResult.Cancelled
            }

            val logs = sessionLogBuffers.remove(completedSession.sessionId)?.toList()
            val outputPath = extractOutputPath(command)
            val missingVideo = isMissingVideoStream(command, logs, outputPath)

            if (ReturnCode.isSuccess(returnCode) && !missingVideo) {
                RenderResult.Success(
                    outputPath = outputPath,
                    session = completedSession
                )
            } else {
                val diagnosticLog = createDiagnosticReport(
                    context = context,
                    command = command,
                    returnCode = returnCode,
                    session = completedSession,
                    collectedLogs = logs
                )

                // Do not turn a cancelled render into an encoder retry. The coroutine may have
                // been cancelled by the editor while FFmpeg was returning its failure code.
                currentCoroutineContext().ensureActive()

                if (!isRetry && command.contains("h264_mediacodec")) {
                    Log.w(TAG, "Hardware encoder h264_mediacodec failed or produced no video stream. Falling back to software encoder libx264. Error:\n$diagnosticLog")
                    val fallbackCommand = command.replace("h264_mediacodec", "libx264")
                    unregisterSession(completedSession.sessionId)
                    session = null
                    return@withContext executeTrackedCommand(
                        command = fallbackCommand,
                        isRetry = true,
                        totalDurationSecs = totalDurationSecs,
                        onProgress = onProgress
                    )
                } else if (!isRetry && command.contains("libx264")) {
                    Log.w(TAG, "Software encoder libx264 failed. Falling back to hardware encoder h264_mediacodec. Error:\n$diagnosticLog")
                    val fallbackCommand = command
                        .replace("-c:v libx264", "-c:v h264_mediacodec -b:v 8M")
                        .replace("libx264", "h264_mediacodec")
                    unregisterSession(completedSession.sessionId)
                    session = null
                    return@withContext executeTrackedCommand(
                        command = fallbackCommand,
                        isRetry = true,
                        totalDurationSecs = totalDurationSecs,
                        onProgress = onProgress
                    )
                }

                Log.e(TAG, "FFmpeg error:\n$diagnosticLog")
                RenderResult.Failure(error = diagnosticLog, session = completedSession)
            }
        } catch (e: CancellationException) {
            RenderResult.Cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
            val diagnosticLog = createDiagnosticReport(context, command, null, null, null, e)
            RenderResult.Failure(error = diagnosticLog)
        } finally {
            // On the normal path (and after a successful cancellation request) the entry is no
            // longer needed.  If native cancellation failed, completion/cancelAllSessions owns
            // the cleanup instead; do not make that render untracked here.
            if (!cancellationRequested.get()) {
                session?.let { unregisterSession(it.sessionId) }
            }
        }
    }

    private fun recordSessionLog(log: com.antonkarpenko.ffmpegkit.Log) {
        val sessionId = log.sessionId
        if (sessionId == 0L) return

        // A cancelled session can still emit one or more native log callbacks after its coroutine
        // has been cancelled.  Do not use getOrPut here: a late callback would recreate a buffer
        // that unregisterSession() had already removed and leak the whole native-session record.
        // Registration always installs the buffer before the session becomes active, so looking it
        // up without creating one is both sufficient and race-safe.
        if (!activeSessions.containsKey(sessionId)) return
        val buffer = sessionLogBuffers[sessionId] ?: return
        if (activeSessions.containsKey(sessionId) && buffer.size < 2000) {
            buffer.add(log)
        }
    }

    private fun isMissingVideoStream(command: String, logs: List<com.antonkarpenko.ffmpegkit.Log>?, outputPath: String): Boolean {
        val isVideoExport = (command.contains("-c:v") || command.contains("h264_mediacodec") || command.contains("libx264")) && !command.contains("-vn")
        if (!isVideoExport) return false

        if (!logs.isNullOrEmpty()) {
            for (l in logs) {
                val msg = l.message ?: continue
                if (msg.contains("video:0kB", ignoreCase = true) ||
                    msg.contains("video:0.0kB", ignoreCase = true) ||
                    msg.contains("video: 0kB", ignoreCase = true) ||
                    msg.contains("video:0B", ignoreCase = true) ||
                    (msg.contains("frame=") && msg.contains("frame= 0 ") && msg.contains("Lsize="))
                ) {
                    Log.w(TAG, "isMissingVideoStream: Detected zero video frames in FFmpeg log line: $msg")
                    return true
                }
            }
        }

        if (outputPath.isNotBlank()) {
            val file = File(outputPath)
            if (!file.exists() || file.length() == 0L) {
                Log.w(TAG, "isMissingVideoStream: Output file is missing or 0 bytes: $outputPath")
                return true
            }
        }

        return false
    }

    /**
     * Create a structured technical diagnostic report for failed operations.
     */
    private fun createDiagnosticReport(
        context: Context,
        command: String,
        returnCode: ReturnCode?,
        session: FFmpegSession?,
        collectedLogs: List<com.antonkarpenko.ffmpegkit.Log>?,
        exception: Throwable? = null
    ): String {
        val appVersion = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pInfo.versionName} (${pInfo.versionCode})"
        } catch (e: Exception) {
            "1.0-beta"
        }

        val failStackTrace = session?.failStackTrace?.takeIf { it.isNotBlank() }

        val rawLogLines = mutableListOf<String>()
        if (!collectedLogs.isNullOrEmpty()) {
            for (l in collectedLogs) {
                l.message?.trimEnd()?.let { if (it.isNotEmpty()) rawLogLines.add(it) }
            }
        } else {
            session?.allLogsAsString?.takeIf { it.isNotBlank() }?.let { allLogsStr ->
                rawLogLines.addAll(allLogsStr.split("\n").map { it.trimEnd() }.filter { it.isNotEmpty() })
            }
        }

        val errorKeywords = listOf(
            "error", "fatal", "panic", "failed", "invalid", "cannot",
            "denied", "not found", "no such", "exception", "aborted",
            "conversion failed", "option not found", "filtergraph", "could not"
        )

        val keyErrors = mutableListOf<String>()
        val cleanHistory = mutableListOf<String>()

        for (line in rawLogLines) {
            val lower = line.lowercase()
            val isProgress = line.startsWith("frame=") || line.startsWith("size=") ||
                    (line.contains("fps=") && line.contains("time="))
            val isErrorLine = errorKeywords.any { lower.contains(it) }

            if (isErrorLine && !isProgress) {
                keyErrors.add(line)
            }
            if (!isProgress || isErrorLine) {
                cleanHistory.add(line)
            }
        }

        val sb = StringBuilder()
        sb.appendLine("==================================================")
        sb.appendLine("             MHIREX TECHNICAL LOG               ")
        sb.appendLine("==================================================")
        sb.appendLine("App Version : $appVersion")
        sb.appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Return Code : ${returnCode?.getValue() ?: "N/A"}")
        if (exception != null) {
            sb.appendLine("Exception   : ${exception.javaClass.simpleName} - ${exception.message}")
        }
        sb.appendLine()
        sb.appendLine("--------------------------------------------------")
        sb.appendLine("EXECUTED COMMAND:")
        sb.appendLine("--------------------------------------------------")
        sb.appendLine(command)
        sb.appendLine()

        if (!failStackTrace.isNullOrEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("JAVA FAIL STACK TRACE:")
            sb.appendLine("--------------------------------------------------")
            sb.appendLine(failStackTrace)
            sb.appendLine()
        }

        if (keyErrors.isNotEmpty()) {
            sb.appendLine("--------------------------------------------------")
            sb.appendLine("CRITICAL ERROR MESSAGES (${keyErrors.size}):")
            sb.appendLine("--------------------------------------------------")
            keyErrors.take(25).forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        sb.appendLine("--------------------------------------------------")
        sb.appendLine("RELEVANT LOG TRAIL (${cleanHistory.size} lines):")
        sb.appendLine("--------------------------------------------------")
        if (cleanHistory.isEmpty()) {
            sb.appendLine("(No log lines outputted by FFmpeg)")
        } else {
            cleanHistory.takeLast(100).forEach { sb.appendLine(it) }
        }
        sb.appendLine("==================================================")

        return sb.toString()
    }

    /** Extract audio from a video file using FFmpeg. */
    suspend fun extractAudio(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        // -vn disables video stream, -acodec copy copies the audio stream.
        val command = "-y -i \"$sourceFilePath\" -vn -acodec copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Transcode any input to a genuine MP3 (P0-2).
     *
     * The no-edits export path used to write the source video's raw bytes into a file named
     * `.mp3`. That produced a file the user could not play, mislabelled as MP3. Audio-only output
     * must be encoded, not renamed.
     *
     * `extractAudio` cannot be reused here: `-acodec copy` would put (say) AAC into an MP3 container,
     * which is equally unplayable. CBR 192 kbps matches what the edited export path already uses.
     */
    suspend fun exportAudioOnly(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-y -i \"$sourceFilePath\" -vn " +
            "-c:a libmp3lame -b:a 192k -ar 44100 -ac 2 " +
            "\"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Merge videos using the FFmpeg concat demuxer.
     * FIX: Both listFilePath and outputFilePath are quoted.
     */
    suspend fun mergeVideos(
        listFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-f concat -safe 0 -i \"$listFilePath\" -c:v copy -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun trimVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun generateSpeedProxy(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        speed: Float,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        
        val ptsMultiplier = 1.0f / speed
        
        val atempoFilters = mutableListOf<String>()
        var currentSpeed = speed
        while (currentSpeed < 0.5f) {
            atempoFilters.add("atempo=0.5")
            currentSpeed /= 0.5f
        }
        while (currentSpeed > 2.0f) {
            atempoFilters.add("atempo=2.0")
            currentSpeed /= 2.0f
        }
        if (currentSpeed != 1.0f || atempoFilters.isEmpty()) {
            atempoFilters.add("atempo=$currentSpeed")
        }
        val audioFilter = atempoFilters.joinToString(",")
        
        val command = "-y -ss $startSecs -t $durationSecs -i \"$sourceFilePath\" -filter:v \"setpts=${ptsMultiplier}*PTS,format=yuv420p\" -filter:a \"$audioFilter\" \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun generateScrubProxy(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        // Fast proxy generation: Downscale to max 720p (keeping aspect ratio), fast preset for encoding speed.
        val command = "-y -i \"$sourceFilePath\" -vf \"scale='min(1280,iw)':-2\" -c:v libx264 -preset ultrafast -crf 28 -tune fastdecode -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun reverseVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-y -ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -filter:v \"reverse,format=yuv420p\" -filter:a \"areverse\" -c:v libx264 -c:a aac \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun cropVideo(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = buildCropFilter(aspectRatio)
            ?: return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        val command = "-i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Add a text overlay to a video.
     *
     * @param fontFilePath  Absolute path to a TTF file. Use [copyFontToCache] to get this.
     */
    suspend fun addTextOverlay(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): RenderResult {
        val textFilter = buildDrawtextFilter(text, fontSize, positionParam, fontFilePath)
        val command = "-i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun addBackgroundAudio(
        sourceFilePath: String,
        audioFilePath: String,
        outputFilePath: String,
        replaceAudio: Boolean = false
    ): RenderResult {
        val command = if (replaceAudio) {
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" -c:v copy -map 0:v:0 -map 1:a:0 \"$outputFilePath\""
        } else {
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" " +
                    "-filter_complex \"[0:a][1:a]amix=inputs=2:duration=first[a]\" " +
                    "-map 0:v -map \"[a]\" -c:v copy \"$outputFilePath\""
        }
        return executeCommand(command)
    }

    suspend fun muteAudio(sourceFilePath: String, outputFilePath: String): RenderResult {
        val command = "-i \"$sourceFilePath\" -an -c:v copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun extractFrame(
        sourceFilePath: String,
        timeMs: Long,
        outputImagePath: String
    ): RenderResult {
        val timeSecs = timeMs / 1000.0
        val command = "-ss $timeSecs -i \"$sourceFilePath\" -vframes 1 \"$outputImagePath\""
        return executeCommand(command)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun cleanup() {
        // Kept for callers that need a non-suspending emergency cleanup. Do not merely clear the
        // registry: that would orphan a running FFmpeg thread and make later cancellation a no-op.
        activeSessions.keys.toList().forEach { sessionId ->
            runCatching { FFmpegKit.cancel(sessionId) }
                .onFailure { Log.w(TAG, "Could not cancel FFmpeg session $sessionId during cleanup: ${it.message}") }
            unregisterSession(sessionId)
        }
        Log.d(TAG, "FFmpegRenderEngine cleaned up")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Build a drawtext filter string.
     * Uses fontfile= (not the non-existent font= option).
     */
    private fun buildDrawtextFilter(
        text: String,
        fontSize: Int,
        positionParam: String,
        fontFilePath: String?
    ): String {
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("'", "\\\\'")
            .replace(":", "\\:")

        val fontPart = if (!fontFilePath.isNullOrBlank()) {
            val escapedFontPath = fontFilePath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFontPath':"
        } else {
            Log.w(TAG, "No fontFilePath — text may not render. Call copyFontToCache() first.")
            ""
        }

        return "drawtext=${fontPart}text='$escapedText':fontcolor=white:fontsize=$fontSize:$positionParam"
    }

    private fun buildCropFilter(aspectRatio: String): String? = when (aspectRatio) {
        "16:9" -> "crop='trunc(min(iw\\,ih*16/9)/2)*2':'trunc(min(ih\\,iw*9/16)/2)*2',setsar=1"
        "9:16" -> "crop='trunc(min(iw\\,ih*9/16)/2)*2':'trunc(min(ih\\,iw*16/9)/2)*2',setsar=1"
        "1:1"  -> "crop='trunc(min(iw\\,ih)/2)*2':'trunc(min(iw\\,ih)/2)*2',setsar=1"
        else   -> null
    }

    /**
     * Extract the output file path from an FFmpeg command.
     *
     * FIX: Handles both quoted paths (standard) and unquoted paths (legacy merge commands).
     * Previously only matched quoted strings, which caused truncated paths when the output
     * was accidentally left unquoted — producing errors like:
     *   "Unable to find a suitable output format for '/data/user/0/com.tharunb'"
     */
    suspend fun generateVideoFromImage(
        imageUri: Uri,
        durationMs: Long,
        outputPath: String
    ): RenderResult {
        // getSafParameterForRead returns saf:X which confuses the image2 demuxer.
        // We force copy to a cache file so FFmpeg sees a standard file path.
        val imagePath = resolveUriToFilePath(imageUri, forceCopy = true)
            ?: return RenderResult.Failure("Could not read image file. URI: $imageUri")
        val durationSecs = durationMs / 1000f

        // Use 30 fps with hardware acceleration (h264_mediacodec) for instant encoding, falling back to libx264 if needed.
        // The scale filter ensures the width and height are divisible by 2.
        val command = "-f image2 -loop 1 -framerate 30 -i \"$imagePath\" -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v h264_mediacodec -b:v 4M -t $durationSecs -pix_fmt yuv420p -c:a aac -shortest \"$outputPath\""
        return executeCommand(command)
    }

    suspend fun generateVideoFromGif(
        gifUri: Uri,
        durationMs: Long,
        outputPath: String
    ): RenderResult {
        val gifPath = resolveUriToFilePath(gifUri, forceCopy = true)
            ?: return RenderResult.Failure("Could not read GIF file. URI: $gifUri")
        val durationSecs = durationMs / 1000f

        // ignore_loop 0 loops the gif infinitely. We then add a silent audio track and limit duration.
        // Try hardware encoder first for fast generation.
        val command = "-f gif -ignore_loop 0 -i \"$gifPath\" -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" -c:v h264_mediacodec -b:v 4M -t $durationSecs -pix_fmt yuv420p -c:a aac -shortest \"$outputPath\""
        return executeCommand(command)
    }

    /**
     * Resolve any URI to a plain file-system path that FFmpeg can read.
     *
     * - file:// URIs  → strip scheme, return path directly
     * - content:// SAF URIs (ACTION_OPEN_DOCUMENT) → use getSafParameterForRead
     * - content:// MediaStore URIs → copy to a temp file in cacheDir
     */
    private suspend fun resolveUriToFilePath(uri: Uri, forceCopy: Boolean = false): String? = withContext(Dispatchers.IO) {
        when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                if (!forceCopy) {
                    // Try SAF first (works for ACTION_OPEN_DOCUMENT URIs, but custom protocol can confuse some demuxers)
                    val safPath = try { FFmpegKitConfig.getSafParameterForRead(context, uri) } catch (e: Exception) { null }
                    if (!safPath.isNullOrBlank()) return@withContext safPath
                }

                // Fallback (or forced): copy the stream to a temp cache file
                try {
                    // Determine a sensible extension from the MIME type
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val ext = when {
                        mimeType.contains("gif")  -> ".gif"
                        mimeType.contains("png")  -> ".png"
                        mimeType.contains("webp") -> ".webp"
                        mimeType.contains("heic") || mimeType.contains("heif") -> ".heic"
                        else -> ".jpg"
                    }
                    val tempFile = File(context.cacheDir, "img_input_${System.currentTimeMillis()}$ext")
                    val inputStream = if (uri.scheme == "file") {
                        java.io.FileInputStream(uri.path!!)
                    } else {
                        context.contentResolver.openInputStream(uri)
                    } ?: return@withContext null
                    inputStream.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (tempFile.length() == 0L) { tempFile.delete(); return@withContext null }
                    Log.d(TAG, "resolveUriToFilePath: copied to ${tempFile.absolutePath} (${tempFile.length()} bytes)")
                    tempFile.absolutePath
                } catch (e: Exception) {
                    Log.e(TAG, "resolveUriToFilePath: failed to copy URI $uri: ${e.message}", e)
                    null
                }
            }
            else -> null
        }
    }

    private fun extractOutputPath(command: String): String {
        // Try quoted path first (preferred — all new commands use this)
        val quotedRegex = """"([^"]*)"\s*$""".toRegex()
        quotedRegex.find(command)?.groupValues?.get(1)?.let { return it }

        // Fallback: last whitespace-delimited token (for unquoted legacy commands)
        return command.trimEnd().split("\\s+".toRegex()).lastOrNull() ?: ""
    }
}