# Tasks: RTSP Backchannel Audio

## Implementation Plan

### Phase 1: Core Protocol

- [ ] **Task 1: G711Encoder** — Create `G711Encoder.kt` with `linearToUlaw(sample: Short): Byte` and `linearToAlaw(sample: Short): Byte` using standard lookup tables. Pure utility class, no state.

- [ ] **Task 2: BackchannelClient** — Create `BackchannelClient.kt` that handles RTSP signaling:
  - TCP socket connection to camera RTSP port
  - Digest authentication (parse WWW-Authenticate, compute MD5 response)
  - DESCRIBE with `Require: www.onvif.org/ver20/backchannel` header
  - SDP parsing to find sendonly audio tracks (extract codec, payload type, control URL)
  - SETUP with TCP interleaved transport
  - PLAY to start session
  - `sendAudio(data: ByteArray)` — wraps in RTP header + TCP interleaved frame and sends
  - TEARDOWN to end session
  - Expose `supportsBackchannel(): Boolean` after DESCRIBE

- [ ] **Task 3: BackchannelManager** — Create `BackchannelManager.kt` that coordinates:
  - Initializes AudioRecord (8000Hz, MONO, 16-bit PCM, 160-sample buffer)
  - Background thread: read 20ms frames → G711 encode → BackchannelClient.sendAudio()
  - `start(rtspUrl: String, username: String, password: String)` — connects and begins capture
  - `stop()` — stops capture, sends TEARDOWN, closes socket
  - `detectBackchannel(rtspUrl: String, username: String, password: String): Boolean` — probe only

### Phase 2: UI Integration

- [ ] **Task 4: Microphone button layout** — Add a mic button (ImageButton with `ic_mic` icon) to the StreamsActivity layout, positioned near the existing mute button. Initially hidden (`View.GONE`).

- [ ] **Task 5: Backchannel detection on stream connect** — When StreamsActivity opens a stream, call `BackchannelManager.detectBackchannel()` on an IO coroutine. If supported, show the mic button.

- [ ] **Task 6: Push-to-talk interaction** — Wire the mic button with `OnTouchListener`:
  - ACTION_DOWN: request RECORD_AUDIO permission if needed, then call `BackchannelManager.start()`
  - ACTION_UP / ACTION_CANCEL: call `BackchannelManager.stop()`
  - Visual feedback: change button tint/background while active

- [ ] **Task 7: Permission handling** — Use `ActivityResultContracts.RequestPermission` for RECORD_AUDIO. On denial, show Snackbar. On grant, proceed with start.

### Phase 3: Polish & Testing

- [ ] **Task 8: Error handling** — Handle connection failures, socket timeouts, and mid-session disconnects gracefully. Show toast on failure, reset button state.

- [ ] **Task 9: Test with camera2** — End-to-end test: press mic button in app, verify audio plays from camera2 speaker. Verify button hides for cameras without backchannel.

- [ ] **Task 10: Add RECORD_AUDIO permission to manifest** — Add `<uses-permission android:name="android.permission.RECORD_AUDIO"/>` to AndroidManifest.xml. Add `<uses-feature android:name="android.hardware.microphone" android:required="false"/>`.
