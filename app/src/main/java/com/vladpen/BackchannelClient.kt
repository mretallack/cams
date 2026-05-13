package com.vladpen

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest

/**
 * RTSP backchannel client for sending audio to camera speaker.
 * Handles RTSP signaling (DESCRIBE/SETUP/PLAY/TEARDOWN) and RTP packet sending.
 */
class BackchannelClient {

    data class BackchannelTrack(
        val codec: Codec,
        val payloadType: Int,
        val sampleRate: Int,
        val controlUrl: String
    )

    enum class Codec { PCMU, PCMA, AAC }

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var cseq = 0
    private var session: String? = null
    private var rtpPacket: RtpPacket? = null
    private var interleavedChannel = 0

    /**
     * Parse SDP content and extract backchannel (sendonly) audio tracks.
     * Returns list of available backchannel tracks, preferred first (PCMU > PCMA > AAC).
     */
    fun parseSdpForBackchannel(sdp: String): List<BackchannelTrack> {
        val tracks = mutableListOf<BackchannelTrack>()
        val mediaBlocks = splitMediaBlocks(sdp)

        for (block in mediaBlocks) {
            if (!block.contains("a=sendonly")) continue
            val mLine = block.lines().firstOrNull { it.startsWith("m=audio") } ?: continue

            val parts = mLine.split(" ")
            if (parts.size < 4) continue
            val payloadType = parts[3].trim().toIntOrNull() ?: continue

            val rtpmap = block.lines()
                .firstOrNull { it.startsWith("a=rtpmap:$payloadType ") }
                ?.substringAfter("a=rtpmap:$payloadType ")
                ?: continue

            val codec = when {
                rtpmap.uppercase().startsWith("PCMU") -> Codec.PCMU
                rtpmap.uppercase().startsWith("PCMA") -> Codec.PCMA
                rtpmap.uppercase().contains("MPEG4-GENERIC") -> Codec.AAC
                else -> continue
            }

            val sampleRate = rtpmap.split("/").getOrNull(1)?.toIntOrNull() ?: 8000

            val controlUrl = block.lines()
                .firstOrNull { it.startsWith("a=control:") }
                ?.substringAfter("a=control:")
                ?.trim()
                ?: continue

            tracks.add(BackchannelTrack(codec, payloadType, sampleRate, controlUrl))
        }

        // Sort: PCMU first, then PCMA, then AAC
        return tracks.sortedBy { track ->
            when (track.codec) {
                Codec.PCMU -> 0
                Codec.PCMA -> 1
                Codec.AAC -> 2
            }
        }
    }

    /**
     * Split SDP into media blocks. Each block starts with "m=" line.
     */
    private fun splitMediaBlocks(sdp: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = sdp.lines()
        var currentBlock = StringBuilder()
        var inMedia = false

        for (line in lines) {
            if (line.startsWith("m=")) {
                if (inMedia) blocks.add(currentBlock.toString())
                currentBlock = StringBuilder()
                inMedia = true
            }
            if (inMedia) currentBlock.appendLine(line)
        }
        if (inMedia) blocks.add(currentBlock.toString())
        return blocks
    }

    /**
     * Connect to camera RTSP server and detect backchannel support.
     * Returns the preferred backchannel track, or null if not supported.
     */
    fun detectBackchannel(host: String, port: Int, path: String, username: String, password: String): BackchannelTrack? {
        try {
            connect(host, port)
            val sdp = describe(host, port, path, username, password) ?: return null
            val tracks = parseSdpForBackchannel(sdp)
            disconnect()
            return tracks.firstOrNull()
        } catch (e: Exception) {
            disconnect()
            return null
        }
    }

