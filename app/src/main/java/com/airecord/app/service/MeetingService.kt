package com.airecord.app.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.airecord.app.MainActivity
import com.airecord.app.R
import com.airecord.app.container
import com.airecord.app.core.AudioMath
import com.airecord.app.core.TimeFormat
import com.airecord.app.core.Wav
import com.airecord.app.core.WavWriter
import com.airecord.app.data.MeetingStatus
import com.airecord.app.stt.WhisperLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns both the microphone recording and the
 * (long-running) on-device transcription/summarisation queue, so both keep
 * running when the screen is off or the app is in the background.
 */
class MeetingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { applicationContext.container }
    private val processor by lazy { MeetingProcessor(applicationContext, container) }

    private var recordJob: Job? = null
    private var workerJob: Job? = null
    private val queue = ArrayDeque<Pair<String, ProcessingTask>>()

    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var cancelRequested = false

    private var recording = false
    private var processing = false
    private var currentTypes = -1
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        // Keep the notification in sync with recorder/processing state, at most once a second.
        scope.launch {
            combine(ServiceStatus.recorder, ServiceStatus.processing, ServiceStatus.queue) { _, _, _ -> }
                .conflate()
                .collect {
                    if (recording || processing) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
                    }
                    delay(1000)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_START_RECORDING -> if (id != null) startRecording(id)
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_STOP_RECORDING -> stopRequested = true
            ACTION_ENQUEUE -> if (id == null) satisfyForegroundContract() else {
                val task = intent.getStringExtra(EXTRA_TASK)?.let { ProcessingTask.valueOf(it) } ?: ProcessingTask.FULL
                enqueue(id, task)
            }
            ACTION_CANCEL -> cancel(id)
        }
        stopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        cancelRequested = true
        WhisperLib.takeIf { it.isAvailable }?.abort()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Recording -------------------------------------------------------------------------

    private fun startRecording(meetingId: String) {
        if (recordJob?.isActive == true) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            container.meetings.update(meetingId) { it.copy(status = MeetingStatus.ERROR, error = "마이크 권한이 없습니다.") }
            satisfyForegroundContract()
            return
        }
        paused = false
        stopRequested = false
        recording = true
        ServiceStatus.recorderFlow.value = RecorderState(meetingId = meetingId, active = true)
        updateForeground()

        val file = container.meetings.audioFile(meetingId)
        recordJob = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { recordLoop(file) } }
            recording = false
            ServiceStatus.recorderFlow.value = RecorderState()
            result.fold(
                onSuccess = { durationMs ->
                    container.meetings.update(meetingId) { it.copy(status = MeetingStatus.RECORDED, durationMs = durationMs) }
                    if (container.settings.state.value.autoProcess && durationMs >= 1000) {
                        enqueue(meetingId, ProcessingTask.FULL)
                    }
                },
                onFailure = { t ->
                    container.meetings.update(meetingId) {
                        it.copy(status = MeetingStatus.ERROR, error = "녹음 실패: ${t.message}", durationMs = Wav.durationMs(file))
                    }
                },
            )
            updateForeground()
            stopIfIdle()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordLoop(file: java.io.File): Long {
        val rate = Wav.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate) * 2,
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "마이크를 사용할 수 없습니다" }
        val buffer = ShortArray(rate / 10) // 100 ms
        try {
            return WavWriter(file).use { writer ->
                recorder.startRecording()
                while (!stopRequested && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n < 0) error("AudioRecord 오류 ($n)")
                    if (n == 0) continue
                    if (paused) {
                        ServiceStatus.recorderFlow.update { it.copy(level = 0f) }
                        continue
                    }
                    writer.write(buffer, n)
                    val elapsed = writer.samplesWritten * 1000 / rate
                    val level = AudioMath.meterLevel(AudioMath.rms(buffer, n))
                    ServiceStatus.recorderFlow.update { it.copy(elapsedMs = elapsed, level = level) }
                }
                writer.samplesWritten * 1000 / rate
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun setPaused(value: Boolean) {
        paused = value
        ServiceStatus.recorderFlow.update { it.copy(paused = value) }
    }

    // ---- Processing queue ------------------------------------------------------------------

    private fun enqueue(meetingId: String, task: ProcessingTask) {
        if (queue.any { it.first == meetingId } || ServiceStatus.processing.value?.meetingId == meetingId) {
            satisfyForegroundContract()
            return
        }
        queue.addLast(meetingId to task)
        ServiceStatus.queueFlow.value = queue.map { it.first }
        processing = true
        updateForeground()
        if (workerJob?.isActive != true) workerJob = scope.launch { runWorker() }
    }

    private suspend fun runWorker() {
        acquireWakeLock()
        try {
            while (true) {
                // Don't compete with an ongoing recording for CPU / memory.
                ServiceStatus.recorder.first { !it.active }
                val (id, task) = queue.removeFirstOrNull() ?: break
                ServiceStatus.queueFlow.value = queue.map { it.first }
                cancelRequested = false
                if (WhisperLib.isAvailable) WhisperLib.resetAbort()
                ServiceStatus.processingFlow.value = ProcessingState(id, Stage.WAITING)
                withContext(Dispatchers.Default) {
                    processor.process(
                        meetingId = id,
                        task = task,
                        onState = { ServiceStatus.processingFlow.value = it },
                        isCancelled = { cancelRequested },
                    )
                }
                ServiceStatus.processingFlow.value = null
            }
        } finally {
            ServiceStatus.processingFlow.value = null
            processing = false
            releaseWakeLock()
            updateForeground()
            stopIfIdle()
        }
    }

    private fun cancel(meetingId: String?) {
        if (meetingId != null && queue.removeAll { it.first == meetingId }) {
            ServiceStatus.queueFlow.value = queue.map { it.first }
        }
        val current = ServiceStatus.processing.value
        if (current != null && (meetingId == null || current.meetingId == meetingId)) {
            cancelRequested = true
            if (WhisperLib.isAvailable) WhisperLib.abort()
        }
    }

    // ---- Foreground / notification ---------------------------------------------------------

    private fun updateForeground() {
        if (!recording && !processing) return
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (recording) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (processing) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (types != currentTypes) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            currentTypes = types
        }
    }

    /**
     * A service started with startForegroundService() must call startForeground() even if it
     * immediately has nothing to do, or the system crashes the app.
     */
    private fun satisfyForegroundContract() {
        if (currentTypes != -1) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        currentTypes = type
    }

    private fun stopIfIdle() {
        if (recording || processing || recordJob?.isActive == true || workerJob?.isActive == true) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        currentTypes = -1
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val rec = ServiceStatus.recorder.value
        val proc = ServiceStatus.processing.value
        if (rec.active) {
            builder.setContentTitle(if (rec.paused) "녹음 일시정지" else "회의 녹음 중")
                .setContentText(TimeFormat.duration(rec.elapsedMs))
                .addAction(
                    0, if (rec.paused) "계속" else "일시정지",
                    servicePending(if (rec.paused) ACTION_RESUME else ACTION_PAUSE, 1),
                )
                .addAction(0, "중지", servicePending(ACTION_STOP_RECORDING, 2))
        } else {
            val queued = ServiceStatus.queue.value.size
            builder.setContentTitle(proc?.stage?.label ?: "AI 처리 준비 중")
                .setContentText(
                    listOfNotNull(
                        proc?.progress?.let { "${(it * 100).toInt()}%" },
                        proc?.detail?.takeIf { it.isNotEmpty() },
                        if (queued > 0) "대기 $queued 건" else null,
                    ).joinToString(" · "),
                )
                .setProgress(100, ((proc?.progress ?: 0f) * 100).toInt(), proc?.progress == null)
                .addAction(0, "취소", servicePending(ACTION_CANCEL, 3))
        }
        return builder.build()
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode, Intent(this, MeetingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Airecord:processing")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "meeting"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_ID = "meeting_id"
        private const val EXTRA_TASK = "task"
        private const val ACTION_START_RECORDING = "start_recording"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_STOP_RECORDING = "stop_recording"
        private const val ACTION_ENQUEUE = "enqueue"
        private const val ACTION_CANCEL = "cancel"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "녹음 및 AI 처리", NotificationManager.IMPORTANCE_LOW),
            )
        }

        private fun intent(context: Context, action: String, id: String? = null) =
            Intent(context, MeetingService::class.java).setAction(action).apply { id?.let { putExtra(EXTRA_ID, it) } }

        fun startRecording(context: Context, meetingId: String) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_START_RECORDING, meetingId))

        fun stopRecording(context: Context) = context.startService(intent(context, ACTION_STOP_RECORDING))
        fun pause(context: Context) = context.startService(intent(context, ACTION_PAUSE))
        fun resume(context: Context) = context.startService(intent(context, ACTION_RESUME))
        fun cancel(context: Context, meetingId: String) = context.startService(intent(context, ACTION_CANCEL, meetingId))

        fun enqueue(context: Context, meetingId: String, task: ProcessingTask) =
            ContextCompat.startForegroundService(
                context, intent(context, ACTION_ENQUEUE, meetingId).putExtra(EXTRA_TASK, task.name),
            )
    }
}
