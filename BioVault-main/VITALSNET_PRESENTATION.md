# VitalsNet — Proof of Human Capture

---

## Slide 1: Title

**VitalsNet**
*Proof of Human Capture*

A multi-layered mobile authenticity engine that cryptographically proves media was captured by a real human, on a real device, in real time.

**Team: Syntax Error**

| Member | Role |
|--------|------|
| | |
| | |
| | |
| | |

---

## Slide 2: Problem Statement

**The world can no longer trust what it sees.**

Deepfake fraud cost businesses over **$26 billion** in 2024 (Deloitte). AI-generated media is now indistinguishable from reality — and the consequences are already here.

### Real-World Scenarios

**1. Insurance Fraud**
A claimant submits an AI-generated video of themselves describing a fabricated car accident. The insurer has no way to verify if the person was physically present or if the footage is synthetic. Fraudulent payouts cost the insurance industry billions annually.

**2. Courtroom Evidence Tampering**
A photograph is submitted as criminal evidence. The defense argues it was AI-generated or transferred from another device to frame their client. Courts currently lack a technical mechanism to prove which physical camera captured an image.

**3. Non-Consensual Intimate Media**
A person is secretly recorded in a hotel room or private space. The victim discovers the footage months later — but cannot prove they never consented to being recorded, and the perpetrator claims otherwise.

**4. Hidden Camera Abuse in Hotels & Rentals**
Guests in Airbnbs or hotel rooms have no way to detect or prove unauthorized recording. Perpetrators operate undetected, and victims have no cryptographic trail to pursue legal action.

**5. Viral Misinformation**
A real photo is cropped, recompressed, and reshared on social media with fabricated context — falsely claiming it shows a political figure in a compromising situation. After thousands of reshares, the original context is lost.

**6. AI-Generated Profile Fraud**
A scammer uses an AI-generated face on a dating app, builds a relationship, then demands monetary damages claiming someone "leaked their photo." The accused has no tool to prove the image was never captured by a real camera.

**7. Workplace Harassment Documentation**
An employee captures video evidence of physical intimidation at work. The employer's legal team claims the video was staged or digitally altered after the fact.

**The core failure:** Current solutions try to *detect* fakes after the fact — an arms race that generators are winning. There is no widely available system to **prove originals** at the moment of capture.

---

## Slide 3: Solution Overview — VitalsNet

VitalsNet does not detect fakes. **It proves originals.**

Every capture produces a **Reality Score (0–100)** built from six independent, tamper-evident layers. Each real-world problem maps to a specific defense:

| Problem | VitalsNet Defense | How |
|---------|-------------------|-----|
| **Insurance deepfake fraud** | Biological liveness proof | TS-CAN neural rPPG extracts a live heartbeat (78 BPM, 94% confidence) directly from the claimant's face. No generative AI produces a photoplethysmographic signal. |
| **Courtroom evidence tampering** | Device DNA binding | PRNU camera fingerprinting mathematically proves which specific physical device captured the image — unforgeable by AI generation or file transfer. |
| **Non-consensual intimate recording** | BLE consent protocol | Bluetooth broadcast notifies every nearby device before capture. Each person's approval is recorded with a 64-byte Ed25519 cryptographic signature — non-repudiable consent proof. |
| **Hidden cameras in hotels** | Content classification + consent | Dual-engine ML (HSV skin analysis + Google ML Kit) detects SENSITIVE/EXPLICIT content and mandates consent. Without cryptographic approval from subjects, the capture is flagged. |
| **Viral misinformation (reshared media)** | Invisible watermark | DWT+DCT+SVD watermark embeds provenance (timestamp, device fingerprint, BPM) directly into the pixels. Survives cropping, compression, and resharing — anyone can extract the original metadata. |
| **AI-generated profile fraud** | Reality Score verification | AI-generated images score 0/100: no heartbeat, no device DNA, no hardware signature, no watermark. Fraudulent claims are debunked instantly. |
| **Workplace evidence denied** | Hardware-bound signature | StrongBox HSM signs every proof with ECDSA P-256 inside tamper-resistant hardware. The key never leaves the chip — proving the exact device, biometric owner, and that nothing was altered post-capture. |

### Reality Score Composition

