package com.biovault;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.*;

/**
 * TS-CAN (Temporal Shift Convolutional Attention Network) for mobile rPPG
 * 
 * Key advantages over PhysNet:
 * - Only 10 frames needed (vs 128 for PhysNet)
 * - 72x72 resolution (vs 128x128)
 * - ~200ms inference (vs 7000ms)
 * - 31x faster!
 * 
 * Architecture: Dual-branch (Motion + Appearance) with temporal shift and attention
 */
public class TSCANInference {
    private static final String TAG = "TS-CAN";
    
    // TS-CAN configuration
    private static final int BUFFER_SIZE = 10;  // Only 10 frames per inference
    private static final int FRAME_SIZE = 72;   // 72x72 images
    private static final int FRAME_DECIMATION = 2;  // Process every 2nd frame
    private static final int INFERENCE_INTERVAL = 5;  // Run inference every 5 frames (faster)
    private static final int BVP_HISTORY_SIZE = 180;  // Accumulate 180 BVP values (~6 seconds)
    private static final int BPM_HISTORY_SIZE = 5;  // Keep last 5 BPM estimates for smoothing (was 10)
    private static final float CAMERA_FPS = 30.0f;  // Raw camera frame rate
    private static final float MAX_BPM_CHANGE_PER_SEC = 60.0f;  // Max BPM change rate
    
    // FFT spectral analysis parameters (replaces broken peak detection + bandpass)
    private static final float HR_LOW_HZ = 0.7f;    // 42 BPM minimum
    private static final float HR_HIGH_HZ = 3.0f;   // 180 BPM maximum
    
    // Preprocessing: rPPG-Toolbox exact pipeline
    // DiffNormalized: (frame[t+1] - frame[t]) / (frame[t+1] + frame[t] + 1e-7) / std
    // Standardized:   (frame - mean) / std  (global z-score)
    // Both concatenated to 6 channels: [diff_R, diff_G, diff_B, std_R, std_G, std_B]
    
    private Module model;
    private boolean modelReady = false;
    
    // Frame buffer - store raw [0,255] pixel data, all preprocessing happens at inference time
    // This matches rPPG-Toolbox which normalizes entire video BEFORE chunking
    private ArrayBlockingQueue<float[]> frameQueue;  // Raw [0,255] frames in CHW format
    
    // BPM temporal smoothing
    private ArrayBlockingQueue<Float> bpmHistory;  // Keep recent BPM estimates
    private float lastBPM = 70.0f;  // Last valid BPM for rate limiting
    private long lastBPMTime = 0;  // Timestamp of last BPM update
    
    // BVP accumulator - store BVP values over time for better HR estimation
    private ArrayBlockingQueue<Float> bvpHistory;
    private long lastBvpBatchTime = 0;  // For gap detection (scan restart)
    
    // Threading
    private ExecutorService inferenceExecutor;
    private Future<InferenceResult> currentInference = null;
    
    // Results
    private InferenceResult lastResult = null;
    private long lastInferenceTime = 0;
    
    // Counters
    private int frameCounter = 0;
    private int inferenceCounter = 0;
    
    public static class InferenceResult {
        public final float bpm;
        public final float confidence;
        public final long inferenceTimeMs;
        public final boolean isValid;
        
        public InferenceResult(float bpm, float confidence, long inferenceTimeMs, boolean isValid) {
            this.bpm = bpm;
            this.confidence = confidence;
            this.inferenceTimeMs = inferenceTimeMs;
            this.isValid = isValid;
        }
    }
    
    public TSCANInference(Context context) {
        try {
            // Load TS-CAN model from assets
            String modelPath = assetFilePath(context, "tscan.ptl");
            model = LiteModuleLoader.load(modelPath);  // Use LiteModuleLoader for PyTorch Lite
            modelReady = true;
            
            // Initialize frame queue (stores raw [0,255] frames)
            frameQueue = new ArrayBlockingQueue<>(BUFFER_SIZE);
            
            // Initialize BVP history accumulator
            bvpHistory = new ArrayBlockingQueue<>(BVP_HISTORY_SIZE);
            
            // Initialize BPM history for temporal smoothing
            bpmHistory = new ArrayBlockingQueue<>(BPM_HISTORY_SIZE);
            
            // Create high-priority executor for inference
            inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
            
            Log.i(TAG, "\u2713 TS-CAN rPPG initialized (SCAMPS weights)");
            Log.i(TAG, "  Frames: " + BUFFER_SIZE + " @ " + FRAME_SIZE + "x" + FRAME_SIZE);
            Log.i(TAG, "  Decimation: 1/" + FRAME_DECIMATION + " frames");
            Log.i(TAG, "  BVP accumulation: " + BVP_HISTORY_SIZE + " values (~6 seconds)");
            Log.i(TAG, "  BPM smoothing: " + BPM_HISTORY_SIZE + " estimates");
            Log.i(TAG, "  Preprocessing: DiffNormalized + Standardized (rPPG-Toolbox exact)");
            Log.i(TAG, "  BPM extraction: FFT spectral analysis (" + HR_LOW_HZ + "-" + HR_HIGH_HZ + " Hz)");
            Log.i(TAG, "  Dual-branch: Motion + Appearance");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load TS-CAN model", e);
            modelReady = false;
        }
    }
    
