package com.biovault.sdk;

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
    private static final int BUFFER_SIZE = 10;
    private static final int FRAME_SIZE = 72;
    private static final int FRAME_DECIMATION = 2;
    private static final int INFERENCE_INTERVAL = 5;
    private static final int BVP_HISTORY_SIZE = 180;
    private static final int BPM_HISTORY_SIZE = 5;
    private static final float CAMERA_FPS = 30.0f;
    private static final float MAX_BPM_CHANGE_PER_SEC = 60.0f;
    
    // FFT spectral analysis parameters
    private static final float HR_LOW_HZ = 0.7f;
    private static final float HR_HIGH_HZ = 3.0f;
    
    private Module model;
    private boolean modelReady = false;
    
    // Frame buffer - store raw [0,255] pixel data
    private ArrayBlockingQueue<float[]> frameQueue;
    
    // BPM temporal smoothing
    private ArrayBlockingQueue<Float> bpmHistory;
    private float lastBPM = 70.0f;
    private long lastBPMTime = 0;
    
    // BVP accumulator
    private ArrayBlockingQueue<Float> bvpHistory;
    private long lastBvpBatchTime = 0;
    
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
            String modelPath = assetFilePath(context, "tscan.ptl");
            model = LiteModuleLoader.load(modelPath);
            modelReady = true;
            
            frameQueue = new ArrayBlockingQueue<>(BUFFER_SIZE);
            bvpHistory = new ArrayBlockingQueue<>(BVP_HISTORY_SIZE);
            bpmHistory = new ArrayBlockingQueue<>(BPM_HISTORY_SIZE);
            
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
    
    public void addFrame(Bitmap frame) {
        if (!modelReady) return;
        
        frameCounter++;
        
        if (frameCounter % FRAME_DECIMATION != 0) {
            return;
        }
        
        try {
            Bitmap resized = Bitmap.createScaledBitmap(frame, FRAME_SIZE, FRAME_SIZE, false);
            float[] rawFrame = bitmapToRawFloatArray(resized);
            resized.recycle();
            
            if (!frameQueue.offer(rawFrame)) {
                frameQueue.poll();
                frameQueue.offer(rawFrame);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
        }
    }
    
    public synchronized InferenceResult getCurrentBPM() {
        if (frameQueue.size() < BUFFER_SIZE) {
            if (lastResult != null) {
                return lastResult;
            }
            return new InferenceResult(70.0f, 0.0f, 0, false);
        }
        
        if (currentInference != null) {
            if (currentInference.isDone()) {
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
                if (lastResult != null) {
                    return lastResult;
                }
            }
        }
        
        inferenceCounter++;
        if (currentInference == null && inferenceCounter % INFERENCE_INTERVAL == 0) {
            startInferenceAsync();
        }
        
        if (lastResult != null) {
            if (System.currentTimeMillis() - lastInferenceTime < 10000) {
                return lastResult;
            }
        }
        
        return new InferenceResult(70.0f, 0.1f, 0, false);
    }
    
    private void startInferenceAsync() {
        final float[][] rawFrames = frameQueue.toArray(new float[0][]);
        
        if (rawFrames.length < BUFFER_SIZE) {
            return;
        }
        
        currentInference = inferenceExecutor.submit(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                int pixelsPerFrame = 3 * FRAME_SIZE * FRAME_SIZE;
                
                // === 1. DiffNormalized branch ===
                int numDiffs = BUFFER_SIZE - 1;
                float[][] diffFrames = new float[numDiffs][pixelsPerFrame];
                
                for (int j = 0; j < numDiffs; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        float next = rawFrames[j + 1][i];
                        float curr = rawFrames[j][i];
                        diffFrames[j][i] = (next - curr) / (next + curr + 1e-7f);
                    }
                }
                
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
                if (diffStd < 1e-7f) diffStd = 1.0f;
                
                for (int j = 0; j < numDiffs; j++) {
                    for (int i = 0; i < pixelsPerFrame; i++) {
                        diffFrames[j][i] /= diffStd;
                        if (Float.isNaN(diffFrames[j][i])) {
                            diffFrames[j][i] = 0f;
                        }
                    }
                }
                float[] zeroPadding = new float[pixelsPerFrame];
                
                // === 2. Standardized branch ===
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
                int totalElements = BUFFER_SIZE * 6 * FRAME_SIZE * FRAME_SIZE;
                float[] inputData = new float[totalElements];
                
                for (int t = 0; t < BUFFER_SIZE; t++) {
                    int frameOffset = t * 6 * FRAME_SIZE * FRAME_SIZE;
                    
                    float[] diff = (t < numDiffs) ? diffFrames[t] : zeroPadding;
                    System.arraycopy(diff, 0, inputData, frameOffset, pixelsPerFrame);
                    System.arraycopy(stdFrames[t], 0, inputData, frameOffset + pixelsPerFrame, pixelsPerFrame);
                }
                
                Log.d(TAG, String.format("Preprocessing: diffStd=%.4f, rawMean=%.1f, rawStd=%.1f",
                    diffStd, rawMeanF, rawStd));
                
                Tensor inputTensor = Tensor.fromBlob(
                    inputData,
                    new long[]{BUFFER_SIZE, 6, FRAME_SIZE, FRAME_SIZE}
                );
                
                IValue output = model.forward(IValue.from(inputTensor));
                Tensor outputTensor = output.toTensor();
                float[] bvp = outputTensor.getDataAsFloatArray();
                
                // Detect scan restart
                long now = System.currentTimeMillis();
                if (lastBvpBatchTime > 0 && (now - lastBvpBatchTime) > 3000) {
                    bvpHistory.clear();
                    bpmHistory.clear();
                    lastBPM = 70.0f;
                    Log.i(TAG, "Scan restart detected - clearing BVP history");
                }
                lastBvpBatchTime = now;
                
                for (float value : bvp) {
                    if (!bvpHistory.offer(value)) {
                        bvpHistory.poll();
                        bvpHistory.offer(value);
                    }
                }
                
                float bpm;
                float confidence;
                if (bvpHistory.size() >= 30) {
                    Float[] historyArray = bvpHistory.toArray(new Float[0]);
                    float[] bvpSignal = new float[historyArray.length];
                    for (int i = 0; i < historyArray.length; i++) {
                        bvpSignal[i] = historyArray[i];
                    }
                    
                    float effectiveSampleRate = 10.0f;
                    float[] fftResult = calculateBPMFromFFT(bvpSignal, effectiveSampleRate);
                    bpm = fftResult[0];
                    confidence = fftResult[1];
                } else {
                    bpm = 70.0f;
                    confidence = 0.1f;
                }
                
                bpm = Math.max(45.0f, Math.min(180.0f, bpm));
                
                long inferenceTime = System.currentTimeMillis() - startTime;
                
                return new InferenceResult(bpm, confidence, inferenceTime, true);
                
            } catch (Exception e) {
                Log.e(TAG, "Inference failed", e);
                return new InferenceResult(70.0f, 0.0f, 0, false);
            }
        });
    }
    
    private float[] calculateBPMFromFFT(float[] bvp, float sampleRate) {
        if (bvp == null || bvp.length < 20) {
            return new float[]{70.0f, 0.0f};
        }
        
        int n = bvp.length;
        
        float mean = 0f;
        for (float v : bvp) mean += v;
        mean /= n;
        
        int fftSize = 1;
        while (fftSize < n * 2) fftSize <<= 1;
        
        float[] re = new float[fftSize];
        float[] im = new float[fftSize];
        
        for (int i = 0; i < n; i++) {
            float window = 0.5f * (1.0f - (float)Math.cos(2.0 * Math.PI * i / (n - 1)));
            re[i] = (bvp[i] - mean) * window;
        }
        
        fft(re, im, fftSize);
        
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
        
        float confidence = (totalPower > 0) ? maxPower / totalPower : 0f;
        confidence = Math.min(1.0f, confidence * 3.0f);
        
        float smoothedBPM = applyTemporalSmoothing(rawBPM);
        
        Log.d(TAG, String.format("FFT BPM: %.1f->%.1f (peak=%.2fHz, SNR=%.2f, fs=%.1f, N=%d, fftN=%d)",
            rawBPM, smoothedBPM, peakFreq, confidence, sampleRate, n, fftSize));
        
        return new float[]{smoothedBPM, confidence};
    }
    
    private void fft(float[] re, float[] im, int n) {
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
    
    private float applyTemporalSmoothing(float rawBPM) {
        long currentTime = System.currentTimeMillis();
        
        if (bpmHistory.remainingCapacity() == 0) {
            bpmHistory.poll();
        }
        bpmHistory.offer(rawBPM);
        
        if (bpmHistory.size() < 3) {
            lastBPM = rawBPM;
            lastBPMTime = currentTime;
            return rawBPM;
        }
        
        Float[] values = bpmHistory.toArray(new Float[0]);
        java.util.Arrays.sort(values);
        float medianBPM = values[values.length / 2];
        
        if (lastBPMTime > 0) {
            float elapsedSeconds = (currentTime - lastBPMTime) / 1000.0f;
            float maxChange = MAX_BPM_CHANGE_PER_SEC * elapsedSeconds;
            float actualChange = medianBPM - lastBPM;
            
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
    
    private float[] bitmapToRawFloatArray(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        float[] floatArray = new float[3 * width * height];
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            floatArray[i] = (float) ((pixel >> 16) & 0xFF);
            floatArray[pixels.length + i] = (float) ((pixel >> 8) & 0xFF);
            floatArray[2 * pixels.length + i] = (float) (pixel & 0xFF);
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
    
    public InferenceResult getAccumulatedBPM() {
        if (bpmHistory == null || bpmHistory.isEmpty()) {
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
