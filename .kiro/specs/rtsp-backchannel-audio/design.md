# Design: RTSP Backchannel Audio

## Architecture Overview

The backchannel audio feature operates independently from VLC playback. It uses a separate RTSP TCP connection to negotiate and send audio to the camera.

```
┌─────────────────────────────────────────────────────────┐
│                    Android App                           │
│                                                         │
│  ┌──────────┐    ┌──────────────┐    ┌───────────────┐ │
│  │AudioRecord│───▶│ G.711 Encoder│───▶│ RTP Packetizer│ │
│  │ (8kHz/16b)│    │  (μ-law LUT) │    │  (12B header) │ │
│  └──────────┘    └──────────────┘    └───────┬───────┘ │
│                                              │         │
│  ┌──────────────────────────────────────────┐│         │
│  │        BackchannelClient                 ││         │
│  │  ┌────────┐ ┌─────┐ ┌────┐ ┌──────────┐││         │
│  │  │DESCRIBE│▶│SETUP│▶│PLAY│▶│Send RTP   │◀┘         │
│  │  └────────┘ └─────┘ └────┘ │(interleaved)│         │
│  │                             └──────┬─────┘          │
│  └────────────────────────────────────┼────────────────┘│
└───────────────────────────────────────┼─────────────────┘
                                        │ TCP
                                        ▼
                              ┌──────────────────┐
                              │  Camera (prudynt) │
                              │  RTSP Server      │
                              │  Port 554         │
                              └──────────────────┘
```

## Components

### 1. BackchannelClient

Handles RTSP signaling over a dedicated TCP connection.

**Location**: `app/src/main/java/com/vladpen/BackchannelClient.kt`

**Responsibilities**:
- Open TCP socket to camera RTSP port
- Perform DESCRIBE with `Require: www.onvif.org/ver20/backchannel`
- Parse SDP to find sendonly audio tracks
- SETUP the preferred backchannel track (PCMU > PCMA > AAC)
- PLAY to start the session
- Send RTP packets via TCP interleaved framing (`$` + channel + length + data)
- Handle Digest authentication
- TEARDOWN on stop

**Key design decisions**:
- Separate TCP connection from VLC's playback connection (VLC doesn't expose its socket)
- TCP interleaved transport (no NAT/firewall issues, simpler than UDP)
- Reuses the same RTSP credentials already stored in `StreamDataModel`

### 2. G711Encoder

Simple μ-law / A-law encoding via lookup table.

**Location**: `app/src/main/java/com/vladpen/G711Encoder.kt`

**Responsibilities**:
- Convert 16-bit PCM samples to 8-bit G.711 μ-law or A-law
- Stateless, no buffering needed

### 3. BackchannelManager

Coordinates audio capture, encoding, and sending.

**Location**: `app/src/main/java/com/vladpen/BackchannelManager.kt`

**Responsibilities**:
- Manage AudioRecord lifecycle (8000Hz, MONO, 16-bit PCM)
- Read 20ms frames (160 samples = 320 bytes PCM → 160 bytes G.711)
- Encode and pass to BackchannelClient for RTP packetization and sending
- Run on a background thread
- Expose start/stop API for the UI

### 4. UI Integration

**Floating microphone button** positioned top-right of the stream view.

- **Default state**: Visible with `ic_mic_off` icon (mic with line through it), disabled/inactive
- **On click (first time)**: Request RECORD_AUDIO permission, then start audio
- **On click (active)**: Toggle — switches between `ic_mic_off` (inactive) and `ic_mic` (active)
- **When active**: Icon changes to `ic_mic` (no line), backchannel audio streaming
- **When inactive**: Icon changes to `ic_mic_off` (line through), audio stops
- **On app defocus** (`onPause`): Automatically stop audio and reset to inactive state
- Button hidden entirely if camera doesn't support backchannel

## Protocol Flow