    public boolean isReady() {
        return modelReady && model != null;
    }
    
    /**
     * Add a frame to the buffer.
     * Only stores raw [0,255] pixel data — all preprocessing (DiffNormalized + Standardized)
     * happens at inference time over the full chunk, matching rPPG-Toolbox training pipeline.
     */
    public void addFrame(Bitmap frame) {
        if (!modelReady) return;
        
        frameCounter++;
        
        // DECIMATION: Only process every Nth frame
        if (frameCounter % FRAME_DECIMATION != 0) {
            return;
        }
        
        try {
            // Resize to 72x72
            Bitmap resized = Bitmap.createScaledBitmap(frame, FRAME_SIZE, FRAME_SIZE, false);
            
            // Get raw pixels as float [0, 255] in CHW format
            float[] rawFrame = bitmapToRawFloatArray(resized);
            resized.recycle();
            
            // Store raw frame (drop oldest if full)
            if (!frameQueue.offer(rawFrame)) {
                frameQueue.poll();
                frameQueue.offer(rawFrame);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
        }
    }
    
    /**
     * Get current BPM (runs inference if needed, otherwise returns cached)
     */
    public synchronized InferenceResult getCurrentBPM() {
        // Check if we have enough frames
        if (frameQueue.size() < BUFFER_SIZE) {
            if (lastResult != null) {
                return lastResult;
            }
            return new InferenceResult(70.0f, 0.0f, 0, false);
        }
        
        // Check if inference is running
        if (currentInference != null) {
            if (currentInference.isDone()) {
                // Inference complete
                try {
                    lastResult = currentInference.get();
                    lastInferenceTime = System.currentTimeMillis();
                    currentInference = null;
                    Log.i(TAG, String.format("TS-CAN result: BPM=%.1f, quality=%.2f, time=%dms",
                        lastResult.bpm, lastResult.confidence, lastResult.inferenceTimeMs));
                    return lastResult;
                } catch (Exception e) {
                    Log.e(TAG, "Inference error", e);
                    currentInference = null;
                }
            } else {
                // Inference still running - return cached
                if (lastResult != null) {
                    return lastResult;
                }
            }
        }
        
        // Start new inference if interval reached
        inferenceCounter++;
        if (currentInference == null && inferenceCounter % INFERENCE_INTERVAL == 0) {
            startInferenceAsync();
        }
        
        // Return cached result or default
        if (lastResult != null) {
            if (System.currentTimeMillis() - lastInferenceTime < 10000) {
                return lastResult;
            }
        }
        
        return new InferenceResult(70.0f, 0.1f, 0, false);
    }
    
    private void startInferenceAsync() {
        // Copy raw frames for async processing
        final float[][] rawFrames = frameQueue.toArray(new float[0][]);
        
        if (rawFrames.length < BUFFER_SIZE) {
            return;
        }
        
        currentInference = inferenceExecutor.submit(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                int pixelsPerFrame = 3 * FRAME_SIZE * FRAME_SIZE;
                
                // ============================================================
                // rPPG-Toolbox exact preprocessing (chunk-level normalization)
                // ============================================================
                
                // === 1. DiffNormalized branch ===
                // diff[j] = (frame[j+1] - frame[j]) / (frame[j+1] + frame[j] + 1e-7)
                // Then normalize ALL diffs by global std, append zero-padding
                
                // Compute 9 valid diff frames (for 10 input frames)
                int numDiffs = BUFFER_SIZE - 1;  // 9
                float[][] diffFrames = new float[numDiffs][pixelsPerFrame];
                
                for (int j = 0; j < numDiffs; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        float next = rawFrames[j + 1][i];
                        float curr = rawFrames[j][i];
                        diffFrames[j][i] = (next - curr) / (next + curr + 1e-7f);
                    }
                }
                
                // Compute global std across ALL 9 diff frames
                double diffSum = 0, diffSumSq = 0;
                long diffCount = (long) numDiffs * pixelsPerFrame;
                for (int j = 0; j < numDiffs; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        double v = diffFrames[j][i];
                        diffSum += v;
                        diffSumSq += v * v;
                    }
                }
                double diffMean = diffSum / diffCount;
                float diffStd = (float) Math.sqrt(diffSumSq / diffCount - diffMean * diffMean);
                if (diffStd < 1e-7f) diffStd = 1.0f;  // Prevent division by zero
                
                // Normalize all diff frames by global std
                for (int j = 0; j < numDiffs; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        diffFrames[j][i] /= diffStd;
                        // Replace NaN with 0
                        if (Float.isNaN(diffFrames[j][i])) {
                            diffFrames[j][i] = 0f;
                        }
                    }
                }
                // Last diff frame (index 9) = zeros (appended AFTER normalization per rPPG-Toolbox)
                float[] zeroPadding = new float[pixelsPerFrame];  // initialized to 0
                
