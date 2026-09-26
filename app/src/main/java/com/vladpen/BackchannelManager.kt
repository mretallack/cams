package com.vladpen

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.net.URI

/**
 * Coordinates audio capture, encoding, and RTSP backchannel sending.
 * Manages the lifecycle of AudioRecord and BackchannelClient.
 */
class BackchannelManager {

    enum class State { IDLE, CONNECTING, ACTIVE, STOPPING }

    @Volatile
    var state: State = State.IDLE
        private set

    private var client: BackchannelClient? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var codec: BackchannelClient.Codec = BackchannelClient.Codec.PCMU

    /**
     * Detect if the camera at the given RTSP URL supports backchannel audio.
     * Call from a background thread.
     */
    fun detectBackchannel(rtspUrl: String): Boolean {
        val (host, port, path, username, password) = parseRtspUrl(rtspUrl) ?: return false
        val c = BackchannelClient()
        return c.detectBackchannel(host, port, path, username, password) != null
    }

    /**
     * Start backchannel audio: connect to camera, begin mic capture and sending.
     * Call from a background thread. Returns true if started successfully.
     */
    fun start(rtspUrl: String): Boolean {
        if (state == State.ACTIVE || state == State.CONNECTING) return false

        state = State.CONNECTING
        val (host, port, path, username, password) = parseRtspUrl(rtspUrl) ?: run {
            android.util.Log.e("BACKCHANNEL", "start: failed to parse URL: $rtspUrl")
            state = State.IDLE
            return false
        }

        val c = BackchannelClient()
        android.util.Log.d("BACKCHANNEL", "start: connecting to $host:$port$path")
        if (!c.start(host, port, path, username, password)) {
            android.util.Log.e("BACKCHANNEL", "start: BackchannelClient.start() failed")
            state = State.IDLE
            return false
        }
        client = c

        if (!startAudioCapture()) {
            android.util.Log.e("BACKCHANNEL", "start: startAudioCapture() failed")
            c.stop()
            client = null
            state = State.IDLE
            return false
        }

        state = State.ACTIVE
        android.util.Log.d("BACKCHANNEL", "start: active, sending audio")
        return true
    }

    /**
     * Stop backchannel audio: stop mic capture and disconnect.
     */
    fun stop() {
        if (state == State.IDLE || state == State.STOPPING) return
        state = State.STOPPING
        stopAudioCapture()
        client?.stop()
        client = null
        state = State.IDLE
    }

    private fun startAudioCapture(): Boolean {
        val sampleRate = 8000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) return false

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(320 * 4) // At least 4 frames of 160 samples
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }
        } catch (e: SecurityException) {
            return false
        }

        audioRecord?.startRecording()
        audioThread = Thread({
            val buffer = ShortArray(160) // 20ms at 8kHz
            val gain = 8 // Amplify mic input
            while (state == State.ACTIVE) {
                val read = audioRecord?.read(buffer, 0, 160) ?: break
                if (read > 0) {
                    // Apply gain
                    for (i in 0 until read) {
                        val amplified = buffer[i].toInt() * gain
                        buffer[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                    val encoded = when (codec) {
                        BackchannelClient.Codec.PCMU -> G711Encoder.encodeUlaw(buffer, 0, read)
                        BackchannelClient.Codec.PCMA -> G711Encoder.encodeAlaw(buffer, 0, read)
                        BackchannelClient.Codec.AAC -> continue // Not implemented yet
                    }
                    if (client?.sendAudio(encoded) == false) {
                        // Connection lost
                        break
                    }
                }
            }
        }, "BackchannelAudio").apply { start() }

        return true
    }

    private fun stopAudioCapture() {
        audioThread?.let { thread ->
            // State change will cause the loop to exit
            try { thread.join(1000) } catch (_: InterruptedException) {}
        }
        audioThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    internal data class RtspUrlParts(
        val host: String,
        val port: Int,
        val path: String,
        val username: String,
        val password: String
    )

    internal fun parseRtspUrl(url: String): RtspUrlParts? {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 554
            val path = uri.path ?: "/ch0"
            val userInfo = uri.userInfo
            val username: String
            val password: String
            if (userInfo != null && userInfo.contains(":")) {
                username = userInfo.substringBefore(":")
                password = userInfo.substringAfter(":")
            } else {
                return null
            }
            RtspUrlParts(host, port, path, username, password)
        } catch (e: Exception) {
            null
        }
    }
}
