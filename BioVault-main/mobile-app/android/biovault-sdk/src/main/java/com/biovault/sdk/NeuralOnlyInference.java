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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * NEURAL-ONLY rPPG: PhysNet optimized for real-time mobile inference
 * NO FFT FALLBACK - Pure neural network approach
 * 
 * Optimizations:
 * - Async inference with dedicated thread pool
 * - Frame decimation (process every 3rd frame)
 * - Smaller input: 96x96 instead of 128x128
 * - Buffer size: 64 frames instead of 128
 * - Result caching and interpolation
 */
public class NeuralOnlyInference {
    private static final String TAG = "NeuralOnly";
    
    private static final int BUFFER_SIZE = 64;
    private static final int FRAME_SIZE = 96;
    private static final int FRAME_DECIMATION = 3;
    private static final int INFERENCE_INTERVAL = 32;
    
    private Module model;
    private boolean isModelLoaded = false;
    private final ExecutorService inferenceExecutor;
    private Future<InferenceResult> currentInference = null;
    
    private final ArrayBlockingQueue<float[]> frameQueue;
    private int frameCounter = 0;
    private int inferenceCounter = 0;
    
    private InferenceResult lastResult = null;
    private long lastInferenceTime = 0;
    
    public NeuralOnlyInference(Context context) {
        inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            t.setName("NeuralInference");
            return t;
        });
        
        frameQueue = new ArrayBlockingQueue<>(BUFFER_SIZE);
        
        try {
            String modelPath = copyAssetToCache(context, "models/physnet.ptl");
            model = LiteModuleLoader.load(modelPath);
            isModelLoaded = true;
            
            Log.i(TAG, "✓ Neural-Only rPPG initialized");
            Log.i(TAG, "  Frames: " + BUFFER_SIZE + " @ " + FRAME_SIZE + "x" + FRAME_SIZE);
            Log.i(TAG, "  Decimation: 1/" + FRAME_DECIMATION + " frames");
            Log.i(TAG, "  Mode: NEURAL ONLY (no FFT fallback)");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load neural model", e);
            isModelLoaded = false;
        }
    }
    
    public boolean isReady() {
        return isModelLoaded;
    }
    
    public synchronized void addFrame(Bitmap frame) {
        if (!isModelLoaded || frame == null) return;
        
        frameCounter++;
        
        if (frameCounter % FRAME_DECIMATION != 0) {
            return;
        }
        
        try {
            Bitmap resized = Bitmap.createScaledBitmap(frame, FRAME_SIZE, FRAME_SIZE, false);
            float[] frameData = bitmapToFloatArray(resized);
            resized.recycle();
            
            if (!frameQueue.offer(frameData)) {
                frameQueue.poll();
                frameQueue.offer(frameData);
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
                    Log.i(TAG, String.format("Neural result: BPM=%.1f, quality=%.2f, time=%dms",
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
        final float[][] frames = frameQueue.toArray(new float[0][]);
        if (frames.length < BUFFER_SIZE) {
            return;
        }
        
        currentInference = inferenceExecutor.submit(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                float[] inputData = new float[3 * BUFFER_SIZE * FRAME_SIZE * FRAME_SIZE];
                
                for (int t = 0; t < BUFFER_SIZE && t < frames.length; t++) {
                    System.arraycopy(frames[t], 0, inputData, t * frames[t].length, frames[t].length);
                }
                
                Tensor inputTensor = Tensor.fromBlob(
                    inputData,
                    new long[]{1, 3, BUFFER_SIZE, FRAME_SIZE, FRAME_SIZE}
                );
                
                IValue output = model.forward(IValue.from(inputTensor));
                
                Tensor outputTensor;
                if (output.isTuple()) {
                    outputTensor = output.toTuple()[0].toTensor();
                } else {
                    outputTensor = output.toTensor();
                }
                
                float[] bvp = outputTensor.getDataAsFloatArray();
                
                float bpm = calculateBPM(bvp);
                float confidence = calculateConfidence(bvp);
                
                long inferenceTime = System.currentTimeMillis() - startTime;
                
                return new InferenceResult(bpm, confidence, inferenceTime, true);
                
            } catch (Exception e) {
                Log.e(TAG, "Inference failed", e);
                return new InferenceResult(70.0f, 0.0f, 0, false);
            }
        });
    }
    
    private float[] bitmapToFloatArray(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        
        float[] floatArray = new float[3 * width * height];
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            floatArray[i] = ((pixel >> 16) & 0xFF) / 255.0f;
            floatArray[width * height + i] = ((pixel >> 8) & 0xFF) / 255.0f;
            floatArray[2 * width * height + i] = (pixel & 0xFF) / 255.0f;
        }
        
        return floatArray;
    }
    
    private float calculateBPM(float[] bvp) {
        if (bvp == null || bvp.length < 32) {
            return 70.0f;
        }
        
        int peaks = 0;
        float threshold = 0;
        
        for (float v : bvp) threshold += v;
        threshold /= bvp.length;
        
        for (int i = 2; i < bvp.length - 2; i++) {
            if (bvp[i] > bvp[i-1] && bvp[i] > bvp[i+1] && 
                bvp[i] > bvp[i-2] && bvp[i] > bvp[i+2] && 
                bvp[i] > threshold) {
                peaks++;
            }
        }
        
        float durationSec = BUFFER_SIZE / 10.0f;
        float bpm = (peaks / durationSec) * 60.0f;
        
        return Math.max(45.0f, Math.min(180.0f, bpm));
    }
    
    private float calculateConfidence(float[] bvp) {
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
        
        return Math.min(1.0f, stdDev * 5.0f);
    }
    
    private String copyAssetToCache(Context context, String assetPath) throws Exception {
        File cacheFile = new File(context.getCacheDir(), "neural_only.ptl");
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
    
    public void reset() {
        frameQueue.clear();
        frameCounter = 0;
        inferenceCounter = 0;
        lastResult = null;
        if (currentInference != null) {
            currentInference.cancel(true);
            currentInference = null;
        }
    }
    
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
}
