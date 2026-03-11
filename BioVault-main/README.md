# BioVault 2.0

**Unified Physiological-Cryptographic Infrastructure for IT Amendment Rules 2026 Compliance**

BioVault 2.0 is a Compliance-as-a-Service (CaaS) platform that combines real-time rPPG biometric extraction, DWT-SVD invisible watermarking, and forensic deepfake detection â€” built for India's IT Amendment Rules 2026.

[![Platform](https://img.shields.io/badge/platform-Android-green)]()
[![Backend](https://img.shields.io/badge/backend-FastAPI%20%2B%20C++-blue)]()
[![Blockchain](https://img.shields.io/badge/blockchain-Hyperledger%20Besu-orange)]()
[![License](https://img.shields.io/badge/license-MIT-blue)]()

## Architecture

### Lane A â€” Proactive (Capture-Time)
1. **rPPG Extraction** â€” POS algorithm extracts 128-bit Physiological Seed from facial video
2. **DWT-SVD Watermarking** â€” Embeds seed as invisible watermark that survives WhatsApp compression
3. **Blockchain Anchor** â€” Hyperledger Besu private L2 for tamper-proof timestamping

### Lane B â€” Reactive (Forensic Analysis)
1. **Pixel-Artifact Scan** â€” Detects GAN/diffusion artifacts and compression anomalies
2. **Geometric Consistency** â€” Validates facial geometry and lighting coherence
3. **Source Provenance** â€” PRNU camera sensor fingerprinting

### IT Rules 2026 Compliance
- 120-minute takedown SLA
- SGI (Synthetic/Generated/Inauthentic) labeling
- SIM-binding for creator identity
- DPDP Act data protection compliance

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Mobile | React Native 0.73 + C++ NDK |
| Vision | MediaPipe BlazeFace, TFLite |
| rPPG | POS Algorithm (C++) |
| Watermark | DWT-SVD (C++) |
| Forensics | PRNU + Pixel Analysis (C++/Python) |
| Backend | FastAPI (Python) |
| Blockchain | Hyperledger Besu (Private L2) |
| ZKP | Zero-Knowledge Humanity Tokens |
| Camera | OpenCV 4.10 Android SDK |

## Project Structure

```
BioVault-main/
â”œâ”€â”€ mobile-app/           # React Native + C++ native modules
â”‚   â”œâ”€â”€ android/          # Android build (AGP 8.7, Gradle 8.9)
â”‚   â”œâ”€â”€ cpp/              # C++ native: rPPG, PRNU, crypto
â”‚   â”‚   â”œâ”€â”€ include/      # Headers
â”‚   â”‚   â”œâ”€â”€ src/          # Implementation
â”‚   â”‚   â””â”€â”€ test/         # C++ unit tests
â”‚   â”œâ”€â”€ src/
â”‚   â”‚   â”œâ”€â”€ components/   # Camera view, error boundary
â”‚   â”‚   â”œâ”€â”€ config/       # App configuration
â”‚   â”‚   â”œâ”€â”€ screens/      # UI screens
â”‚   â”‚   â””â”€â”€ services/     # API + business logic
â”‚   â””â”€â”€ third-party/      # libsodium
â”œâ”€â”€ scripts/              # Build scripts (libsodium)
â”œâ”€â”€ third_party/          # OpenCV Android SDK
â””â”€â”€ package.json          # Workspace root
```

## Quick Start

```bash
# Install dependencies
cd mobile-app && npm install

# Build C++ native modules
npm run build:cpp

# Run on Android device
npm run android
```

## Red-Team Defenses
- 50 Hz light-noise cancellation (anti-screen replay)
- Synthetic pulse rejection (anti-deepfake video)
- ZKP humanity tokens (prove liveness without revealing biometrics)
- Multi-frame temporal consistency validation

## License

MIT

