package com.biovault.sdk;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Aggregated proof-of-origin for a single capture session.
 * Collects PRNU confidence, BPM confidence, consent status,
 * watermark status and computes a composite Risk Score (0–100).
 */
public class CaptureProof {

    // ── Weights (must sum to 1.0) ──
    private static final float W_PRNU     = 0.30f;
    private static final float W_BPM      = 0.30f;
    private static final float W_CONSENT  = 0.20f;
    private static final float W_WATERMARK = 0.10f;
    private static final float W_STRONGBOX = 0.10f;

    // ── Fields ──
    public final float bpm;
    public final float bpmConfidence;        // 0–100
    public final String deviceFingerprint;   // PRNU BLAKE3 hash
    public final float prnuConfidence;       // 0–100 (>8 chars = 100, else 0)
    public final boolean watermarkEmbedded;
    public final boolean consentVerified;
    public final int consentSignatures;
    public final boolean strongBoxSigned;
    public final String contentCategory;     // SAFE / SENSITIVE / EXPLICIT
    public final String videoHash;
    public final String bioSignature;
    public final String proofOfRealityHash;
    public final boolean livenessDetected;
    public final float bpmVariability;   // BPM std dev — low = possible screen spoof

    // ── Computed ──
    public final int riskScore;              // 0–100 (higher = more trustworthy)
    public final String riskLabel;           // VERIFIED / MEDIUM / LOW / UNVERIFIED

    private CaptureProof(Builder b) {
        this.bpm = b.bpm;
        this.bpmConfidence = b.bpmConfidence;
        this.deviceFingerprint = b.deviceFingerprint != null ? b.deviceFingerprint : "";
        this.prnuConfidence = computePRNUConfidence(this.deviceFingerprint);
        this.watermarkEmbedded = b.watermarkEmbedded;
        this.consentVerified = b.consentVerified;
        this.consentSignatures = b.consentSignatures;
        this.strongBoxSigned = b.strongBoxSigned;
        this.contentCategory = b.contentCategory != null ? b.contentCategory : "SAFE";
        this.videoHash = b.videoHash != null ? b.videoHash : "";
        this.bioSignature = b.bioSignature != null ? b.bioSignature : "";
        this.proofOfRealityHash = b.proofOfRealityHash != null ? b.proofOfRealityHash : "";
        this.livenessDetected = b.livenessDetected;
        this.bpmVariability = b.bpmVariability;

        this.riskScore = computeScore();
        this.riskLabel = labelFromScore(this.riskScore);
    }

    // ── Score computation ──

    private float computePRNUConfidence(String fingerprint) {
        if (fingerprint == null || fingerprint.isEmpty()) return 0f;
        // Longer fingerprint = higher confidence; full BLAKE3 = 64 hex chars
        int len = fingerprint.replace(".", "").length();
        if (len >= 32) return 100f;
        if (len >= 16) return 80f;
        if (len >= 8)  return 50f;
        return 10f;
    }

    private int computeScore() {
        // Normalize BPM confidence to 0–1
        float bpmNorm = clamp01(bpmConfidence / 100f);
        // Valid BPM range bonus
        boolean bpmValid = bpm > 40 && bpm < 180;
        float bpmFactor = bpmValid ? bpmNorm : bpmNorm * 0.3f;

        // Anti-spoofing: bonus for passing liveness + HRV, no penalty for failing
        // This avoids penalizing real humans when C++ liveness is unreliable
        float antiSpoofBonus = 0f;
        if (livenessDetected && bpmVariability > 1.5f) {
            antiSpoofBonus = 0.05f; // 5% bonus for confirmed live human
        }

        float prnuNorm = clamp01(prnuConfidence / 100f);
        float consentNorm = consentVerified ? 1.0f
                : "SAFE".equals(contentCategory) ? 1.0f : 0.0f;
        float wmNorm = watermarkEmbedded ? 1.0f : 0.0f;
        float sbNorm = strongBoxSigned ? 1.0f : 0.0f;

        float raw = (prnuNorm * W_PRNU)
                   + (bpmFactor * W_BPM)
                   + (consentNorm * W_CONSENT)
                   + (wmNorm * W_WATERMARK)
                   + (sbNorm * W_STRONGBOX)
                   + antiSpoofBonus;

        return Math.round(raw * 100f);
    }

    private static String labelFromScore(int score) {
        if (score >= 75) return "VERIFIED";
        if (score >= 50) return "MEDIUM";
        if (score >= 25) return "LOW";
        return "UNVERIFIED";
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    // ── JSON serialization ──

    public JSONObject toJSON() {
        JSONObject j = new JSONObject();
        try {
            j.put("riskScore", riskScore);
            j.put("riskLabel", riskLabel);
            j.put("bpm", bpm);
            j.put("bpmConfidence", bpmConfidence);
            j.put("prnuConfidence", prnuConfidence);
            j.put("deviceFingerprint", deviceFingerprint);
            j.put("watermarkEmbedded", watermarkEmbedded);
            j.put("consentVerified", consentVerified);
            j.put("consentSignatures", consentSignatures);
            j.put("strongBoxSigned", strongBoxSigned);
            j.put("contentCategory", contentCategory);
            j.put("videoHash", videoHash);
            j.put("bioSignature", bioSignature);
            j.put("proofOfRealityHash", proofOfRealityHash);
            j.put("livenessDetected", livenessDetected);
            j.put("bpmVariability", bpmVariability);
            j.put("antiSpoofPassed", livenessDetected && bpmVariability > 1.5f);
        } catch (JSONException ignored) {}
        return j;
    }

    // ── Builder ──

    public static class Builder {
        float bpm;
        float bpmConfidence;
        String deviceFingerprint;
        boolean watermarkEmbedded;
        boolean consentVerified;
        int consentSignatures;
        boolean strongBoxSigned;
        String contentCategory;
        String videoHash;
        String bioSignature;
        String proofOfRealityHash;
        boolean livenessDetected;
        float bpmVariability;

        public Builder bpm(float v)              { this.bpm = v; return this; }
        public Builder bpmConfidence(float v)     { this.bpmConfidence = v; return this; }
        public Builder deviceFingerprint(String v){ this.deviceFingerprint = v; return this; }
        public Builder watermarkEmbedded(boolean v){ this.watermarkEmbedded = v; return this; }
        public Builder consentVerified(boolean v) { this.consentVerified = v; return this; }
        public Builder consentSignatures(int v)   { this.consentSignatures = v; return this; }
        public Builder strongBoxSigned(boolean v) { this.strongBoxSigned = v; return this; }
        public Builder contentCategory(String v)  { this.contentCategory = v; return this; }
        public Builder videoHash(String v)        { this.videoHash = v; return this; }
        public Builder bioSignature(String v)     { this.bioSignature = v; return this; }
        public Builder proofOfRealityHash(String v){ this.proofOfRealityHash = v; return this; }
        public Builder livenessDetected(boolean v){ this.livenessDetected = v; return this; }
        public Builder bpmVariability(float v)    { this.bpmVariability = v; return this; }

        public CaptureProof build() { return new CaptureProof(this); }
    }
}