| Signal | Weight | What It Proves |
|--------|--------|----------------|
| Device DNA (PRNU) | 30% | This specific camera sensor captured the image |
| Heartbeat (rPPG) | 30% | A living human was present in the frame |
| BLE Consent | 20% | Bystanders gave cryptographic consent |
| Invisible Watermark | 10% | Provenance metadata is embedded in the pixels |
| Hardware Signature | 10% | A tamper-resistant chip signed the proof |

---

## Slide 4: Technical Approach

### Six Independent Security Layers

**Layer 1 — Remote Photoplethysmography (rPPG): Biological Liveness**
- TS-CAN (Temporal Shift Convolutional Attention Network) running on PyTorch Mobile 1.13
- Dual-branch input: temporal difference frames + standardized raw frames at 72×72 resolution
- 180-sample BVP accumulation → Hann-windowed FFT → peak detection in 0.7–3.0 Hz (42–180 BPM)
- Parabolic interpolation for sub-bin frequency accuracy; median smoothing across 5 estimates
- Forehead ROI (top 20%, centered 60% width) — optimal for blood volume pulse

**Layer 2 — PRNU Device DNA: Hardware Fingerprinting**
- 50+ calibration frames → mean subtraction → noise averaging → Wiener filter (Gaussian 3×3, σ=0.5)
- BLAKE3 hash of full PRNU pattern → 64-character hex Device DNA
- Verification via normalized cross-correlation (`cv::matchTemplate`, `TM_CCOEFF_NORMED`)
- Binds every capture to a unique physical camera sensor — unforgeable by software

**Layer 3 — Invisible Watermark: Provenance in Pixels**
- Transform chain: RGBA → YCrCb (Y channel) → 1-level Haar DWT → 8×8 block DCT-II → SVD
- Quantization Index Modulation on largest singular value σ₀ with α=100
- Payload: JSON `{"id", "dna", "ts", "bpm"}` + 16-bit length header + 8-bit XOR checksum
- Extraction: same transform chain in reverse → bit recovery via σ₀ mod 100
- Survives JPEG compression, cropping, screenshotting, social media re-encoding

**Layer 4 — Hardware Signature: Tamper-Resistant Signing**
- EC P-256 key pair generated inside StrongBox HSM or TEE (Android Keystore)
- Every signature requires biometric authentication (fingerprint/face)
- Signs the 32-byte BLAKE3 anchor hash using `NONEwithECDSA` (no double-hashing)
- Private key physically never leaves the secure chip

**Layer 5 — BLE Consent Protocol: Cryptographic Consent**
- BLE 5.0 advertisement-based: `R|<session>|<S/E>` broadcast → `A|<session>|<Y/N>` response
- 20-second collection window, 60-second dedup
- Each approval carries a 64-byte Ed25519 signature + 32-byte public key
- All approvals merged into a BLAKE3 consensus hash

**Layer 6 — Content Classification: Automated Safety**
- Engine A: HSV skin-pixel analysis (3 ranges for diverse skin tones, 120×90 downscale)
- Engine B: Google ML Kit 17.0.9 (on-device, no cloud) — skin, underwear, and violence label sets
- Three tiers: SAFE / SENSITIVE (≥30% skin) / EXPLICIT (≥45% skin or ML+skin threshold)

### Cryptographic Foundation

| Algorithm | Purpose | Library |
|-----------|---------|---------|
| BLAKE3 (256-bit) | Device DNA, anchor hash, consensus hash | blake3 1.5.0 |
| ECDSA P-256 | Hardware-bound proof signing | Android Keystore (StrongBox/TEE) |
| Ed25519 | BLE consent signatures | libsodium |
| SHA-256 | Content hash, capture ID | libsodium |

---

## Slide 5: Development Process

### Architecture: Three-Layer Native Stack

```
┌──────────────────────────────────────────────┐
│        React Native 0.73 (Hermes Engine)     │
│   Login → Calibration → Camera → Results     │
│            → Verification → Home             │
├──────────────────────────────────────────────┤
│        Android SDK Layer (Kotlin + Java)      │
│  StrongBoxManager │ TSCANInference           │
│  ContentClassifier │ ConsentBroadcaster      │
│  CaptureProof │ BioVaultSDK (Facade)         │
├──────────────────────────────────────────────┤
│          Native C++17 (via JNI/NDK)          │
│  OpenCV 4.10 │ BLAKE3 │ libsodium           │
│  PyTorch Mobile │ PRNU │ Watermark           │
│  rPPG Engine │ Crypto Utils                  │
└──────────────────────────────────────────────┘
```

