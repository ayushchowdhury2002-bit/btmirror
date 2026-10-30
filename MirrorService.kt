package com.example.btmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Captures the audio other apps play on this device (AudioPlaybackCapture) and
 * re-plays it, in parallel, to every selected Bluetooth output.
 */
class MirrorService : Service() {

    companion object {
        @Volatile var running = false

        const val SAMPLE_RATE = 48000
        const val CHUNK_MS = 10
        // 10 ms of 16-bit stereo = 480 frames * 4 bytes
        const val CHUNK_BYTES = SAMPLE_RATE * CHUNK_MS / 1000 * 4
        private const val CHANNEL_ID = "mirror"
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val sinks = ArrayList<Sink>()
    @Volatile private var active = false

    /** One Bluetooth output: its own AudioTrack, thread and small queue. */
    private class Sink(val track: AudioTrack, delayMs: Int) {
        private val delayChunks = delayMs / CHUNK_MS
        // Queue is pre-filled with silence = the delay; only the newest audio is kept beyond that.
        private val queue = ArrayBlockingQueue<ByteArray>(delayChunks + 6)
        @Volatile private var alive = true
        private val thread = Thread({ loop() }, "sink")

        init {
            repeat(delayChunks) { queue.offer(ByteArray(CHUNK_BYTES)) }
        }

        fun start() {
            track.play()
            thread.start()
        }

        fun offer(chunk: ByteArray) {
            if (!queue.offer(chunk)) {   // sink is behind: drop oldest to stay low-latency
                queue.poll()
                queue.offer(chunk)
            }
        }

        private fun loop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (alive) {
                val b = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                track.write(b, 0, b.size)
            }
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }

        fun stop() {
            alive = false
            thread.join(500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || running) return START_NOT_STICKY

        startAsForeground()

        val resultCode = intent.getIntExtra("resultCode", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val ids = intent.getIntArrayExtra("ids") ?: IntArray(0)
        val delays = intent.getIntArrayExtra("delays") ?: IntArray(0)
        if (data == null || ids.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, null)
            startMirroring(ids, delays)
            running = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio mirroring", NotificationManager.IMPORTANCE_LOW)
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BT Mirror is running")
            .setContentText("Playing device audio to selected Bluetooth outputs")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun startMirroring(ids: IntArray, delays: IntArray) {
        val am = getSystemService(AudioManager::class.java)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val outFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )

        ids.forEachIndexed { i, id ->
            val dev = outputs.firstOrNull { it.id == id } ?: return@forEachIndexed
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minOut, CHUNK_BYTES * 4))
                .build()
            track.setPreferredDevice(dev)
            sinks.add(Sink(track, delays.getOrElse(i) { 0 }))
        }
        if (sinks.isEmpty()) { stopSelf(); return }

        // Capture everything other apps play. Exclude ourselves to avoid a feedback loop.
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())
            .build()
        val inFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        record = AudioRecord.Builder()
            .setAudioFormat(inFormat)
            .setAudioPlaybackCaptureConfig(capture)
            .setBufferSizeInBytes(maxOf(minIn, CHUNK_BYTES * 4))
            .build()

        sinks.forEach { it.start() }
        record!!.startRecording()
        active = true

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val rec = record!!
            while (active) {
                val buf = ByteArray(CHUNK_BYTES)
                var off = 0
                while (off < CHUNK_BYTES && active) {
                    val n = rec.read(buf, off, CHUNK_BYTES - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == CHUNK_BYTES) {
                    // Each sink gets its own copy so they never share a buffer.
                    sinks.forEach { it.offer(buf.copyOf()) }
                }
            }
        }, "capture").also { it.start() }
    }

    override fun onDestroy() {
        active = false
        running = false
        captureThread?.join(500)
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        sinks.forEach { it.stop() }
        sinks.clear()
        projection?.stop()
        super.onDestroy()
    }
}
