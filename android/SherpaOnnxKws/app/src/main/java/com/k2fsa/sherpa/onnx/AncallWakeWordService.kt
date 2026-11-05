package com.k2fsa.sherpa.onnx.kws

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.k2fsa.sherpa.onnx.getKeywordsFile
import com.k2fsa.sherpa.onnx.getKwsModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Diagnostic ANCALL foreground wake-word service. */
class AncallWakeWordService : Service() {
    companion object {
        const val ACTION_START = "com.ancall.action.START_WAKE_LISTENER"
        const val ACTION_STOP = "com.ancall.action.STOP_WAKE_LISTENER"
        private const val CHANNEL_ID = "ancall_next_wake_listener"
        private const val NOTIFICATION_ID = 4117
        private const val TAG = "ANCALL-WakeNext"
        private const val SAMPLE_RATE = 16000
    }

    private val listening = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var stream: OnlineStream? = null
    @Volatile private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START, null -> {
                startForeground(NOTIFICATION_ID, buildNotification("Starting ANCALL Next…"))
                startListening()
            }
            else -> Log.w(TAG, "Ignoring unknown service action: ${intent.action}")
        }
        return START_STICKY
    }

    private fun startListening() {
        if (!listening.compareAndSet(false, true)) return

        worker = thread(name = "ancall-next-wake-audio") {
            try {
                val keywordsFile = getKeywordsFile(type = 1)
                val modelConfig = getKwsModelConfig(type = 1)
                    ?: error("English GigaSpeech wake-word model configuration is missing")
                Log.i(TAG, "Loading English GigaSpeech KWS model; keywords=$keywordsFile")

                val config = KeywordSpotterConfig(
                    featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = modelConfig,
                    keywordsFile = keywordsFile,
                )
                val localSpotter = KeywordSpotter(assetManager = assets, config = config)
                spotter = localSpotter
                val localStream = localSpotter.createStream()
                stream = localStream
                Log.i(TAG, "KeywordSpotter initialized successfully")

                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(minBuffer > 0) { "AudioRecord returned invalid buffer size: $minBuffer" }

                val localRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer * 2, SAMPLE_RATE)
                )
                check(localRecorder.state == AudioRecord.STATE_INITIALIZED) {
                    "Could not initialize the microphone"
                }
                recorder = localRecorder
                localRecorder.startRecording()
                check(localRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Microphone did not enter recording state"
                }
                Log.i(TAG, "Microphone recording at 16000 Hz, mono PCM16")
                updateNotification("Listening for “Hey ANCALL”")

                val buffer = ShortArray(1600)
                var lastAudioLogMs = SystemClock.elapsedRealtime()
                var totalSamples = 0L
                while (listening.get()) {
                    val count = localRecorder.read(buffer, 0, buffer.size)
                    if (count < 0) {
                        throw IllegalStateException("AudioRecord.read failed with code $count")
                    }
                    if (count == 0) continue

                    var peak = 0
                    var sumSquares = 0.0
                    for (i in 0 until count) {
                        val value = buffer[i].toInt()
                        val magnitude = kotlin.math.abs(value)
                        if (magnitude > peak) peak = magnitude
                        val normalized = value / 32768.0
                        sumSquares += normalized * normalized
                    }
                    totalSamples += count
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAudioLogMs >= 5000) {
                        val rms = kotlin.math.sqrt(sumSquares / count)
                        Log.i(TAG, "Audio OK: samples=$totalSamples peak=$peak rms=%.5f".format(rms))
                        lastAudioLogMs = now
                    }

                    val samples = FloatArray(count) { buffer[it] / 32768.0f }
                    localStream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                    while (localSpotter.isReady(localStream)) {
                        localSpotter.decode(localStream)
                        val detected = localSpotter.getResult(localStream).keyword
                        if (detected.isNotBlank()) {
                            Log.i(TAG, "Wake phrase detected: $detected")
                            listening.set(false)
                            releaseAudioResources()
                            updateNotification("Hey ANCALL detected — opening your assistant")
                            launchTermuxAssistant()
                            break
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Wake listener failed", error)
                listening.set(false)
                releaseAudioResources()
                updateNotification("Listener stopped: ${error.message ?: "check microphone permission"}")
            }
        }
    }

    private fun launchTermuxAssistant() {
        try {
            val command = "python ~/ancall-android/voice/assistant.py; " +
                "am start -n com.ancall.voice.next/com.k2fsa.sherpa.onnx.kws.MainActivity " +
                "-a com.ancall.action.START_WAKE_LISTENER"
            val run = Intent("com.termux.RUN_COMMAND").apply {
                component = ComponentName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            startService(run)
            updateNotification("ANCALL is responding — listener will resume afterwards")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start the Termux assistant", error)
            updateNotification("Wake phrase detected. Check Termux external-command permission.")
        }
    }

    private fun stopListening() {
        listening.set(false)
        worker?.interrupt()
        releaseAudioResources()
    }

    @Synchronized
    private fun releaseAudioResources() {
        try { recorder?.let { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
        catch (error: Exception) { Log.w(TAG, "Recorder stop: ${error.message}") }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { stream?.release() } catch (_: Exception) {}
        stream = null
        try { spotter?.release() } catch (_: Exception) {}
        spotter = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ANCALL Next wake listener", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when ANCALL Next is listening for its wake phrase"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = openApp?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }
        val stopIntent = Intent(this, AncallWakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ANCALL Next voice assistant")
            .setContentText(message)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