### Build Toolchain

| Component | Version |
|-----------|---------|
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 |
| NDK | 26.1.10909125 |
| Kotlin | 1.9.22 |
| Java | 17 |
| compileSdk / targetSdk | 36 / 34 |
| minSdk | 26 (Android 8.0+) |

### Development Phases

**Phase 0 — Foundation**
BPM stub integration and native module wiring. Connected React Native ↔ Java ↔ C++ pipeline through JNI. Established the Hermes-bundled build system.

**Phase 1 — PRNU Device DNA**
Built calibration screen for 50-frame PRNU extraction. Implemented Wiener-filtered noise averaging in C++. BLAKE3 fingerprint generation and cross-correlation verification.

**Phase 2 — DWT+DCT+SVD Watermark**
Implemented the full Haar DWT → block DCT-II → SVD embedding pipeline in C++. Built QIM encoding with α=100 for robust bit embedding. Added extraction and checksum validation.

**Phase 3 — Content Classification**
Dual-engine approach: HSV skin-pixel analysis (computer vision) combined with Google ML Kit neural labeling. Three classification tiers with configurable thresholds for diverse skin tones.

**Phase 4 — BLE Consent Flow**
Advertisement-only BLE protocol — no pairing required. Unified scan handles both inbound requests and approvals. Ed25519 multi-party signatures with dedup and timeout logic.

**Phase 5 — SDK Packaging**
Extracted core functionality into `biovault-sdk` Android library module. Created `BioVaultSDK.java` facade with clean public API for third-party integration.

**Phase 6 — Risk Score & Polish**
Built `CaptureProof.java` with weighted 5-component Reality Score. Results screen with circular gauge, score breakdown bars, content hash, watermark status, and full proof display. Verification screen for on-device proof re-verification.

### Key Engineering Decisions

- **Fully offline** — zero cloud dependency. All ML inference, cryptography, and consent happen on-device.
- **C++17 for performance-critical paths** — PRNU, watermarking, rPPG, and crypto run at native speed via JNI.
- **StrongBox-first with TEE fallback** — hardware security adapts to device capability without user friction.
- **Advertisement-based BLE** — no pairing handshake means consent works between strangers instantly.

---

## Slide 6: Market Potential & Business Model

### Total Addressable Market

The digital trust and content authentication market is projected to reach **$15.8 billion by 2028** (MarketsandMarkets). VitalsNet addresses three distinct market segments:

### B2B Platform Integration (SDK Licensing)

| Vertical | Use Case | Revenue Model |
|----------|----------|---------------|
| **Social Media (Instagram, TikTok, X)** | "Verified Capture" badge on uploads; EU AI Act compliance for synthetic media labeling | Per-API-call or monthly SDK license |
| **Insurance Platforms** | Claim video verification; remote inspection authenticity | Per-verification fee; enterprise license |
| **Legal Tech / eDiscovery** | Evidence chain-of-custody; courtroom-admissible proof bundles | Enterprise annual license |
| **Telemedicine / Healthcare** | Patient identity verification; remote vital sign monitoring via rPPG | Per-session or platform integration fee |
| **Dating Apps (Tinder, Bumble)** | Profile photo verification; combat AI-generated catfishing | SDK integration license |
| **Financial Services (KYC)** | Video identity verification for remote onboarding | Per-verification transaction fee |
| **News & Journalism** | Source media provenance; photojournalism integrity | Platform license |

### B2B Hospitality & Safety (Consent Infrastructure)

| Vertical | Use Case | Revenue Model |
|----------|----------|---------------|
| **Hotels & Airbnb** | Hidden camera detection ecosystem; guest privacy certification | Property certification fee; per-room monthly |
| **Coworking Spaces** | Meeting room recording consent; GDPR compliance | Facility license |
| **Event Venues** | Concert/event photography consent management | Per-event or annual license |
| **Schools & Campuses** | Student media consent; anti-bullying documentation | Institutional license |

The BLE consent protocol turns VitalsNet into a **privacy infrastructure layer** for any physical space where recording occurs. Hotels and Airbnbs can certify that their properties are VitalsNet-compliant — meaning any unauthorized recording would be instantly flagged, and guests have cryptographic proof of privacy.

### B2C Consumer App (Freemium)

