package com.biovault;

import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.content.Context;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.module.annotations.ReactModule;

import java.io.ByteArrayOutputStream;

/**
 * React Native module bridge to C++ Bio-Vault core
 * Supports hybrid rPPG: PhysNet (neural network) + FFT (classical)
 */
@ReactModule(name = "BioVaultModule")
public class BioVaultModule extends ReactContextBaseJavaModule {
    
    static {
        // Load native library
        System.loadLibrary("BioVaultCore");
    }

    // Singleton reference for static JNI access from ConsentBroadcaster
    private static BioVaultModule instance;

    private final ReactApplicationContext reactContext;
    private StrongBoxManager strongBoxManager;
    private TSCANInference tscanInference;
    private boolean tscanAvailable = false;
    private int frameProcessCounter = 0;
    
    // PRNU-derived hardware fingerprint (BLAKE3 hash of sensor noise)
    // Replaces Build.FINGERPRINT — this is a real per-device unique identifier
    private String cachedPRNUFingerprint = null;
    private int calibrationFrameCount = 0;
    
    // rPPG session state — running content hash during recording
    private boolean isRPPGSessionActive = false;
    private java.security.MessageDigest videoDigest = null;
    private int rppgFrameCount = 0;

    // Last captured frame for watermark embedding (base64 JPEG)
    private String lastFrameBase64 = null;

    // Face tracking stabilization (exponential moving average)
    private float[] smoothedFaceBounds = null;  // [x, y, width, height]
    private static final float FACE_SMOOTHING_ALPHA = 0.7f;  // Higher = more smoothing

    public BioVaultModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
        this.strongBoxManager = new StrongBoxManager(context);
        instance = this;
        