                // === 2. Standardized branch ===
                // Global z-score: (pixel - global_mean) / global_std across ALL 10 frames
                
                // Compute global mean and std across all raw frames
                double rawSum = 0, rawSumSq = 0;
                long rawCount = (long) BUFFER_SIZE * pixelsPerFrame;
                for (int j = 0; j < BUFFER_SIZE; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        double v = rawFrames[j][i];
                        rawSum += v;
                        rawSumSq += v * v;
                    }
                }
                double rawMean = rawSum / rawCount;
                float rawStd = (float) Math.sqrt(rawSumSq / rawCount - rawMean * rawMean);
                if (rawStd < 1e-7f) rawStd = 1.0f;
                float rawMeanF = (float) rawMean;
                
                // Compute standardized frames
                float[][] stdFrames = new float[BUFFER_SIZE][pixelsPerFrame];
                for (int j = 0; j < BUFFER_SIZE; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        stdFrames[j][i] = (rawFrames[j][i] - rawMeanF) / rawStd;
                        if (Float.isNaN(stdFrames[j][i])) {
                            stdFrames[j][i] = 0f;
                        }
                    }
                }
                
                // === 3. Assemble 6-channel tensor [T, 6, H, W] ===
                // Channels 0-2: DiffNormalized (R,G,B)
                // Channels 3-5: Standardized (R,G,B)
                
                int totalElements = BUFFER_SIZE * 6 * FRAME_SIZE * FRAME_SIZE;
                float[] inputData = new float[totalElements];
                
                for (int t = 0; t < BUFFER_SIZE; t++) {
                    int frameOffset = t * 6 * FRAME_SIZE * FRAME_SIZE;
                    
                    // Diff channels (0-2): use computed diff or zero padding for last frame
                    float[] diff = (t < numDiffs) ? diffFrames[t] : zeroPadding;
                    System.arraycopy(diff, 0, inputData, frameOffset, pixelsPerFrame);
                    
                    // Standardized channels (3-5)
                    System.arraycopy(stdFrames[t], 0, inputData, frameOffset + pixelsPerFrame, pixelsPerFrame);
                }
                
                Log.d(TAG, String.format("Preprocessing: diffStd=%.4f, rawMean=%.1f, rawStd=%.1f",
                    diffStd, rawMeanF, rawStd));
                
                // Create tensor [B*T, C, H, W] = [10, 6, 72, 72]
                Tensor inputTensor = Tensor.fromBlob(
                    inputData,
                    new long[]{BUFFER_SIZE, 6, FRAME_SIZE, FRAME_SIZE}
                );
                
                // Run inference
                IValue output = model.forward(IValue.from(inputTensor));
                Tensor outputTensor = output.toTensor();
                float[] bvp = outputTensor.getDataAsFloatArray();
                
                // Detect scan restart (gap > 3 seconds = user navigated away)
                long now = System.currentTimeMillis();
                if (lastBvpBatchTime > 0 && (now - lastBvpBatchTime) > 3000) {
                    bvpHistory.clear();
                    bpmHistory.clear();
                    lastBPM = 70.0f;
                    Log.i(TAG, "Scan restart detected - clearing BVP history");
                }
                lastBvpBatchTime = now;
                
                // Add BVP values to history accumulator
                for (float value : bvp) {
                    if (!bvpHistory.offer(value)) {
                        bvpHistory.poll();  // Remove oldest
                        bvpHistory.offer(value);
                    }
                }
                
                // Calculate BPM from accumulated BVP history using FFT
                float bpm;
                float confidence;
                if (bvpHistory.size() >= 30) {
                    Float[] historyArray = bvpHistory.toArray(new Float[0]);
                    float[] bvpSignal = new float[historyArray.length];
                    for (int i = 0; i < historyArray.length; i++) {
                        bvpSignal[i] = historyArray[i];
                    }
                    
                    // Fixed effective sample rate:
                    // Each inference = 10 BVP values, runs every ~1s
                    // = 10 values/sec = 10 Hz (architecture constant)
                    float effectiveSampleRate = 10.0f;
                    
                    float[] fftResult = calculateBPMFromFFT(bvpSignal, effectiveSampleRate);
                    bpm = fftResult[0];
                    confidence = fftResult[1];
                } else {
                    // Not enough data yet
                    bpm = 70.0f;
                    confidence = 0.1f;
                }
                
                // Clamp BPM to physiological range
                bpm = Math.max(45.0f, Math.min(180.0f, bpm));
                
                long inferenceTime = System.currentTimeMillis() - startTime;
                
                return new InferenceResult(bpm, confidence, inferenceTime, true);
                
            } catch (Exception e) {
                Log.e(TAG, "Inference failed", e);
                return new InferenceResult(70.0f, 0.0f, 0, false);
            }
        });
    }
    
    /**
     * Calculate BPM from BVP signal using FFT spectral analysis.
     * This is the standard method used in rPPG research — much more robust
     * than peak detection for noisy neural network outputs.
     * 
     * @return float[2]: [bpm, confidence]
     */
    private float[] calculateBPMFromFFT(float[] bvp, float sampleRate) {
        if (bvp == null || bvp.length < 20) {
            return new float[]{70.0f, 0.0f};
        }
        
        int n = bvp.length;
        
        // 1. Detrend (remove DC offset)
        float mean = 0f;
        for (float v : bvp) mean += v;
        mean /= n;
        
        // 2. Zero-pad to next power of 2 for FFT
        int fftSize = 1;
        while (fftSize < n * 2) fftSize <<= 1;  // 2x oversampling for better freq resolution
        
        float[] re = new float[fftSize];
        float[] im = new float[fftSize];
        
        // 3. Apply Hanning window + detrend
        for (int i = 0; i < n; i++) {
            float window = 0.5f * (1.0f - (float)Math.cos(2.0 * Math.PI * i / (n - 1)));
            re[i] = (bvp[i] - mean) * window;
        }
        // Remaining re[n..fftSize-1] = 0 (zero padding)
        
        // 4. In-place Cooley-Tukey FFT
        fft(re, im, fftSize);
        
        // 5. Compute power spectrum and find peak in HR range
        float freqResolution = sampleRate / fftSize;
        int lowBin = Math.max(1, (int)Math.ceil(HR_LOW_HZ / freqResolution));
        int highBin = Math.min(fftSize / 2, (int)Math.floor(HR_HIGH_HZ / freqResolution));
        
        float maxPower = 0f;
        int peakBin = lowBin;
        float totalPower = 0f;
        
        for (int k = lowBin; k <= highBin; k++) {
            float power = re[k] * re[k] + im[k] * im[k];
            totalPower += power;
            if (power > maxPower) {
                maxPower = power;
                peakBin = k;
            }
        }
        
        // 6. Parabolic interpolation for sub-bin accuracy
        float peakFreq;
        if (peakBin > lowBin && peakBin < highBin) {
            float pLeft = re[peakBin-1]*re[peakBin-1] + im[peakBin-1]*im[peakBin-1];
            float pCenter = maxPower;
            float pRight = re[peakBin+1]*re[peakBin+1] + im[peakBin+1]*im[peakBin+1];
            float delta = 0.5f * (pLeft - pRight) / (pLeft - 2*pCenter + pRight + 1e-10f);
            peakFreq = (peakBin + delta) * freqResolution;
        } else {
            peakFreq = peakBin * freqResolution;
        }
        
        float rawBPM = peakFreq * 60.0f;
        
        // 7. Confidence = peak power / total power in HR band (spectral SNR)
        float confidence = (totalPower > 0) ? maxPower / totalPower : 0f;
        confidence = Math.min(1.0f, confidence * 3.0f);  // Scale up — a dominant peak gives ~0.3 ratio
        
        // 8. Apply temporal smoothing
        float smoothedBPM = applyTemporalSmoothing(rawBPM);
        
        Log.d(TAG, String.format("FFT BPM: %.1f->%.1f (peak=%.2fHz, SNR=%.2f, fs=%.1f, N=%d, fftN=%d)",
            rawBPM, smoothedBPM, peakFreq, confidence, sampleRate, n, fftSize));
        
        return new float[]{smoothedBPM, confidence};
    }
    
    /**
     * Radix-2 Cooley-Tukey FFT (in-place).
     * n must be a power of 2.
     */
    private void fft(float[] re, float[] im, int n) {
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        // Butterfly operations
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wRe = (float)Math.cos(ang);
            float wIm = (float)Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1f, curIm = 0f;
                for (int j = 0; j < len / 2; j++) {
                    float uRe = re[i+j],             uIm = im[i+j];
                    float vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    float vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i+j]       = uRe + vRe;  im[i+j]       = uIm + vIm;
                    re[i+j+len/2] = uRe - vRe;  im[i+j+len/2] = uIm - vIm;
                    float newCurRe = curRe*wRe - curIm*wIm;
                    curIm = curRe*wIm + curIm*wRe;
                    curRe = newCurRe;
                }
            }
        }
    }
    
    /**
     * Apply temporal smoothing and rate limiting to BPM estimates
     * Uses median filter to reduce noise and enforces maximum change rate
     */
    private float applyTemporalSmoothing(float rawBPM) {
        long currentTime = System.currentTimeMillis();
        
        // Add to history buffer (removes oldest if full)
        if (bpmHistory.remainingCapacity() == 0) {
            bpmHistory.poll();  // Remove oldest
        }
        bpmHistory.offer(rawBPM);
        
        // Need at least 3 values for meaningful smoothing
        if (bpmHistory.size() < 3) {
            lastBPM = rawBPM;
            lastBPMTime = currentTime;
            return rawBPM;
        }
        
        // Calculate median (more robust than mean against outliers)
        Float[] values = bpmHistory.toArray(new Float[0]);
        java.util.Arrays.sort(values);
        float medianBPM = values[values.length / 2];
        
        // Apply rate limiting to prevent unrealistic jumps
        if (lastBPMTime > 0) {
            float elapsedSeconds = (currentTime - lastBPMTime) / 1000.0f;
            float maxChange = MAX_BPM_CHANGE_PER_SEC * elapsedSeconds;
            float actualChange = medianBPM - lastBPM;
            
            // Clamp change to realistic rate
            if (Math.abs(actualChange) > maxChange) {
                medianBPM = lastBPM + Math.signum(actualChange) * maxChange;
                Log.d(TAG, String.format("Rate limited: %.1f -> %.1f (max Δ=%.1f)", 
                    lastBPM, medianBPM, maxChange));
            }
        }
        
        lastBPM = medianBPM;
        lastBPMTime = currentTime;
        
        return medianBPM;
    }
    
    // Signal quality is now computed inside calculateBPMFromFFT as spectral SNR
    
    /**
     * Convert bitmap to raw float array in CHW format [C, H, W]
     * Values in [0, 255] range (NOT normalized) for DiffNormalized computation.
     */
    private float[] bitmapToRawFloatArray(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        float[] floatArray = new float[3 * width * height];
        
        // Convert to CHW format, keep values in [0, 255]
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            floatArray[i] = (float) ((pixel >> 16) & 0xFF);  // R [0,255]
            floatArray[pixels.length + i] = (float) ((pixel >> 8) & 0xFF);  // G [0,255]
            floatArray[2 * pixels.length + i] = (float) (pixel & 0xFF);  // B [0,255]
        }
        
        return floatArray;
    }
    
    private String assetFilePath(Context context, String assetName) throws Exception {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        
        try (InputStream is = context.getAssets().open(assetName)) {
            try (FileOutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }
    
    /**
     * Get accumulated BPM summary for the entire session.
     * Returns the median-smoothed BPM, confidence, and inference count.
     */
    public InferenceResult getAccumulatedBPM() {
        if (bpmHistory == null || bpmHistory.isEmpty()) {
            // Fall back to last inference result
            if (lastResult != null && lastResult.isValid) {
                return lastResult;
            }
            return new InferenceResult(0f, 0f, 0, false);
        }

        Float[] values = bpmHistory.toArray(new Float[0]);
        java.util.Arrays.sort(values);
        float medianBPM = values[values.length / 2];
        float confidence = (lastResult != null) ? lastResult.confidence : 0.5f;

        return new InferenceResult(medianBPM, confidence, 0, true);
    }

    /**
     * Reset session state (call when starting a new recording).
     */
    public void resetSession() {
        if (bvpHistory != null) bvpHistory.clear();
        if (bpmHistory != null) bpmHistory.clear();
        lastBPM = 70.0f;
        lastBPMTime = 0;
        lastResult = null;
        frameCounter = 0;
        inferenceCounter = 0;
        Log.i(TAG, "Session reset");
    }

    public void cleanup() {
        if (inferenceExecutor != null) {
            inferenceExecutor.shutdown();
        }
        if (model != null) {
            model = null;
        }
    }
}