| Tier | Features | Price |
|------|----------|-------|
| **Free** | Basic capture with Reality Score, content classification, local storage | $0 |
| **Pro** | Full PRNU calibration, watermark embedding, proof export, StrongBox signing | $4.99/month |
| **Verified** | Cross-device verification API, legal proof bundle export, priority support | $9.99/month |

### Regulatory Tailwinds

| Regulation | Market Impact |
|------------|---------------|
| **EU AI Act (2024)** | Mandates labeling of AI-generated content — platforms need verification tools |
| **US DEEPFAKES Accountability Act** | Synthetic media disclosure requirements create demand for the inverse proof |
| **GDPR (Consent)** | Cryptographic consent proof directly addresses compliance requirements |
| **FRE 901(a) (US Evidence Rules)** | Digital evidence authentication needs drive legal-tech adoption |

---

## Slide 7: Challenges & Learnings

### Technical Challenges

**1. JNI Bridge Complexity**
Moving between React Native → Java/Kotlin → C++17 via JNI requires exact function signature matching. When we refactored `StrongBoxManager` into the SDK package (`com.biovault.sdk`), the JNI function names in C++ still pointed to the old package (`com.biovault`) — causing `UnsatisfiedLinkError` crashes on physical devices that never appeared in builds.

*Learning:* JNI function naming is a manual contract. Any Java package refactor requires updating every corresponding `Java_com_package_Class_method` signature in C++.

**2. rPPG Signal Noise on Mobile**
Camera auto-exposure, auto-white-balance, and compression artifacts introduce noise that drowns out the subtle skin color fluctuations (< 1% intensity variation) used for heartbeat extraction. Early prototypes produced wildly unstable BPM readings.

*Learning:* The TS-CAN dual-branch architecture (difference-normalized + standardized) was critical — it separates motion artifacts from blood volume pulse. Forehead ROI selection and Hamming windowing before FFT were necessary for reliable readings.

**3. Watermark Survival vs. Invisibility Trade-off**
Aggressive embedding (high α) survives compression but introduces visible artifacts. Conservative embedding (low α) is invisible but destroyed by JPEG re-encoding.

*Learning:* α=100 with QIM on the largest SVD singular value in the DWT-LL band hits the sweet spot — the low-frequency sub-band is more robust to compression, while SVD distributes the modification across the entire 8×8 block, minimizing perceptual impact.

**4. BLE Without Pairing**
Traditional BLE workflows require device discovery → pairing → GATT connection. This is impractical for consent between strangers. A hotel guest should not need to pair with every other guest's phone.

*Learning:* Advertisement-only protocol with session IDs in the payload eliminates pairing entirely. The 20-second timeout and 60-second dedup window prevent spam while maintaining responsiveness.

**5. StrongBox Availability**
Not all Android devices have StrongBox HSM. Our primary test device (OnePlus) lacks it entirely — falling back silently to TEE without any user-visible indication.

*Learning:* Design for graceful degradation. StrongBox is preferred but TEE provides equivalent cryptographic guarantees for proof signing. The Reality Score weights hardware signature at 10% precisely because availability varies across devices.

### Process Learnings

- **Bundle before build:** React Native's Gradle integration does not auto-regenerate the JS bundle for debug builds. Stale bundles from weeks ago silently ship — causing hours of debugging "why my changes aren't showing."
- **Physical device testing is non-negotiable:** BLE, camera PRNU, and StrongBox behave completely differently on a real phone vs. an emulator. Every feature was validated on a physical OnePlus device via ADB.
- **On-device ML has hard constraints:** PyTorch Mobile models must be quantized and optimized for ARM. ML Kit's bundled model is good enough for content classification but not purpose-built for content moderation — HSV skin analysis carries the primary signal.

---

## Slide 8: Future Scope

### Near-Term (3–6 Months)

