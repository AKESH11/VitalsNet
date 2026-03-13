package com.biovault.sdk;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

/**
 * BioVault SDK — public entry point for third-party integrators.
 *
 * Usage:
 *   BioVaultSDK sdk = new BioVaultSDK(context);
 *   sdk.initialize();
 *
 *   // Content classification
 *   ContentClassifier.ClassificationResult result = sdk.classifyContent(bitmap);
 *
 *   // Heart-rate (rPPG) — feed camera frames, then read BPM
 *   sdk.addRPPGFrame(bitmap);
 *   TSCANInference.InferenceResult bpm = sdk.getCurrentBPM();
 *
 *   // BLE consent protocol
 *   sdk.getConsentBroadcaster().startConsentRequest(sessionId, category, callback);
 *
 *   // StrongBox / TEE key management
 *   sdk.getStrongBoxManager().generateRealityKey();
 *
 *   // Cleanup
 *   sdk.release();
 */
public class BioVaultSDK {
    private static final String TAG = "BioVaultSDK";
    public static final String VERSION = "1.0.0";

    private final Context context;
    private StrongBoxManager strongBoxManager;
    private ConsentBroadcaster consentBroadcaster;
    private TSCANInference tscanInference;
    private boolean initialized = false;

    static {
        System.loadLibrary("BioVaultCore");
    }

    public BioVaultSDK(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Initialize all SDK subsystems.
     * Call once after construction; safe to call multiple times.
     *
     * @return true if at least the core was initialized (TS-CAN may be unavailable on low-end devices)
     */
    public boolean initialize() {
        if (initialized) return true;

        try {
            strongBoxManager = new StrongBoxManager(context);
            consentBroadcaster = new ConsentBroadcaster(context);

            try {
                tscanInference = new TSCANInference(context);
                if (!tscanInference.isReady()) {
                    Log.w(TAG, "TS-CAN model not available — rPPG disabled");
                    tscanInference = null;
                }
            } catch (Exception e) {
                Log.w(TAG, "TS-CAN init failed: " + e.getMessage());
                tscanInference = null;
            }

            initialized = true;
            Log.i(TAG, "BioVault SDK v" + VERSION + " initialized"
                + (tscanInference != null ? " [rPPG: TS-CAN]" : " [rPPG: unavailable]"));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "SDK initialization failed", e);
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Content Classification
    // ═══════════════════════════════════════════════════════════════

    /**
     * Classify a bitmap as SAFE / SENSITIVE / EXPLICIT.
     * Thread-safe; blocking (waits for ML Kit, typically &lt;200 ms).
     */
    public ContentClassifier.ClassificationResult classifyContent(Bitmap bitmap) {
        return ContentClassifier.classify(bitmap);
    }

    // ═══════════════════════════════════════════════════════════════
    //  rPPG (Heart-Rate Estimation)
    // ═══════════════════════════════════════════════════════════════

    /** @return true if the TS-CAN neural rPPG engine is available */
    public boolean isRPPGAvailable() {
        return tscanInference != null && tscanInference.isReady();
    }

    /** Feed a camera frame to the rPPG pipeline. */
    public void addRPPGFrame(Bitmap frame) {
        if (tscanInference != null) tscanInference.addFrame(frame);
    }

    /** Get the latest BPM estimate (non-blocking). */
    public TSCANInference.InferenceResult getCurrentBPM() {
        if (tscanInference != null) return tscanInference.getCurrentBPM();
        return new TSCANInference.InferenceResult(0f, 0f, 0, false);
    }

    /** Accumulated session BPM (median). */
    public TSCANInference.InferenceResult getAccumulatedBPM() {
        if (tscanInference != null) return tscanInference.getAccumulatedBPM();
        return new TSCANInference.InferenceResult(0f, 0f, 0, false);
    }

    /** Reset rPPG session state. */
    public void resetRPPGSession() {
        if (tscanInference != null) tscanInference.resetSession();
    }

    // ═══════════════════════════════════════════════════════════════
    //  BLE Consent
    // ═══════════════════════════════════════════════════════════════

    /** Direct access to the BLE consent broadcaster. */
    public ConsentBroadcaster getConsentBroadcaster() {
        return consentBroadcaster;
    }

    // ═══════════════════════════════════════════════════════════════
    //  StrongBox / TEE
    // ═══════════════════════════════════════════════════════════════

    /** Direct access to the hardware keystore manager. */
    public StrongBoxManager getStrongBoxManager() {
        return strongBoxManager;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /** Release all resources. Call in Application.onTerminate or Activity.onDestroy. */
    public void release() {
        if (tscanInference != null) {
            tscanInference.cleanup();
            tscanInference = null;
        }
        if (strongBoxManager != null) {
            strongBoxManager.destroy();
            strongBoxManager = null;
        }
        consentBroadcaster = null;
        initialized = false;
        Log.i(TAG, "BioVault SDK released");
    }
}