    /**
     * Start backchannel session: connect, DESCRIBE, SETUP, PLAY.
     */
    fun start(host: String, port: Int, path: String, username: String, password: String): Boolean {
        try {
            connect(host, port)
            android.util.Log.d("BACKCHANNEL", "client.start: connected")
            val sdp = describe(host, port, path, username, password)
            android.util.Log.d("BACKCHANNEL", "client.start: describe result=${sdp?.length ?: "null"} chars")
            if (sdp == null) return false
            val tracks = parseSdpForBackchannel(sdp)
            android.util.Log.d("BACKCHANNEL", "client.start: found ${tracks.size} backchannel tracks")
            val track = tracks.firstOrNull() ?: return false

            val setupUrl = resolveControlUrl(host, port, path, track.controlUrl)
            android.util.Log.d("BACKCHANNEL", "client.start: SETUP url=$setupUrl")
            if (!setup(setupUrl, host, port, path, username, password)) {
                android.util.Log.e("BACKCHANNEL", "client.start: SETUP failed")
                return false
            }
            android.util.Log.d("BACKCHANNEL", "client.start: SETUP ok, session=$session")
            if (!play(host, port, path, username, password)) {
                android.util.Log.e("BACKCHANNEL", "client.start: PLAY failed")
                return false
            }
            android.util.Log.d("BACKCHANNEL", "client.start: PLAY ok, ready to send audio")

            rtpPacket = RtpPacket(track.payloadType, (System.currentTimeMillis() and 0xFFFFFFFF).toInt())
            return true
        } catch (e: Exception) {
            android.util.Log.e("BACKCHANNEL", "client.start: exception: ${e.message}", e)
            disconnect()
            return false
        }
    }