```
Client                                    Camera (prudynt-t)
  │                                           │
  │──DESCRIBE (+ Require: backchannel)───────▶│
  │◀─────────────────────── 401 Unauthorized──│
  │──DESCRIBE (+ Digest Auth)────────────────▶│
  │◀─────────────────────── 200 OK + SDP──────│
  │                                           │
  │  (Parse SDP: find track4 PCMU sendonly)    │
  │                                           │
  │──SETUP track4 (TCP interleaved 0-1)──────▶│
  │◀─────────────────────── 200 OK + Session──│
  │                                           │
  │──PLAY (+ Session)────────────────────────▶│
  │◀─────────────────────── 200 OK────────────│
  │                                           │
  │══RTP PCMU packets ($0 + len + rtp)═══════▶│ (audio plays on speaker)
  │══RTP PCMU packets═══════════════════════▶│
  │  ...                                      │
  │                                           │
  │──TEARDOWN───────────────────────────────▶│
  │◀─────────────────────── 200 OK────────────│
```

## RTP Packet Format

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT=0   |       Sequence Number         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                              SSRC                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    G.711 μ-law payload (160 bytes)            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- PT=0 for PCMU, PT=8 for PCMA
- Timestamp increments by 160 per packet (20ms at 8kHz)
- Total RTP packet: 12 + 160 = 172 bytes
- TCP interleaved frame: 4 + 172 = 176 bytes per 20ms

## TCP Interleaved Framing

```
$<channel:1byte><length:2bytes big-endian><rtp_packet>
```

Channel 0 = RTP data (as negotiated in SETUP interleaved=0-1)

## SDP Parsing

Look for media lines with `a=sendonly`:
```
m=audio 0 RTP/AVP 0        ← payload type 0 = PCMU
a=rtpmap:0 PCMU/8000/1
a=control:track4
a=sendonly                  ← this marks it as backchannel
```

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Camera doesn't support backchannel | Hide mic button (no sendonly track in SDP) |
| RTSP auth fails | Log error, hide mic button |
| Connection drops during talk | Stop audio capture, show brief toast |
| Permission denied | Show snackbar explaining requirement |
| Camera speaker disabled | No sendonly tracks appear (already handled) |

## Threading Model

```
Main Thread:  UI button events, permission requests
IO Thread:    RTSP signaling (socket connect, DESCRIBE, SETUP, PLAY)
Audio Thread: AudioRecord.read() loop → encode → send RTP
```

The AudioRecord callback runs on its own thread. Encoding (G.711 lookup) is trivial and can happen inline. Socket.write() for the interleaved frame is the only blocking call.

## Dependencies

- No new external libraries required
- Uses Android SDK: `AudioRecord`, `Manifest.permission.RECORD_AUDIO`
- Reuses existing: RTSP URL parsing, credential storage from `StreamDataModel`

## Testing Strategy

### Unit Tests

Location: `app/src/test/java/com/vladpen/` (same as existing tests, runs in CI via `testDebugUnitTest`)

| Test Class | Coverage |
|-----------|----------|
| `G711EncoderTest` | All μ-law/A-law encoding: zero, max positive, max negative, known reference values, symmetry |
| `BackchannelClientTest` | SDP parsing (find sendonly tracks, extract codec/control), Digest auth computation, RTP packet construction, TCP interleaved framing, RTSP message formatting |
| `BackchannelManagerTest` | State machine (idle→connecting→active→stopping→idle), start/stop lifecycle, auto-stop on repeated start, error state transitions |
| `RtpPacketTest` | Header construction, sequence number wrap-around, timestamp increment, SSRC, payload type mapping |

### Integration Tests

Location: `app/src/androidTest/java/com/vladpen/cams/`

| Test | What it verifies |
|------|-----------------|
| `BackchannelIntegrationTest` | Full RTSP handshake against a mock RTSP server (loopback), audio data arrives correctly encoded |

### Test Execution

- Unit tests run at each development stage before proceeding to next task
- Unit tests run in CI on every push/PR (existing `test` job in GitHub Actions)
- Integration tests run locally with `./gradlew connectedDebugAndroidTest`
