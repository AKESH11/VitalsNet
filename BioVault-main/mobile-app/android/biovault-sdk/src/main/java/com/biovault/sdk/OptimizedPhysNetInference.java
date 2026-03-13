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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Optimized PhysNet inference with reduced latency.
 * - 64 frames instead of 128 (50% faster)
 * - 96x96 input instead of 128x128 (44% less computation)
 * - Async inference on background thread
 * - Frame decimation (process every 2nd frame)
 * - BPM filtering for stability
 */
public class OptimizedPhysNetInference {
    private static final String TAG = "PhysNetOptimized";
    
    private static final int FRAME_COUNT = 64;
    private static final int FRAME_SIZE = 96;
    private static final int CHANNELS = 3;
    private static final float TARGET_FPS = 10.0f;
    
    private Module module;
    private boolean isLoaded = false;
    
    private final List<float[]> frameBuffer = new ArrayList<>();
    private int frameCounter = 0;
    private int processedFrames = 0;
    
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private Future<PhysNetResult> currentInference = null;
    
    private final List<Float> bpmHistory = new ArrayList<>();
    private static final int BPM_HISTORY_SIZE = 5;
    private float lastValidBPM = 0;
    
    public OptimizedPhysNetInference(Context context) {
        try {
            String modelPath = copyAssetToCache(context, "models/physnet.ptl");
            module = LiteModuleLoader.load(modelPath);
            isLoaded = true;
            Log.i(TAG, "✓ Optimized PhysNet loaded (64 frames, 96x96)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load PhysNet model", e);
            isLoaded = false;
        }
    }
    
    public boolean isModelLoaded() {
        return isLoaded;
    }
    
    public synchronized void addFrame(Bitmap frame) {
        if (!isLoaded || frame == null) return;
        
        frameCounter++;
        
        if (frameCounter % 2 != 0) {
            return;
        }
        
        try {
            Bitmap resized = Bitmap.createScaledBitmap(frame, FRAME_SIZE, FRAME_SIZE, true);
            
            int[] pixels = new int[FRAME_SIZE * FRAME_SIZE];
            resized.getPixels(pixels, 0, FRAME_SIZE, 0, 0, FRAME_SIZE, FRAME_SIZE);
            
            float[] frameData = new float[CHANNELS * FRAME_SIZE * FRAME_SIZE];
            int pixelCount = FRAME_SIZE * FRAME_SIZE;
            
            for (int i = 0; i < pixelCount; i++) {
                int pixel = pixels[i];
                frameData[i] = ((pixel >> 16) & 0xFF) / 255.0f;
                frameData[pixelCount + i] = ((pixel >> 8) & 0xFF) / 255.0f;
                frameData[2 * pixelCount + i] = (pixel & 0xFF) / 255.0f;
            }
            
            frameBuffer.add(frameData);
            
            if (frameBuffer.size() > FRAME_COUNT) {
                frameBuffer.remove(0);
            }
            
            resized.recycle();
            processedFrames++;
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding frame", e);
        }
    }
    
    public boolean hasEnoughFrames() {
        return frameBuffer.size() >= FRAME_COUNT;
    }
    
    public int getFrameCount() {
        return frameBuffer.size();
    }
    
    public synchronized void startInferenceAsync() {
        if (!hasEnoughFrames() || currentInference != null) {
            return;
        }
        
        final List<float[]> bufferCopy = new ArrayList<>(frameBuffer);
        
        currentInference = inferenceExecutor.submit(() -> {
            return runInferenceInternal(bufferCopy);
        });
    }
    
    public synchronized PhysNetResult getResultIfReady() {
        if (currentInference == null) {
            return null;
        }
        
        if (!currentInference.isDone()) {
            return null;
        }
        
        try {
            PhysNetResult result = currentInference.get();
            currentInference = null;
            
            if (result != null && result.heartRate > 40 && result.heartRate < 180) {
                float filteredBPM = filterBPM(result.heartRate);
                result = new PhysNetResult(
                    result.bvpWaveform,
                    filteredBPM,
                    result.signalQuality,
                    result.inferenceTimeMs
                );
                lastValidBPM = filteredBPM;
            }
            
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Inference error", e);
            currentInference = null;
            return null;
        }
    }
    