**iOS Port**
Replicate the full pipeline on iOS using Secure Enclave (Apple's equivalent of StrongBox), CoreML (replacing PyTorch Mobile), and CoreBluetooth. The SDK architecture is designed for this — the facade API is platform-agnostic.

**Cloud Verification API**
A public REST endpoint where anyone can submit an image and receive a verification report — Reality Score, watermark extraction, Device DNA match status. Enables verification without the VitalsNet app installed.

**Video Watermarking**
Extend the current single-frame DWT+DCT+SVD pipeline to per-frame watermarking for video. Each frame carries its own provenance payload, creating a continuous chain of authenticity.

### Medium-Term (6–12 Months)

**Platform SDK Distribution**
Publish the Android SDK to Maven Central / Google's Maven repository for one-line integration by third-party apps. Provide reference implementations for Instagram-style "Verified Capture" badges.

**Multi-Device Consensus**
Extend the BLE consent protocol to support multi-device capture verification — multiple phones filming the same event can cross-reference their proofs, creating a mesh of corroborating evidence.

**Federated PRNU Database**
A privacy-preserving lookup service where devices voluntarily register their PRNU fingerprints. Enables cross-device verification: "Was this image captured by a registered, known device?" — without revealing device identity.

### Long-Term (12+ Months)

**On-Device Deepfake Detection Integration**
Combine VitalsNet's "prove the original" approach with lightweight on-device deepfake detection models. Incoming media without a VitalsNet proof gets flagged for secondary analysis, while VitalsNet-verified media is trusted.

**Hardware OEM Partnerships**
Work with smartphone manufacturers (Samsung, Google Pixel, OnePlus) to embed VitalsNet's PRNU calibration and StrongBox signing into the factory camera pipeline — every photo taken on the device is automatically authenticated.

**Regulatory Certification**
Pursue eIDAS 2.0 qualified electronic signature certification for the StrongBox signing mechanism, making VitalsNet proofs legally equivalent to handwritten signatures in the EU.

---

## Slide 9: Live Demo Flow

### What Happens On-Screen

**Step 1 — Calibration (First Launch Only)**
User captures 50 flat-field frames → C++ extracts PRNU pattern → Wiener filter → BLAKE3 fingerprint → Device DNA stored locally.
*On screen: Progress bar filling as frames are captured, final "Device DNA Registered" confirmation.*

**Step 2 — Capture**
User opens camera → face detection activates → TS-CAN processes frames → real-time BPM display on screen → content classification runs simultaneously → if SENSITIVE/EXPLICIT, BLE consent broadcast fires.
*On screen: Live BPM counter, face frame overlay, recording timer (max 30s).*

**Step 3 — Proof Generation**
Recording stops → watermark embedded in last frame → anchor hash computed → StrongBox/TEE signs with biometric → Reality Score calculated.
*On screen: Brief processing animation, then Results dashboard.*

**Step 4 — Results Dashboard**
- Reality Score gauge (0–100) with VERIFIED / MEDIUM / LOW / UNVERIFIED label
- Score breakdown: Device DNA 30% + Heartbeat 30% + Consent 20% + Watermark 10% + Signature 10%
- Capture ID, content hash (SHA-256), device fingerprint, BPM statistics
- Invisible watermark status, content classification, BLE consent details

**Step 5 — Verification**
Any user can load a watermarked image → VitalsNet extracts the embedded payload → recomputes the anchor hash → displays original capture metadata (BPM, timestamp, device, capture ID).
*On screen: Verification result with extracted watermark data.*

---

## Slide 10: Why VitalsNet Wins

### Five Unforgeable Pillars

**1. Biology cannot be faked.**
No generative AI produces a photoplethysmographic signal in a rendered face. rPPG extraction is a fundamental barrier that deepfake generators cannot cross without solving an unsolved problem in computational biology.

**2. Physics cannot be copied.**
PRNU patterns are semiconductor manufacturing imperfections at the atomic scale. They cannot be replicated, transferred, or simulated. Every camera ever made has a unique one.

**3. Hardware cannot be extracted.**
StrongBox HSM keys are generated and used inside tamper-resistant silicon. The private key physically never exists outside the secure chip — not in RAM, not on disk, not in a backup.

**4. Math cannot be broken.**
BLAKE3, Ed25519, and ECDSA P-256 rely on well-studied computational hardness assumptions. Breaking any one of them would break the global financial system first.

**5. No single point of failure.**
Six independent layers mean an attacker must defeat biology, physics, hardware, and mathematics simultaneously. The cost of attack exceeds the value of any forgery.

### Comparison With Existing Solutions

| Capability | C2PA | Truepic | VitalsNet |
|------------|------|---------|-----------|
| Biological liveness proof | No | No | Yes |
| Physical sensor binding (PRNU) | No | No | Yes |
| Invisible watermark | No | No | Yes |
| Bystander consent protocol | No | No | Yes |
| Content classification | No | No | Yes |
| Hardware-bound signature | Certificate | Certificate | StrongBox HSM |
| Works fully offline | No | No | Yes |

---

**VitalsNet — We are not fighting AI. We are proving reality.**
