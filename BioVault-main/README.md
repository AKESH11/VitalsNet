# VitalsNet

Minimal, production-oriented mobile stack for proof-of-capture and authenticity signals.

VitalsNet combines biometric liveness, device fingerprinting, watermarking, consent-aware capture flow, and privacy alerts in a single Android-first React Native app.

## What this project does

- Captures media with capture-time authenticity signals.
- Extracts heartbeat and liveness features (rPPG/TS-CAN path).
- Generates device-linked fingerprint signals (PRNU/hardware DNA path).
- Embeds and verifies watermark metadata for media integrity flow.
- Computes a Reality Score from multiple weighted signals.
- Supports BLE consent workflow for sensitive captures.
- Supports Privacy Shield mode to detect/report unconsented nearby captures.
- Syncs capture records and privacy alerts through Firestore REST APIs.

## Core capabilities

### 1) Capture and scoring
- Camera session + native processing pipeline.
- Weighted Reality Score composition (heartbeat, device, consent, watermark, etc.).
- Result screen with score breakdown and evidence fields.

### 2) Verify flow
- Verify media/capture IDs across devices.
- Watermark extraction path with user-facing verification output.

### 3) Privacy Shield
- Broadcasts short VitalsID over BLE via foreground service.
- Nearby capture-side scan to detect unconsented subjects.
- Uploads violations to Firestore and renders alert timeline on device.

## High-level architecture

- React Native UI and navigation: screens, app state, user flows.
- Android native bridge (`BioVaultModule`) for camera/SDK/BLE/service operations.
- Native SDK layer (`biovault-sdk`) for capture, scoring, inference, and consent broadcaster.
- C++ layer for performance-sensitive signal and crypto operations.
- Firebase REST service layer for capture and privacy alert persistence.

## Repository layout

```text
BioVault-main/
	mobile-app/
		src/
			screens/            # Login, Calibration, Home, Camera, Results, Verify, PrivacyShield
			components/         # Shared UI + error boundary
			services/           # Firebase REST integration
		android/
			app/
				src/main/java/com/biovault/   # RN bridge + foreground service
			biovault-sdk/
				src/main/java/com/biovault/sdk/  # capture/scoring/inference/consent
				src/main/cpp/                     # native cmake entry
		cpp/                    # Native C++ modules
	scripts/                  # Build/support scripts
	third_party/              # External native dependencies
```

## Quick start

### Prerequisites
- Node.js >= 18
- npm >= 9
- Android Studio + SDK/NDK configured
- ADB device/emulator available

### Install

```bash
npm install
npm run install:mobile
```

### Build native C++ (optional but recommended before Android run)

```bash
npm run build:cpp
```

### Run Android app

```bash
npm run mobile:android
```

## Screenshots

Home

![VitalsNet Home](./screen1.png)

Capture / App flow

![VitalsNet App Screen](./screen2.png)

## Notes

- The app currently targets Android-first workflow.
- Firebase is integrated via REST calls in service layer.
- BLE and foreground-service permissions are required for Privacy Shield features.

## License

MIT

