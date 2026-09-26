# Tasks: RTSP Backchannel Audio

## Implementation Plan

### Phase 1: Core Protocol + Unit Tests

- [ ] **Task 1: G711Encoder + tests** — Create `G711Encoder.kt` with `linearToUlaw(sample: Short): Byte` and `linearToAlaw(sample: Short): Byte`. Create `G711EncoderTest.kt` with tests for: zero input, max positive, max negative, known reference values, full range symmetry. Run tests.

- [ ] **Task 2: RTP packet builder + tests** — Create RTP packet construction in `BackchannelClient.kt` (or helper). Create `RtpPacketTest.kt` testing: header byte layout, sequence number increment and wrap at 65535, timestamp increment by 160, SSRC field, payload type 0 (PCMU) and 8 (PCMA), TCP interleaved framing (`$` + channel + big-endian length). Run tests.

- [ ] **Task 3: SDP parsing + tests** — Implement SDP parsing in `BackchannelClient.kt` to extract sendonly audio tracks (codec, payload type, sample rate, control URL). Create `BackchannelClientTest.kt` with tests for: SDP with PCMU backchannel, SDP with multiple backchannel codecs (prefer PCMU), SDP with no backchannel, SDP with only video, malformed SDP. Run tests.

- [ ] **Task 4: RTSP signaling + Digest auth + tests** — Implement DESCRIBE/SETUP/PLAY/TEARDOWN message formatting and Digest authentication in `BackchannelClient.kt`. Add tests to `BackchannelClientTest.kt` for: RTSP message formatting, Digest auth MD5 computation with known vectors, nonce/realm parsing from 401 response. Run tests.

- [ ] **Task 5: BackchannelManager + tests** — Create `BackchannelManager.kt` coordinating AudioRecord → encode → send. Create `BackchannelManagerTest.kt` testing state machine: idle→connecting→active→idle, start when already active (no-op), stop when idle (no-op), error transitions. Run tests.

### Phase 2: UI Integration

- [ ] **Task 6: Add RECORD_AUDIO permission to manifest** — Add `<uses-permission android:name="android.permission.RECORD_AUDIO"/>` and `<uses-feature android:name="android.hardware.microphone" android:required="false"/>` to AndroidManifest.xml.

- [ ] **Task 7: Mic button layout** — Add a floating ImageButton (top-right) to the StreamsActivity layout with `ic_mic_off` drawable. Initially `View.GONE`. Style: semi-transparent background, 48dp touch target.

- [ ] **Task 8: Backchannel detection on stream connect** — When StreamsActivity opens, call `BackchannelManager.detectBackchannel()` on IO coroutine. If supported, set mic button to `View.VISIBLE`.

- [ ] **Task 9: Toggle interaction + permission** — Wire mic button `OnClickListener`:
  - If not active: request RECORD_AUDIO permission (if needed), then start backchannel, change icon to `ic_mic`
  - If active: stop backchannel, change icon to `ic_mic_off`
  - On `onPause()`: if active, stop and reset icon

- [ ] **Task 10: Permission handling** — Use `ActivityResultContracts.RequestPermission`. On denial show Snackbar. On grant proceed with start.

### Phase 3: Error Handling + Integration Test

- [ ] **Task 11: Error handling** — Handle connection failures, socket timeouts, mid-session disconnects. Show toast on failure, reset button to `ic_mic_off` state.

- [ ] **Task 12: Integration test** — Create `BackchannelIntegrationTest.kt` in `app/src/androidTest/`. Test full RTSP handshake against a mock RTSP server on loopback. Verify RTP packets arrive with correct encoding.

- [ ] **Task 13: End-to-end test with camera2** — Manual test: tap mic button, verify audio plays from camera2 speaker. Verify button hides for cameras without backchannel. Verify auto-stop on app background.
