# Requirements: RTSP Backchannel Audio (Talk-to-Camera)

## Overview

Enable users to speak through their Android device's microphone and have the audio played through the camera's speaker in real-time, using the RTSP backchannel protocol (ONVIF Profile T).

## User Stories

### US-1: Talk to Camera
**As a** user viewing a camera stream,
**I want to** press and hold a microphone button to speak through the camera's speaker,
**So that** I can communicate with people near the camera in real-time.

#### Acceptance Criteria
- WHEN the user presses and holds the microphone button THE SYSTEM SHALL capture audio from the device microphone and send it to the camera speaker via RTSP backchannel
- WHEN the user releases the microphone button THE SYSTEM SHALL stop capturing and sending audio
- WHEN audio is being sent THE SYSTEM SHALL display a visual indicator (button highlight/animation)
- WHEN the camera does not support backchannel THE SYSTEM SHALL hide the microphone button

### US-2: Backchannel Detection
**As a** user,
**I want** the app to automatically detect if my camera supports two-way audio,
**So that** the talk button only appears when the feature is available.

#### Acceptance Criteria
- WHEN connecting to a camera THE SYSTEM SHALL send an RTSP DESCRIBE with `Require: www.onvif.org/ver20/backchannel` header
- WHEN the SDP response contains a `a=sendonly` audio track THE SYSTEM SHALL show the microphone button
- WHEN the SDP response does not contain a sendonly track THE SYSTEM SHALL hide the microphone button

### US-3: Audio Codec Selection
**As a** user,
**I want** the app to automatically select the best supported codec,
**So that** audio works reliably without manual configuration.

#### Acceptance Criteria
- WHEN the camera advertises PCMU (G.711 μ-law) backchannel THE SYSTEM SHALL use PCMU encoding at 8000Hz mono
- WHEN the camera advertises PCMA (G.711 A-law) backchannel THE SYSTEM SHALL use PCMA encoding at 8000Hz mono
- WHEN the camera advertises AAC backchannel THE SYSTEM SHALL use AAC encoding at the advertised sample rate

### US-4: Permission Handling
**As a** user,
**I want** to be prompted for microphone permission only when I try to talk,
**So that** the app doesn't request unnecessary permissions upfront.

#### Acceptance Criteria
- WHEN the user taps the microphone button for the first time THE SYSTEM SHALL request RECORD_AUDIO permission
- WHEN permission is denied THE SYSTEM SHALL show a brief message explaining the requirement
- WHEN permission is granted THE SYSTEM SHALL immediately begin audio capture

## Non-Functional Requirements

- **Latency**: Audio should reach the camera speaker within 500ms of being spoken
- **Reliability**: Network interruptions should not crash the app; the session should recover gracefully
- **Battery**: Audio capture and encoding should use minimal CPU (G.711 is a simple lookup table)
- **Compatibility**: Must work with Thingino cameras running prudynt-t with `spk_enabled: true`

## Out of Scope

- Full-duplex audio (simultaneous listen and talk) — future enhancement
- Audio from camera speaker back to phone — already handled by VLC stream
- Volume control for backchannel audio
- Echo cancellation