        // Restore cached PRNU fingerprint from SharedPreferences
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("biovault_prnu", Context.MODE_PRIVATE);
            String saved = prefs.getString("fingerprint", null);
            if (saved != null && !saved.isEmpty()) {
                cachedPRNUFingerprint = saved;
                android.util.Log.i("BioVault", "Restored PRNU fingerprint from cache: " + saved.substring(0, Math.min(16, saved.length())) + "...");
            }
        } catch (Exception e) {
            android.util.Log.w("BioVault", "Failed to restore PRNU cache: " + e.getMessage());
        }

        // Initialize TS-CAN rPPG (NO FFT FALLBACK)
        try {
            tscanInference = new TSCANInference(context);
            tscanAvailable = tscanInference.isReady();
            
            if (tscanAvailable) {
                android.util.Log.i("BioVault", "✓ TS-CAN MODE ENABLED");
                android.util.Log.i("BioVault", "✓ FFT disabled - pure TS-CAN neural inference");
                android.util.Log.i("BioVault", "✓ 10 frames, 72x72, dual-branch (motion+appearance)");
                android.util.Log.i("BioVault", "✓ 31x faster than PhysNet!");
            } else {
                android.util.Log.e("BioVault", "✗ TS-CAN model failed to load - rPPG unavailable");
            }
        } catch (Exception e) {
            android.util.Log.e("BioVault", "TS-CAN initialization failed", e);
            tscanAvailable = false;
        }
    }

    @Override
    public String getName() {
        return "BioVaultModule";
    }

    // Required by NativeEventEmitter
    @ReactMethod
    public void addListener(String eventName) { /* no-op */ }

    @ReactMethod
    public void removeListeners(int count) { /* no-op */ }

    /**
     * Detects if device is high-end enough for PhysNet inference.
     * Checks CPU architecture and device model/hardware.
     */
    private boolean isHighEndDevice() {
        String model = Build.MODEL.toLowerCase();
        String hardware = Build.HARDWARE.toLowerCase();
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        
        // Check for 64-bit ARM architecture (arm64-v8a)
        boolean is64Bit = false;
        for (String abi : supportedAbis) {
            if (abi.contains("arm64-v8a") || abi.contains("x86_64")) {
                is64Bit = true;
                break;
            }
        }
        
        if (!is64Bit) {
            return false; // 32-bit devices too slow for PhysNet
        }
        
        // Check for flagship chipsets (Snapdragon 8-series, Tensor, Dimensity 9000+)
        boolean hasFlagshipCpu = hardware.contains("qcom") || hardware.contains("exynos") ||
                                  hardware.contains("tensor") || hardware.contains("dimensity");
        
        // Check for flagship device models
        boolean isFlagshipDevice = model.contains("pixel") ||
                                    model.contains("galaxy s") ||
                                    model.contains("oneplus") ||
                                    model.contains("xiaomi 13") || model.contains("xiaomi 14") ||
                                    model.contains("oppo find") ||
                                    model.contains("vivo x") ||
                                    model.contains("iqoo");
        
        // Require at least 6GB RAM (check available memory)
        android.app.ActivityManager activityManager = (android.app.ActivityManager) 
            reactContext.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memInfo);
        long totalMemoryMB = memInfo.totalMem / (1024 * 1024);
        boolean hasEnoughRAM = totalMemoryMB >= 6000; // 6 GB minimum
        
        android.util.Log.i("BioVault", String.format(
            "Device detection: model=%s, hardware=%s, 64bit=%b, flagship_cpu=%b, flagship_device=%b, ram=%dMB",
            model, hardware, is64Bit, hasFlagshipCpu, isFlagshipDevice, totalMemoryMB));
        
        // Enable PhysNet if: 64-bit + (flagship CPU OR flagship device) + enough RAM
        return is64Bit && (hasFlagshipCpu || isFlagshipDevice) && hasEnoughRAM;
    }

    /**
     * Converts YUV_420_888 frame data to RGB Bitmap for TS-CAN input.
     * Returns full-resolution frame (cropping happens later).
     */
    private Bitmap yuvToBitmap(byte[] yuvData, int width, int height, int rotation) {
        try {
            // Create YuvImage from NV21 data
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                yuvData, 
                android.graphics.ImageFormat.NV21, 
                width, 
                height, 
                null
            );
            
            // Convert to JPEG (intermediate format)
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 90, out);
            byte[] imageBytes = out.toByteArray();
            
            // Decode JPEG to Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            
            if (bitmap == null) {
                android.util.Log.e("BioVault", "Failed to decode YUV frame to Bitmap");
                return null;
            }
            
            // Apply rotation if needed
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            
            return bitmap;
            
        } catch (Exception e) {
            android.util.Log.e("BioVault", "Error converting YUV to Bitmap: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Smooth face bounding box using exponential moving average
     * Reduces jitter from Haar Cascade face detection
     * @param rawBounds Current frame's detected face bounds [x, y, width, height]
     * @return Stabilized face bounds
     */
    private float[] smoothFaceBoundingBox(float[] rawBounds) {
        if (rawBounds == null || rawBounds.length != 4) {
            return rawBounds;
        }
        
        // Initialize on first frame
        if (smoothedFaceBounds == null) {
            smoothedFaceBounds = new float[4];
            System.arraycopy(rawBounds, 0, smoothedFaceBounds, 0, 4);
            return smoothedFaceBounds;
        }
        
        // Apply exponential moving average: smoothed = alpha * smoothed + (1-alpha) * raw
        // Higher alpha = more smoothing (less jitter, slower response)
        for (int i = 0; i < 4; i++) {
            smoothedFaceBounds[i] = FACE_SMOOTHING_ALPHA * smoothedFaceBounds[i] + 
                                   (1.0f - FACE_SMOOTHING_ALPHA) * rawBounds[i];
        }
        
        return smoothedFaceBounds;
    }

    // Native method declarations (implemented in C++)
    private native String nativeInitialize();
    private native String processFrame(String frameData, int width, int height, String faceBounds);
    private native String calibrateHardware(String calibrationFramesJson);
    private native boolean nativeAddCalibrationFrame(byte[] rgbaData, int width, int height);
    private native String nativeFinalizeCalibration();
    private native String nativeGetHardwareDNA();
    private native String generateAnchorHash(String frameData, int bpm, String hardwareID);
    private native byte[] generateBioVaultProof(byte[] frameData, int bpm, String hardwareID);
    private native boolean testStrongBoxSignature();
    private native boolean initConsensusSession(String sessionId, int[] expectedFaceIds, 
                                                 byte[] videoFrameHash, String hardwareDNA);
    private native boolean appendConsensusSignature(String sessionId, int faceId, int bpm,
                                                     byte[] signature, byte[] publicKey);
    private native String finalizeConsensus(String sessionId);
    private native void reset();

    /**
     * Public static bridge so ConsentBroadcaster (Kotlin) can call JNI consensus methods
     * without holding a direct JNI reference.
     */
    public static String computeConsensusHashStatic(
            String sessionId,
            java.util.List<ConsentBroadcaster.BLESignatureData> signatures) {
        if (instance == null) return "";
        try {
            // Use real PRNU hardware DNA instead of Build.FINGERPRINT
            String hardwareDNA = instance.getResolvedHardwareDNA();

            // Init consensus session with empty frame hash and real hardware DNA
            instance.initConsensusSession(
                sessionId,
                new int[signatures.size()],
                new byte[0],
                hardwareDNA);

            // Append each peer's signature
            for (ConsentBroadcaster.BLESignatureData sig : signatures) {
                instance.appendConsensusSignature(
                    sessionId, sig.getFaceId(), sig.getBpm(),
                    sig.getSignature(), sig.getPublicKey());
            }

            // Finalize - returns BLAKE3 consensus hash from C++
            return instance.finalizeConsensus(sessionId);
        } catch (Exception e) {
            android.util.Log.e("BioVault", "computeConsensusHashStatic failed: " + e.getMessage());
            return "";
        }
    }
    
    // Camera bridge native methods (implemented in camera_bridge.cpp)
    private native boolean nativeInitializeCamera(String cascadePath);
    private native String nativeProcessCameraFrame(byte[] frameData, int width, int height, int format);
    private native String nativeProcessMultiFace(byte[] frameData, int width, int height);
    private native boolean nativeStartRPPGSession();
    private native String nativeStopRPPGSession();
    private native void nativeReleaseCamera();

    // Watermark native methods (implemented in bio_vault_native.cpp)
    private native byte[] nativeEmbedWatermark(byte[] imageRgba, int w, int h, String payloadJson);
    private native String nativeExtractWatermark(byte[] imageRgba, int w, int h);

    @ReactMethod
    public void init(Promise promise) {
        try {
            String result = nativeInitialize();
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("INIT_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void processVideoFrame(String frameData, int width, int height, 
                                   String faceBounds, Promise promise) {
        try {
            String result = processFrame(frameData, width, height, faceBounds);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("PROCESS_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void calibrateDevice(String calibrationFramesJson, Promise promise) {
        try {
            String result = calibrateHardware(calibrationFramesJson);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("CALIBRATE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void createAnchorHash(String frameData, int bpm, String hardwareID, Promise promise) {
        try {
            String result = generateAnchorHash(frameData, bpm, hardwareID);
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("HASH_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void createBioVaultProof(String frameDataBase64, int bpm, String hardwareID, Promise promise) {
        try {
            // Decode base64 frame data
            byte[] frameData = android.util.Base64.decode(frameDataBase64, android.util.Base64.DEFAULT);
            
            // Generate proof with StrongBox signature
            byte[] proof = generateBioVaultProof(frameData, bpm, hardwareID);
            
            if (proof == null || proof.length == 0) {
                promise.reject("PROOF_ERROR", "Failed to generate proof. Check biometric authentication.");
                return;
            }
            
            // Encode proof as base64 for React Native
            String proofBase64 = android.util.Base64.encodeToString(proof, android.util.Base64.NO_WRAP);
            promise.resolve(proofBase64);
        } catch (Exception e) {
            promise.reject("PROOF_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void testStrongBox(Promise promise) {
        try {
            boolean success = testStrongBoxSignature();
            promise.resolve(success);
        } catch (Exception e) {
            promise.reject("TEST_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void initializeStrongBox(Promise promise) {
        try {
            boolean isSupported = strongBoxManager.isStrongBoxSupported();
            boolean keyGenerated = strongBoxManager.generateRealityKey();
            
            if (!keyGenerated) {
                promise.reject("STRONGBOX_ERROR", "Failed to generate reality key");
                return;
            }
            
            Boolean isInStrongBox = strongBoxManager.isKeyInStrongBox();
            String securityLevel = isInStrongBox == null ? "unknown" : 
                                  (isInStrongBox ? "strongbox" : "tee");
            
            com.facebook.react.bridge.WritableMap result = com.facebook.react.bridge.Arguments.createMap();
            result.putBoolean("strongBoxSupported", isSupported);
            result.putBoolean("keyGenerated", true);
            result.putString("securityLevel", securityLevel);
            
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("STRONGBOX_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getSecurityInfo(Promise promise) {
        try {
            boolean hasKey = strongBoxManager.hasRealityKey();
            Boolean isInStrongBox = strongBoxManager.isKeyInStrongBox();
            
            com.facebook.react.bridge.WritableMap result = com.facebook.react.bridge.Arguments.createMap();
            result.putBoolean("hasRealityKey", hasKey);
            
            if (hasKey && isInStrongBox != null) {
                result.putString("securityLevel", isInStrongBox ? "strongbox" : "tee");
            } else {
                result.putString("securityLevel", "unknown");
            }
            
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("INFO_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void startConsensusSession(String sessionId, com.facebook.react.bridge.ReadableArray faceIds,
                                      String videoFrameHashBase64, String hardwareDNA, Promise promise) {
        try {
            // Convert faceIds array
            int[] faceIdArray = new int[faceIds.size()];
            for (int i = 0; i < faceIds.size(); i++) {
                faceIdArray[i] = faceIds.getInt(i);
            }
            
            // Decode video frame hash
            byte[] frameHash = android.util.Base64.decode(videoFrameHashBase64, android.util.Base64.DEFAULT);
            
            // Initialize consensus session in C++
            boolean success = initConsensusSession(sessionId, faceIdArray, frameHash, hardwareDNA);
            
            if (!success) {
                promise.reject("CONSENSUS_ERROR", "Failed to initialize consensus session");
                return;
            }
            
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("CONSENSUS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void addConsensusSignature(String sessionId, int faceId, int bpm,
                                      String signatureBase64, String publicKeyBase64, Promise promise) {
        try {
            // Decode signature and public key
            byte[] signature = android.util.Base64.decode(signatureBase64, android.util.Base64.DEFAULT);
            byte[] publicKey = android.util.Base64.decode(publicKeyBase64, android.util.Base64.DEFAULT);
            
            // Append signature to consensus session in C++
            boolean success = appendConsensusSignature(sessionId, faceId, bpm, signature, publicKey);
            
            promise.resolve(success);
        } catch (Exception e) {
            promise.reject("CONSENSUS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void finalizeConsensusSession(String sessionId, Promise promise) {
        try {
            // Finalize and get result from C++
            String resultJson = finalizeConsensus(sessionId);
            
            if (resultJson == null) {
                promise.reject("CONSENSUS_ERROR", "Session not found");
                return;
            }
            
            // Parse JSON and return as map
            // For simplicity, return raw JSON string
            promise.resolve(resultJson);
        } catch (Exception e) {
            promise.reject("CONSENSUS_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void resetEngine() {
        reset();
    }
    
    // ============================================
    // Camera Integration Methods
    // ============================================
    
    @ReactMethod
    public void processCameraFrame(String frameDataBase64, int width, int height, 
                                   int format, Promise promise) {
        try {
            // Decode base64 frame data
            byte[] frameData = android.util.Base64.decode(frameDataBase64, android.util.Base64.DEFAULT);
            
            // Process through native camera bridge
            String result = nativeProcessCameraFrame(frameData, width, height, format);
            
            if (result != null) {
                promise.resolve(result);
            } else {
                promise.reject("PROCESS_ERROR", "Failed to process camera frame");
            }
        } catch (Exception e) {
            promise.reject("PROCESS_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void processMultiFaceFrame(String frameDataBase64, int width, int height, Promise promise) {
        try {
            byte[] frameData = android.util.Base64.decode(frameDataBase64, android.util.Base64.DEFAULT);
            String result = nativeProcessMultiFace(frameData, width, height);
            
            if (result != null) {
                promise.resolve(result);
            } else {
                promise.reject("PROCESS_ERROR", "Failed to process multi-face frame");
            }
        } catch (Exception e) {
            promise.reject("PROCESS_ERROR", e.getMessage());
        }
    }
    
    // Synchronous method for direct calls from camera view
    public WritableMap processVideoFrameSync(byte[] frameData, int width, int height, int rotation) {
        try {
            frameProcessCounter++;
            
            // TS-CAN MODE: Use C++ for face detection, TS-CAN for rPPG
            if (!tscanAvailable || tscanInference == null) {
                // TS-CAN not available - return error
                WritableMap errorMap = Arguments.createMap();
                errorMap.putBoolean("error", true);
                errorMap.putString("message", "TS-CAN model not loaded");
                errorMap.putInt("facesDetected", 0);
                return errorMap;
            }
            
            // Accumulate content hash during active rPPG session
            if (isRPPGSessionActive && videoDigest != null) {
                videoDigest.update(frameData);
                rppgFrameCount++;
            }

            // Call C++ for face detection (but not FFT)
            String cppResult = nativeProcessCameraFrame(frameData, width, height, rotation);
            
            if (cppResult != null) {
                // Parse face detection from C++ result
                org.json.JSONObject json = new org.json.JSONObject(cppResult);
                int facesDetected = json.has("facesDetected") ? json.getInt("facesDetected") : 0;
                
                // Only add frames when face is detected
                if (facesDetected > 0) {
                    Bitmap frameBitmap = yuvToBitmap(frameData, width, height, rotation);
                    if (frameBitmap != null) {
                        Bitmap roiBitmap = null;
                        try {
                            if (json.has("faceBox")) {
                                org.json.JSONObject faceBox = json.getJSONObject("faceBox");
                                float[] rawBounds = new float[] {
                                    (float) faceBox.optDouble("x", 0.0),
                                    (float) faceBox.optDouble("y", 0.0),
                                    (float) faceBox.optDouble("width", frameBitmap.getWidth()),
                                    (float) faceBox.optDouble("height", frameBitmap.getHeight())
                                };

                                float[] smoothBounds = smoothFaceBoundingBox(rawBounds);

                                int left = Math.max(0, Math.min((int) Math.round(smoothBounds[0]), frameBitmap.getWidth() - 1));
                                int top = Math.max(0, Math.min((int) Math.round(smoothBounds[1]), frameBitmap.getHeight() - 1));
                                int boxWidth = Math.max(1, Math.min((int) Math.round(smoothBounds[2]), frameBitmap.getWidth() - left));
                                int boxHeight = Math.max(1, Math.min((int) Math.round(smoothBounds[3]), frameBitmap.getHeight() - top));

                                roiBitmap = Bitmap.createBitmap(frameBitmap, left, top, boxWidth, boxHeight);
                            }
                        } catch (Exception e) {
                            android.util.Log.w("BioVault", "Failed to crop face ROI, using full frame", e);
                        }

                        Bitmap inputBitmap = (roiBitmap != null) ? roiBitmap : frameBitmap;
                        tscanInference.addFrame(inputBitmap);

                        if (roiBitmap != null && roiBitmap != frameBitmap) {
                            roiBitmap.recycle();
                        }
                        frameBitmap.recycle();
                    }
                    
                    // Get current BPM from TS-CAN inference
                    TSCANInference.InferenceResult result = tscanInference.getCurrentBPM();

                    // Cache last frame as base64 JPEG for watermark embedding
                    if (isRPPGSessionActive) {
                        try {
                            Bitmap snapBmp = yuvToBitmap(frameData, width, height, rotation);
                            if (snapBmp != null) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                snapBmp.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                                snapBmp.recycle();
                                lastFrameBase64 = android.util.Base64.encodeToString(
                                    baos.toByteArray(), android.util.Base64.NO_WRAP);
                            }
                        } catch (Exception snapErr) {
                            // Non-fatal
                        }
                    }
                    
                    // Create result map
                    WritableMap map = Arguments.createMap();
                    map.putInt("bpm", (int) Math.round(result.bpm));
                    map.putDouble("confidence", result.confidence);
                    map.putString("method", "TS-CAN");
                    map.putBoolean("isValid", result.isValid);
                    map.putInt("facesDetected", facesDetected);
                    map.putInt("width", width);
                    map.putInt("height", height);
                    
                    if (result.isValid) {
                        map.putInt("inferenceTime", (int) result.inferenceTimeMs);
                    }
                    
                    return map;
                } else {
                    // No face detected - return early
                    WritableMap map = Arguments.createMap();
                    map.putInt("facesDetected", 0);
                    map.putInt("bpm", 0);
                    map.putDouble("confidence", 0.0);
                    map.putBoolean("isValid", false);
                    map.putInt("width", width);
                    map.putInt("height", height);
                    return map;
                }
            }
            
            // C++ call failed
            WritableMap errorMap = Arguments.createMap();
            errorMap.putBoolean("error", true);
            errorMap.putString("message", "Face detection failed");
            errorMap.putInt("facesDetected", 0);
            return errorMap;
            
        } catch (Exception e) {
            android.util.Log.e("BioVault", "Error in TS-CAN processing: " + e.getMessage());
            WritableMap errorMap = Arguments.createMap();
            errorMap.putBoolean("error", true);
            errorMap.putString("message", e.getMessage());
            return errorMap;
        }
    }
    
    @ReactMethod
    public void startRPPGExtraction(Promise promise) {
        try {
            // Initialize running SHA-256 content hash
            videoDigest = java.security.MessageDigest.getInstance("SHA-256");
            rppgFrameCount = 0;
            isRPPGSessionActive = true;

            // Reset TS-CAN session for fresh BPM accumulation
            if (tscanInference != null) {
                tscanInference.resetSession();
            }

            // Still call native for C++ face detection pipeline init
            boolean success = nativeStartRPPGSession();
            android.util.Log.i("BioVault", "rPPG session started, content hash initialized");
            promise.resolve(success);
        } catch (Exception e) {
            promise.reject("RPPG_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void setPhysNetEnabled(boolean enabled, Promise promise) {
        try {
            // TS-CAN mode - always returns TS-CAN status
            WritableMap result = Arguments.createMap();
            result.putBoolean("enabled", tscanAvailable);
            result.putBoolean("available", tscanAvailable);
            result.putString("mode", "ts-can");
            
            android.util.Log.i("BioVault", "rPPG mode: TS-CAN (no FFT)");
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("MODE_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void getPhysNetStatus(Promise promise) {
        WritableMap status = Arguments.createMap();
        status.putBoolean("available", tscanAvailable);
        status.putBoolean("enabled", tscanAvailable);
        status.putBoolean("highEndDevice", isHighEndDevice());
        status.putString("currentMode", tscanAvailable ? "TS-CAN" : "Unavailable");
        status.putString("deviceModel", Build.MODEL);
        status.putString("cpuAbi", Build.SUPPORTED_ABIS[0]);
        promise.resolve(status);
    }
    
    @ReactMethod
    public void stopRPPGExtraction(Promise promise) {
        try {
            isRPPGSessionActive = false;

            // 1. Real BPM from TS-CAN
            float avgBPM = 0f;
            float confidence = 0f;
            if (tscanInference != null) {
                TSCANInference.InferenceResult bpmResult = tscanInference.getAccumulatedBPM();
                if (bpmResult.isValid) {
                    avgBPM = bpmResult.bpm;
                    confidence = bpmResult.confidence;
                }
            }

            // 2. Real SHA-256 content hash of all video frames
            String videoHash = "";
            if (videoDigest != null) {
                byte[] hash = videoDigest.digest();
                StringBuilder sb = new StringBuilder("0x");
                for (byte b : hash) {
                    sb.append(String.format("%02x", b));
                }
                videoHash = sb.toString();
                videoDigest = null;
            }

            // 3. Real PRNU hardware DNA
            String hardwareDNA = getResolvedHardwareDNA();

            // 4. Bio-signature: SHA-256(bpm + hardwareDNA + timestamp)
            String bioSignature = "";
            try {
                java.security.MessageDigest bioDigest = java.security.MessageDigest.getInstance("SHA-256");
                String bioInput = Math.round(avgBPM) + ":" + hardwareDNA + ":" + System.currentTimeMillis();
                byte[] bioHash = bioDigest.digest(bioInput.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder("0x");
                for (byte b : bioHash) {
                    sb.append(String.format("%02x", b));
                }
                bioSignature = sb.toString();
            } catch (Exception e) {
                android.util.Log.w("BioVault", "Bio-signature computation failed: " + e.getMessage());
            }

            // 5. ML Kit content classification on last captured frame
            String contentCategory = "SAFE";
            boolean requiresConsent = false;
            String contentLabel = "none";
            float contentConfidence = 0f;
            try {
                if (lastFrameBase64 != null && !lastFrameBase64.isEmpty()) {
                    byte[] frameBytes = android.util.Base64.decode(lastFrameBase64, android.util.Base64.DEFAULT);
                    Bitmap frameBmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.length);
                    if (frameBmp != null) {
                        ContentClassifier.ClassificationResult classResult = ContentClassifier.classify(frameBmp);
                        frameBmp.recycle();
                        contentCategory = classResult.category;
                        requiresConsent = classResult.requiresConsent;
                        contentLabel = classResult.topLabel;
                        contentConfidence = classResult.topConfidence;
                    }
                }
            } catch (Exception classifyErr) {
                android.util.Log.w("BioVault", "Content classification failed: " + classifyErr.getMessage());
            }

            // Build real result JSON
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", true);
            json.put("videoHash", videoHash);
            json.put("bioSignature", bioSignature);
            json.put("hardwareDNA", hardwareDNA);
            json.put("averageBPM", Math.round(avgBPM));
            json.put("confidence", Math.round(confidence * 100) / 100.0);
            json.put("frameCount", rppgFrameCount);
            json.put("contentCategory", contentCategory);
            json.put("requiresConsent", requiresConsent);
            json.put("contentLabel", contentLabel);
            json.put("contentConfidence", Math.round(contentConfidence * 100) / 100.0);

            android.util.Log.i("BioVault", "rPPG session stopped — BPM=" + Math.round(avgBPM)
                + " frames=" + rppgFrameCount + " hash=" + videoHash.substring(0, Math.min(18, videoHash.length())) + "...");

            promise.resolve(json.toString());
        } catch (Exception e) {
            promise.reject("RPPG_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void initializeCamera(String cascadePath, Promise promise) {
        try {
            boolean success = nativeInitializeCamera(cascadePath);
            promise.resolve(success);
        } catch (Exception e) {
            promise.reject("CAMERA_INIT_ERROR", e.getMessage());
        }
    }
    
    @ReactMethod
    public void releaseCamera() {
        try {
            nativeReleaseCamera();
        } catch (Exception e) {
            // Silent fail on cleanup
        }
    }

    // ============================================
    // Proof of Reality / Hardware Fingerprinting
    // ============================================

    /**
     * Generate a complete Proof of Reality bundle.
     * Combines video hash + BPM + hardware ID + timestamp into a BLAKE3 hash.
     */
    @ReactMethod
    public void generateProofOfReality(int bpm, Promise promise) {
        try {
            // Get real PRNU-derived hardware fingerprint (NOT Build.FINGERPRINT)
            String hardwareID = getResolvedHardwareDNA();

            // Generate anchor hash via C++ (BLAKE3 of frame data + BPM + hardwareID)
            String proofHash = generateAnchorHash("", bpm, hardwareID);

            // Create bio-signature: composite of BPM + hardware + StrongBox sign
            byte[] bioSigBytes = null;
            try {
                if (strongBoxManager.hasRealityKey()) {
                    String bioSigInput = bpm + ":" + hardwareID + ":" + System.currentTimeMillis();
                    byte[] inputHash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(bioSigInput.getBytes("UTF-8"));
                    bioSigBytes = strongBoxManager.signHash(inputHash);
                }
            } catch (Exception e) {
                android.util.Log.w("BioVault", "StrongBox sign failed, using hash only: " + e.getMessage());
            }

            String bioSignature = (bioSigBytes != null)
                ? android.util.Base64.encodeToString(bioSigBytes, android.util.Base64.NO_WRAP)
                : "bpm:" + bpm + ":hw:" + hardwareID;

            WritableMap result = Arguments.createMap();
            result.putString("proofOfRealityHash", proofHash != null ? proofHash : "");
            result.putString("bioSignature", bioSignature);
            result.putString("hardwareID", hardwareID);
            result.putString("videoHash", proofHash != null ? proofHash : "");
            result.putDouble("timestamp", (double) System.currentTimeMillis());
            promise.resolve(result);
        } catch (Exception e) {
            promise.reject("PROOF_ERROR", e.getMessage());
        }
    }

    /**
     * Get hardware fingerprint (PRNU-based device ID).
     * Returns the real BLAKE3 hash of the camera sensor's PRNU noise pattern.
     * Falls back to Build.FINGERPRINT only if PRNU calibration has not been run.
     */
    @ReactMethod
    public void getHardwareFingerprint(Promise promise) {
        try {
            promise.resolve(getResolvedHardwareDNA());
        } catch (Exception e) {
            promise.reject("HW_ERROR", e.getMessage());
        }
    }

    /**
     * Internal helper: returns the PRNU fingerprint if calibrated,
     * or triggers a warning and returns a labelled fallback.
     * NEVER returns raw Build.FINGERPRINT silently.
     */
    private String getResolvedHardwareDNA() {
        // 1. Check JNI-cached value
        if (cachedPRNUFingerprint != null && !cachedPRNUFingerprint.isEmpty()) {
            return cachedPRNUFingerprint;
        }
        // 2. Ask the C++ layer (survives React Native reloads)
        try {
            String nativeDNA = nativeGetHardwareDNA();
            if (nativeDNA != null && !nativeDNA.isEmpty()) {
                cachedPRNUFingerprint = nativeDNA;
                return cachedPRNUFingerprint;
            }
        } catch (Exception e) {
            android.util.Log.w("BioVault", "nativeGetHardwareDNA failed: " + e.getMessage());
        }
        // 3. PRNU not calibrated yet — return clearly-labelled fallback
        android.util.Log.w("BioVault", "PRNU not calibrated — hardware DNA unavailable. Run calibrateDevice first.");
        return "UNCALIBRATED:" + Build.FINGERPRINT;
    }

    // ============================================
    // PRNU Calibration (Incremental Frame API)
    // ============================================

    /**
     * Add a single RGBA camera frame for PRNU calibration.
     * Must be called at least 50 times before finalizeCalibration().
     * @param frameDataBase64 Base64-encoded RGBA pixel data
     * @param width  Frame width
     * @param height Frame height
     */
    @ReactMethod
    public void addCalibrationFrame(String frameDataBase64, int width, int height, Promise promise) {
        try {
            byte[] rgbaData = android.util.Base64.decode(frameDataBase64, android.util.Base64.DEFAULT);
            boolean ok = nativeAddCalibrationFrame(rgbaData, width, height);
            if (ok) calibrationFrameCount++;
            promise.resolve(ok);
        } catch (Exception e) {
            promise.reject("CALIBRATE_ERROR", e.getMessage());
        }
    }

    /**
     * Add a calibration frame directly from YUV camera data (called from CameraViewManager).
     * Converts YUV to RGBA and feeds to native PRNU extractor.
     * @return current frame count, or -1 on error
     */
    public int addCalibrationFrameFromYUV(byte[] yuvData, int width, int height) {
        try {
            Bitmap bitmap = yuvToBitmap(yuvData, width, height, 0);
            if (bitmap == null) return -1;

            Bitmap argb = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            if (argb == null) return -1;

            int w = argb.getWidth();
            int h = argb.getHeight();
            int[] pixels = new int[w * h];
            argb.getPixels(pixels, 0, w, 0, 0, w, h);
            argb.recycle();

            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                rgba[i * 4]     = (byte)((p >> 16) & 0xFF);
                rgba[i * 4 + 1] = (byte)((p >> 8) & 0xFF);
                rgba[i * 4 + 2] = (byte)(p & 0xFF);
                rgba[i * 4 + 3] = (byte)((p >> 24) & 0xFF);
            }

            boolean ok = nativeAddCalibrationFrame(rgba, w, h);
            if (ok) {
                calibrationFrameCount++;
                return calibrationFrameCount;
            }
            return -1;
        } catch (Exception e) {
            android.util.Log.e("BioVault", "addCalibrationFrameFromYUV error: " + e.getMessage());
            return -1;
        }
    }

    @ReactMethod
    public void getCalibrationFrameCount(Promise promise) {
        promise.resolve(calibrationFrameCount);
    }

    /**
     * Finalize PRNU calibration after >=50 frames have been added.
     * On success, caches the BLAKE3 hardware fingerprint.
     */
    @ReactMethod
    public void finalizeCalibration(Promise promise) {
        try {
            String resultJson = nativeFinalizeCalibration();
            // Cache the fingerprint if present
            if (resultJson != null && resultJson.contains("hardwareFingerprint")) {
                try {
                    org.json.JSONObject json = new org.json.JSONObject(resultJson);
                    if (json.has("hardwareFingerprint")) {
                        cachedPRNUFingerprint = json.getString("hardwareFingerprint");
                        android.util.Log.i("BioVault", "PRNU fingerprint cached: " + cachedPRNUFingerprint);
                        // Persist to SharedPreferences so it survives app restart
                        android.content.SharedPreferences prefs = reactContext.getSharedPreferences("biovault_prnu", Context.MODE_PRIVATE);
                        prefs.edit().putString("fingerprint", cachedPRNUFingerprint).apply();
                    }
                } catch (Exception parseErr) {
                    android.util.Log.w("BioVault", "Failed to parse calibration result", parseErr);
                }
            }
            calibrationFrameCount = 0;
            promise.resolve(resultJson);
        } catch (Exception e) {
            promise.reject("CALIBRATE_ERROR", e.getMessage());
        }
    }

    /**
     * Get StrongBox/TEE availability status.
     */
    @ReactMethod
    public void getStrongBoxStatus(Promise promise) {
        try {
            WritableMap status = Arguments.createMap();
            status.putBoolean("isAvailable", strongBoxManager.isStrongBoxSupported());
            String level = strongBoxManager.isStrongBoxSupported() ? "StrongBox" : "TEE";
            Boolean inStrongBox = strongBoxManager.isKeyInStrongBox();
            if (inStrongBox != null) {
                level = inStrongBox ? "StrongBox" : "TEE";
            }
            status.putString("level", level);
            promise.resolve(status);
        } catch (Exception e) {
            WritableMap fallback = Arguments.createMap();
            fallback.putBoolean("isAvailable", false);
            fallback.putString("level", "unknown");
            promise.resolve(fallback);
        }
    }

    /**
     * Check if a reality key has been generated in StrongBox.
     */
    @ReactMethod
    public void hasRealityKey(Promise promise) {
        try {
            boolean hasKey = strongBoxManager.hasRealityKey();
            promise.resolve(hasKey);
        } catch (Exception e) {
            promise.resolve(false);
        }
    }

    /**
     * Get bio-signature for a given BPM (StrongBox-signed).
     */
    @ReactMethod
    public void getBioSignature(int bpm, Promise promise) {
        try {
            String data = bpm + ":" + System.currentTimeMillis();
            byte[] dataHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes("UTF-8"));
            byte[] signature = strongBoxManager.signHash(dataHash);
            String encoded = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP);
            promise.resolve(encoded);
        } catch (Exception e) {
            promise.resolve("bpm:" + bpm + ":unsigned");
        }
    }

    // ============================================
    // BLE Consent Protocol
    // ============================================

    private ConsentBroadcaster consentBroadcaster;

    private ConsentBroadcaster getOrCreateBroadcaster() {
        if (consentBroadcaster == null) {
            consentBroadcaster = new ConsentBroadcaster(reactContext);
        }
        return consentBroadcaster;
    }

    /**
     * START CONSENT REQUEST — called by the recording device when sensitive
     * content is detected.  Advertises via BLE and opens GATT server to
     * receive approval/denial from nearby BioVault devices.
     */
    @ReactMethod
    public void startBLEConsentSession(String sessionId, String category, Promise promise) {
        try {
            ConsentBroadcaster cb = getOrCreateBroadcaster();

            cb.startConsentRequest(sessionId, category,
                new ConsentBroadcaster.ConsentRequesterCallback() {
                    @Override
                    public void onApprovalReceived(String deviceAddress,
                                                   ConsentBroadcaster.ApprovalData approval) {
                        WritableMap result = Arguments.createMap();
                        result.putBoolean("approved", true);
                        result.putString("deviceAddress", deviceAddress);
                        result.putString("sessionId", sessionId);
                        reactContext
                            .getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                            .emit("onConsentApprovalReceived", result);
                    }

                    @Override
                    public void onDenialReceived(String deviceAddress) {
                        WritableMap result = Arguments.createMap();
                        result.putBoolean("approved", false);
                        result.putString("deviceAddress", deviceAddress);
                        result.putString("sessionId", sessionId);
                        reactContext
                            .getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                            .emit("onConsentApprovalReceived", result);
                    }

                    @Override
                    public void onRequestTimeout(int approvalsReceived) {
                        WritableMap result = Arguments.createMap();
                        result.putBoolean("timeout", true);
                        result.putInt("approvalsReceived", approvalsReceived);
                        result.putString("sessionId", sessionId);
                        reactContext
                            .getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                            .emit("onConsentApprovalReceived", result);
                    }
                });

            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("BLE_ERROR", e.getMessage());
        }
    }

    /**
     * STOP CONSENT REQUEST — stop advertising + close GATT server.
     */
    @ReactMethod
    public void stopBLEConsentSession(Promise promise) {
        try {
            if (consentBroadcaster != null) {
                consentBroadcaster.stopConsentRequest();
            }
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("BLE_ERROR", e.getMessage());
        }
    }

    /**
     * START PASSIVE CONSENT SCANNING — called when the camera screen mounts.
     * Listens for consent request advertisements from nearby recording devices.
     * When one is found, emits 'onConsentRequestReceived' to JS.
     */
    @ReactMethod
    public void startPassiveConsentScan(Promise promise) {
        try {
            android.util.Log.i("BioVault", "startPassiveConsentScan called — initializing passive BLE scan");
            ConsentBroadcaster cb = getOrCreateBroadcaster();

            cb.startPassiveScanning(
                new ConsentBroadcaster.ConsentListenerCallback() {
                    @Override
                    public void onConsentRequestDiscovered(String deviceAddress,
                                                           String sessionId,
                                                           String category) {
                        android.util.Log.i("BioVault", "onConsentRequestDiscovered: addr=" + deviceAddress
                            + " session=" + sessionId + " category=" + category);
                        WritableMap event = Arguments.createMap();
                        event.putString("deviceAddress", deviceAddress);
                        event.putString("sessionId", sessionId);
                        event.putString("category", category);
                        reactContext
                            .getJSModule(com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                            .emit("onConsentRequestReceived", event);
                    }
                });

            android.util.Log.i("BioVault", "startPassiveConsentScan: passive scanning initiated OK");
            promise.resolve(true);
        } catch (Exception e) {
            android.util.Log.e("BioVault", "startPassiveConsentScan FAILED: " + e.getMessage());
            promise.reject("BLE_ERROR", e.getMessage());
        }
    }

    /**
     * STOP PASSIVE CONSENT SCANNING.
     */
    @ReactMethod
    public void stopPassiveConsentScan(Promise promise) {
        try {
            if (consentBroadcaster != null) {
                consentBroadcaster.stopPassiveScanning();
            }
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("BLE_ERROR", e.getMessage());
        }
    }

    /**
     * RESPOND TO CONSENT REQUEST — called on the nearby device when user
     * taps Approve or Deny.  Advertises the approval/denial response so the
     * requester can pick it up via scanning.
     */
    @ReactMethod
    public void respondToConsentRequest(String sessionId, boolean approved, Promise promise) {
        try {
            ConsentBroadcaster cb = getOrCreateBroadcaster();

            cb.respondToConsentRequest(sessionId, approved, (success) -> {
                if (success) {
                    promise.resolve(true);
                } else {
                    promise.reject("BLE_ERROR", "Failed to send consent response");
                }
                return kotlin.Unit.INSTANCE;
            });
        } catch (Exception e) {
            promise.reject("BLE_ERROR", e.getMessage());
        }
    }

    /**
     * Get the last camera frame captured during recording as base64 JPEG.
     */
    @ReactMethod
    public void captureLastFrame(Promise promise) {
        if (lastFrameBase64 != null && !lastFrameBase64.isEmpty()) {
            promise.resolve(lastFrameBase64);
        } else {
            promise.reject("CAPTURE_ERROR", "No frame available");
        }
    }

    /**
     * Classify the current camera frame mid-recording.
     * Returns JSON: { category, requiresConsent, label, confidence }
     */
    @ReactMethod
    public void classifyCurrentFrame(Promise promise) {
        try {
            if (lastFrameBase64 == null || lastFrameBase64.isEmpty()) {
                org.json.JSONObject r = new org.json.JSONObject();
                r.put("category", "SAFE");
                r.put("requiresConsent", false);
                r.put("label", "no_frame");
                r.put("confidence", 0);
                promise.resolve(r.toString());
                return;
            }
            // Decode and classify on a background thread
            new Thread(() -> {
                try {
                    byte[] frameBytes = android.util.Base64.decode(lastFrameBase64, android.util.Base64.DEFAULT);
                    Bitmap frameBmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.length);
                    if (frameBmp == null) {
                        org.json.JSONObject r = new org.json.JSONObject();
                        r.put("category", "SAFE");
                        r.put("requiresConsent", false);
                        r.put("label", "decode_error");
                        r.put("confidence", 0);
                        promise.resolve(r.toString());
                        return;
                    }
                    ContentClassifier.ClassificationResult cr = ContentClassifier.classify(frameBmp);
                    frameBmp.recycle();
                    org.json.JSONObject r = new org.json.JSONObject();
                    r.put("category", cr.category);
                    r.put("requiresConsent", cr.requiresConsent);
                    r.put("label", cr.topLabel);
                    r.put("confidence", Math.round(cr.topConfidence * 100));
                    promise.resolve(r.toString());
                } catch (Exception e) {
                    promise.reject("CLASSIFY_ERROR", e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            promise.reject("CLASSIFY_ERROR", e.getMessage());
        }
    }

    // ============================================
    // DWT+DCT+SVD Watermark (Phase 2)
    // ============================================

    /**
     * Embed invisible watermark into an image.
     * @param imageBase64 Base64-encoded JPEG/PNG image
     * @param metadataJson JSON payload to embed (max ~60 chars)
     * Returns base64-encoded watermarked PNG image.
     */
    @ReactMethod
    public void embedWatermark(String imageBase64, String metadataJson, Promise promise) {
        try {
            byte[] imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bmp == null) {
                promise.reject("WM_ERROR", "Failed to decode image");
                return;
            }

            // Convert to ARGB_8888 → extract RGBA bytes
            Bitmap argb = bmp.copy(Bitmap.Config.ARGB_8888, false);
            bmp.recycle();
            int w = argb.getWidth();
            int h = argb.getHeight();
            int[] pixels = new int[w * h];
            argb.getPixels(pixels, 0, w, 0, 0, w, h);
            argb.recycle();

            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                rgba[i * 4]     = (byte)((p >> 16) & 0xFF); // R
                rgba[i * 4 + 1] = (byte)((p >> 8)  & 0xFF); // G
                rgba[i * 4 + 2] = (byte)(p & 0xFF);          // B
                rgba[i * 4 + 3] = (byte)((p >> 24) & 0xFF); // A
            }

            // Call C++ watermark embed
            byte[] outRgba = nativeEmbedWatermark(rgba, w, h, metadataJson);
            if (outRgba == null) {
                promise.reject("WM_ERROR", "Native embed failed");
                return;
            }

            // RGBA bytes → Bitmap → PNG → Base64
            int[] outPixels = new int[w * h];
            for (int i = 0; i < outPixels.length; i++) {
                int r = outRgba[i * 4]     & 0xFF;
                int g = outRgba[i * 4 + 1] & 0xFF;
                int b = outRgba[i * 4 + 2] & 0xFF;
                int a = outRgba[i * 4 + 3] & 0xFF;
                outPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            Bitmap outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            outBmp.setPixels(outPixels, 0, w, 0, 0, w, h);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            outBmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
            outBmp.recycle();

            String outBase64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP);
            promise.resolve(outBase64);
        } catch (Exception e) {
            promise.reject("WM_ERROR", e.getMessage());
        }
    }

    /**
     * Extract watermark from an image.
     * @param imageBase64 Base64-encoded JPEG/PNG image
     * Returns the embedded JSON metadata or null.
     */
    @ReactMethod
    public void extractWatermark(String imageBase64, Promise promise) {
        try {
            byte[] imageBytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            if (bmp == null) {
                promise.reject("WM_ERROR", "Failed to decode image");
                return;
            }

            Bitmap argb = bmp.copy(Bitmap.Config.ARGB_8888, false);
            bmp.recycle();
            int w = argb.getWidth();
            int h = argb.getHeight();
            int[] pixels = new int[w * h];
            argb.getPixels(pixels, 0, w, 0, 0, w, h);
            argb.recycle();

            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                rgba[i * 4]     = (byte)((p >> 16) & 0xFF);
                rgba[i * 4 + 1] = (byte)((p >> 8)  & 0xFF);
                rgba[i * 4 + 2] = (byte)(p & 0xFF);
                rgba[i * 4 + 3] = (byte)((p >> 24) & 0xFF);
            }

            String decoded = nativeExtractWatermark(rgba, w, h);
            if (decoded != null && !decoded.isEmpty()) {
                promise.resolve(decoded);
            } else {
                promise.resolve(null);
            }
        } catch (Exception e) {
            promise.reject("WM_ERROR", e.getMessage());
        }
    }
}