    /**
     * Send encoded audio data as RTP over the interleaved channel.
     * Returns false if the connection is lost.
     */
    fun sendAudio(encodedAudio: ByteArray): Boolean {
        val rtp = rtpPacket ?: return false
        val packet = rtp.build(encodedAudio)
        val frame = rtp.wrapInterleaved(packet, interleavedChannel)
        return try {
            output?.write(frame)
            output?.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop the backchannel session.
     */
    fun stop() {
        try {
            session?.let { teardown() }
        } catch (e: Exception) {
            // Ignore teardown errors
        }
        disconnect()
        rtpPacket = null
        session = null
    }

    private fun connect(host: String, port: Int) {
        socket = Socket(host, port).apply { soTimeout = 5000 }
        output = socket!!.getOutputStream()
        input = socket!!.getInputStream()
        cseq = 0
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        input = null
    }

    private var authRealm: String? = null
    private var authNonce: String? = null
    private var authUsername: String? = null
    private var authPassword: String? = null

    private fun describe(host: String, port: Int, path: String, username: String, password: String): String? {
        val url = "rtsp://$host:$port$path"
        authUsername = username
        authPassword = password
        // First attempt without auth to get nonce
        val resp1 = sendRequest("DESCRIBE", url, mapOf(
            "Accept" to "application/sdp",
            "Require" to "www.onvif.org/ver20/backchannel"
        ))
        if (resp1.startsWith("RTSP/1.0 200")) return extractBody(resp1)

        // Parse 401 for digest auth
        authRealm = extractHeaderValue(resp1, "realm")
        authNonce = extractHeaderValue(resp1, "nonce")
        if (authRealm == null || authNonce == null) return null

        val authHeader = computeDigestAuth("DESCRIBE", url, username, password, authRealm!!, authNonce!!)
        val resp2 = sendRequest("DESCRIBE", url, mapOf(
            "Accept" to "application/sdp",
            "Require" to "www.onvif.org/ver20/backchannel",
            "Authorization" to authHeader
        ))
        return if (resp2.startsWith("RTSP/1.0 200")) extractBody(resp2) else null
    }

    private fun setup(url: String, host: String, port: Int, path: String, username: String, password: String): Boolean {
        val headers = mutableMapOf(
            "Transport" to "RTP/AVP/TCP;unicast;interleaved=$interleavedChannel-${interleavedChannel + 1}",
            "Require" to "www.onvif.org/ver20/backchannel"
        )
        if (authRealm != null && authNonce != null) {
            headers["Authorization"] = computeDigestAuth("SETUP", url, username, password, authRealm!!, authNonce!!)
        }

        val resp = sendRequest("SETUP", url, headers)
        if (resp.startsWith("RTSP/1.0 200")) {
            session = extractSession(resp)
            return session != null
        }

        // Try with fresh nonce from 401
        val realm = extractHeaderValue(resp, "realm")
        val nonce = extractHeaderValue(resp, "nonce")
        if (realm != null && nonce != null) {
            authRealm = realm
            authNonce = nonce
            headers["Authorization"] = computeDigestAuth("SETUP", url, username, password, realm, nonce)
            val resp2 = sendRequest("SETUP", url, headers)
            if (resp2.startsWith("RTSP/1.0 200")) {
                session = extractSession(resp2)
                return session != null
            }
        }
        return false
    }

    private fun play(host: String, port: Int, path: String, username: String, password: String): Boolean {
        val url = "rtsp://$host:$port$path"
        val headers = mutableMapOf(
            "Require" to "www.onvif.org/ver20/backchannel"
        )
        session?.let { headers["Session"] = it }
        if (authRealm != null && authNonce != null) {
            headers["Authorization"] = computeDigestAuth("PLAY", url, username, password, authRealm!!, authNonce!!)
        }

        val resp = sendRequest("PLAY", url, headers)
        android.util.Log.d("BACKCHANNEL", "PLAY response: ${resp.take(200)}")
        if (resp.startsWith("RTSP/1.0 200")) return true

        // Try with fresh nonce
        val realm = extractHeaderValue(resp, "realm")
        val nonce = extractHeaderValue(resp, "nonce")
        if (realm != null && nonce != null) {
            authRealm = realm
            authNonce = nonce
            headers["Authorization"] = computeDigestAuth("PLAY", url, username, password, realm, nonce)
            val resp2 = sendRequest("PLAY", url, headers)
            android.util.Log.d("BACKCHANNEL", "PLAY retry response: ${resp2.take(200)}")
            return resp2.startsWith("RTSP/1.0 200")
        }
        return false
    }

    private fun teardown() {
        val url = session?.let { "rtsp://" } ?: return // simplified, just close
        // Best effort teardown
        disconnect()
    }

    private fun sendRequest(method: String, url: String, headers: Map<String, String>): String {
        cseq++
        val sb = StringBuilder()
        sb.append("$method $url RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        for ((key, value) in headers) {
            sb.append("$key: $value\r\n")
        }
        sb.append("\r\n")

        output?.write(sb.toString().toByteArray())
        output?.flush()
        return readResponse()
    }

    private fun readResponse(): String {
        val buffer = ByteArray(8192)
        val sb = StringBuilder()
        val inp = input ?: return ""

        while (true) {
            val b = inp.read()
            if (b < 0) break

            // Skip interleaved RTP frames (start with '$' = 0x24)
            if (b == 0x24 && sb.isEmpty()) {
                // Read channel (1 byte) + length (2 bytes)
                val channel = inp.read()
                val lenHi = inp.read()
                val lenLo = inp.read()
                if (channel < 0 || lenHi < 0 || lenLo < 0) break
                val frameLen = (lenHi shl 8) or lenLo
                // Skip the RTP payload
                var skipped = 0
                while (skipped < frameLen) {
                    val s = inp.skip((frameLen - skipped).toLong()).toInt()
                    if (s <= 0) { inp.read(); skipped++ } else skipped += s
                }
                continue
            }

            sb.append(b.toChar())

            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) {
                // Check for content-length
                val clMatch = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(sb)
                if (clMatch != null) {
                    val contentLength = clMatch.groupValues[1].toInt()
                    var read = 0
                    while (read < contentLength) {
                        val n = inp.read(buffer, 0, minOf(buffer.size, contentLength - read))
                        if (n <= 0) break
                        sb.append(String(buffer, 0, n))
                        read += n
                    }
                }
                break
            }
        }
        return sb.toString()
    }

    private fun extractBody(response: String): String? {
        val idx = response.indexOf("\r\n\r\n")
        return if (idx >= 0) response.substring(idx + 4) else null
    }

    private fun extractSession(response: String): String? {
        val match = Regex("Session:\\s*([^;\\r\\n]+)").find(response)
        return match?.groupValues?.get(1)?.trim()
    }

    private fun extractHeaderValue(response: String, key: String): String? {
        val match = Regex("$key=\"([^\"]+)\"").find(response)
        return match?.groupValues?.get(1)
    }

    internal fun resolveControlUrl(host: String, port: Int, path: String, controlUrl: String): String {
        return if (controlUrl.startsWith("rtsp://")) {
            controlUrl
        } else {
            "rtsp://$host:$port$path/$controlUrl"
        }
    }

    companion object {
        fun computeDigestAuth(
            method: String, uri: String, username: String, password: String,
            realm: String, nonce: String
        ): String {
            val ha1 = md5("$username:$realm:$password")
            val ha2 = md5("$method:$uri")
            val response = md5("$ha1:$nonce:$ha2")
            return "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        }

        private fun md5(input: String): String {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(input.toByteArray())
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