    private PhysNetResult runInferenceInternal(List<float[]> frames) {
        if (frames.size() < FRAME_COUNT) {
            return null;
        }
        
        long startTime = System.currentTimeMillis();
        
        try {
            int totalSize = CHANNELS * FRAME_COUNT * FRAME_SIZE * FRAME_SIZE;
            float[] inputData = new float[totalSize];
            
            for (int t = 0; t < FRAME_COUNT; t++) {
                float[] frame = frames.get(t);
                for (int c = 0; c < CHANNELS; c++) {
                    int srcOffset = c * FRAME_SIZE * FRAME_SIZE;
                    int dstOffset = c * FRAME_COUNT * FRAME_SIZE * FRAME_SIZE + 
                                   t * FRAME_SIZE * FRAME_SIZE;
                    System.arraycopy(frame, srcOffset, inputData, dstOffset, FRAME_SIZE * FRAME_SIZE);
                }
            }
            
            Tensor inputTensor = Tensor.fromBlob(
                inputData,
                new long[]{1, CHANNELS, FRAME_COUNT, FRAME_SIZE, FRAME_SIZE}
            );
            
            IValue output = module.forward(IValue.from(inputTensor));
            Tensor outputTensor = output.toTensor();
            float[] bvpWaveform = outputTensor.getDataAsFloatArray();
            
            float heartRate = calculateHeartRate(bvpWaveform);
            float signalQuality = calculateSignalQuality(bvpWaveform);
            
            long inferenceTime = System.currentTimeMillis() - startTime;
            
            Log.i(TAG, String.format("Inference: BPM=%.1f, quality=%.3f, time=%dms (frames=%d)", 
                heartRate, signalQuality, inferenceTime, processedFrames));
            
            return new PhysNetResult(bvpWaveform, heartRate, signalQuality, inferenceTime);
            
        } catch (Exception e) {
            Log.e(TAG, "Inference failed", e);
            return null;
        }
    }
    
    private float calculateHeartRate(float[] bvp) {
        if (bvp == null || bvp.length < 32) {
            return lastValidBPM > 0 ? lastValidBPM : 70.0f;
        }
        
        int peaks = 0;
        float threshold = 0;
        
        for (float v : bvp) threshold += v;
        threshold /= bvp.length;
        
        for (int i = 1; i < bvp.length - 1; i++) {
            if (bvp[i] > bvp[i-1] && bvp[i] > bvp[i+1] && bvp[i] > threshold) {
                peaks++;
            }
        }
        
        float durationSeconds = FRAME_COUNT / TARGET_FPS;
        float bpm = (peaks / durationSeconds) * 60.0f;
        
        bpm = Math.max(45.0f, Math.min(180.0f, bpm));
        
        if (bpm < 50 || bpm > 150) {
            if (lastValidBPM > 0) {
                return lastValidBPM;
            }
        }
        
        return bpm;
    }
    
    private float calculateSignalQuality(float[] bvp) {
        if (bvp == null || bvp.length == 0) return 0.0f;
        
        float mean = 0;
        for (float v : bvp) mean += v;
        mean /= bvp.length;
        
        float stdDev = 0;
        for (float v : bvp) {
            float diff = v - mean;
            stdDev += diff * diff;
        }
        stdDev = (float) Math.sqrt(stdDev / bvp.length);
        
        return Math.min(1.0f, stdDev * 3.0f);
    }
    
    private float filterBPM(float newBPM) {
        bpmHistory.add(newBPM);
        if (bpmHistory.size() > BPM_HISTORY_SIZE) {
            bpmHistory.remove(0);
        }
        
        List<Float> sorted = new ArrayList<>(bpmHistory);
        sorted.sort(Float::compareTo);
        return sorted.get(sorted.size() / 2);
    }
    
    public void reset() {
        synchronized (this) {
            frameBuffer.clear();
            bpmHistory.clear();
            frameCounter = 0;
            processedFrames = 0;
            if (currentInference != null) {
                currentInference.cancel(true);
                currentInference = null;
            }
        }
    }
    
    private String copyAssetToCache(Context context, String assetPath) throws Exception {
        File cacheFile = new File(context.getCacheDir(), "physnet_optimized.ptl");
        if (!cacheFile.exists()) {
            try (InputStream is = context.getAssets().open(assetPath);
                 FileOutputStream os = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        }
        return cacheFile.getAbsolutePath();
    }
    
    public static class PhysNetResult {
        public final float[] bvpWaveform;
        public final float heartRate;
        public final float signalQuality;
        public final long inferenceTimeMs;
        
        public PhysNetResult(float[] bvpWaveform, float heartRate, float signalQuality, long inferenceTimeMs) {
            this.bvpWaveform = bvpWaveform;
            this.heartRate = heartRate;
            this.signalQuality = signalQuality;
            this.inferenceTimeMs = inferenceTimeMs;
        }
    }
}
