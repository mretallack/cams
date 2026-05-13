package com.vladpen

import org.junit.Test
import org.junit.Assert.*

class BackchannelManagerTest {

    @Test
    fun initialStateIsIdle() {
        val manager = BackchannelManager()
        assertEquals(BackchannelManager.State.IDLE, manager.state)
    }

    @Test
    fun stopWhenIdleIsNoOp() {
        val manager = BackchannelManager()
        manager.stop()
        assertEquals(BackchannelManager.State.IDLE, manager.state)
    }

    @Test
    fun parseRtspUrlValid() {
        val manager = BackchannelManager()
        val parts = manager.parseRtspUrl("rtsp://user:pass@10.0.0.63:554/ch0")
        assertNotNull(parts)
        assertEquals("10.0.0.63", parts!!.host)
        assertEquals(554, parts.port)
        assertEquals("/ch0", parts.path)
        assertEquals("user", parts.username)
        assertEquals("pass", parts.password)
    }

    @Test
    fun parseRtspUrlDefaultPort() {
        val manager = BackchannelManager()
        val parts = manager.parseRtspUrl("rtsp://user:pass@10.0.0.63/ch0")
        assertNotNull(parts)
        assertEquals(554, parts!!.port)
    }

    @Test
    fun parseRtspUrlNoCredentials() {
        val manager = BackchannelManager()
        val parts = manager.parseRtspUrl("rtsp://10.0.0.63:554/ch0")
        assertNull(parts)
    }

    @Test
    fun parseRtspUrlNoPassword() {
        val manager = BackchannelManager()
        val parts = manager.parseRtspUrl("rtsp://user@10.0.0.63:554/ch0")
        assertNull(parts)
    }

    @Test
    fun parseRtspUrlInvalid() {
        val manager = BackchannelManager()
        assertNull(manager.parseRtspUrl("not a url"))
        assertNull(manager.parseRtspUrl(""))
    }

    @Test
    fun parseRtspUrlSpecialCharsInPassword() {
        val manager = BackchannelManager()
        val parts = manager.parseRtspUrl("rtsp://thingino:UhilRdFonDZzxvio4Z9m@10.0.0.63:554/ch0")
        assertNotNull(parts)
        assertEquals("thingino", parts!!.username)
        assertEquals("UhilRdFonDZzxvio4Z9m", parts.password)
    }

    @Test
    fun parseRtspUrlCustomPort() {
        val manager = BackchannelManager()
        val parts = manager.parseRtspUrl("rtsp://user:pass@192.168.1.1:8554/stream")
        assertNotNull(parts)
        assertEquals(8554, parts!!.port)
        assertEquals("/stream", parts.path)
    }
}
