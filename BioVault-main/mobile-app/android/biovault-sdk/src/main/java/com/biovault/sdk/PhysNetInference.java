package com.biovault.sdk;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PhysNet Neural Network Inference Wrapper
 * Uses PyTorch Mobile to run 3D-CNN rPPG heart rate extraction
 */
public class PhysNetInference {
    private static final String TAG = "PhysNetInference";
    
    private static final int FRAME_COUNT = 128;
    private static final int FRAME_WIDTH = 128;
    private static final int FRAME_HEIGHT = 128;
    private static final int CHANNELS = 3;
    
    private Module module;
    private boolean isLoaded = false;
    private List<float[]> frameBuffer;
    private int frameSampleRate = 30;
    
    public PhysNetInference(Context context) {
        frameBuffer = new ArrayList<>();
        try {
            String modelPath = assetFilePath(context, "models/physnet.ptl");
            module = LiteModuleLoader.load(modelPath);
            isLoaded = true;
            Log.i(TAG, "✓ PhysNet model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "✗ Failed to load PhysNet model", e);
            isLoaded = false;
        }
    }
    
    public boolean isModelLoaded() {
        return isLoaded;
    }
    
    public void addFrame(Bitmap frame) {
        if (!isLoaded || frame == null) return;
        
        int width = frame.getWidth();
        int height = frame.getHeight();
        int[] pixels = new int[width * height];
        frame.getPixels(pixels, 0, width, 0, 0, width, height);
        
        float[] normalizedFrame = new float[CHANNELS * FRAME_HEIGHT * FRAME_WIDTH];
        
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            
            normalizedFrame[i] = (r / 127.5f) - 1.0f;
            normalizedFrame[FRAME_WIDTH * FRAME_HEIGHT + i] = (g / 127.5f) - 1.0f;
            normalizedFrame[2 * FRAME_WIDTH * FRAME_HEIGHT + i] = (b / 127.5f) - 1.0f;
        }
        
        frameBuffer.add(normalizedFrame);
        
        if (frameBuffer.size() > FRAME_COUNT) {
            frameBuffer.remove(0);
        }
    }
    
    public boolean hasEnoughFrames() {
        return frameBuffer.size() >= FRAME_COUNT;
    }
    
    public int getFrameCount() {
        return frameBuffer.size();
    }
    
    public PhysNetResult runInference() {
        if (!isLoaded || !hasEnoughFrames()) {
            return null;
        }
        
        try {
            float[] inputData = new float[1 * CHANNELS * FRAME_COUNT * FRAME_HEIGHT * FRAME_WIDTH];
            
            for (int t = 0; t < FRAME_COUNT; t++) {
                float[] frame = frameBuffer.get(frameBuffer.size() - FRAME_COUNT + t);
                
                for (int c = 0; c < CHANNELS; c++) {
                    for (int h = 0; h < FRAME_HEIGHT; h++) {
                        for (int w = 0; w < FRAME_WIDTH; w++) {
                            int srcIdx = c * FRAME_HEIGHT * FRAME_WIDTH + h * FRAME_WIDTH + w;
                            int dstIdx = c * FRAME_COUNT * FRAME_HEIGHT * FRAME_WIDTH +
                                        t * FRAME_HEIGHT * FRAME_WIDTH +
                                        h * FRAME_WIDTH + w;
                            inputData[dstIdx] = frame[srcIdx];
                        }
                    }
                }
            }
            
            long[] shape = {1, CHANNELS, FRAME_COUNT, FRAME_HEIGHT, FRAME_WIDTH};
            Tensor inputTensor = Tensor.fromBlob(inputData, shape);
            
            long startTime = System.currentTimeMillis();
            IValue[] outputs = module.forward(IValue.from(inputTensor)).toTuple();
            long inferenceTime = System.currentTimeMillis() - startTime;
            
            Tensor outputTensor = outputs[0].toTensor();
            float[] bvpSignal = outputTensor.getDataAsFloatArray();
            
            Log.i(TAG, String.format("✓ PhysNet inference: %dms, signal length: %d", 
                                    inferenceTime, bvpSignal.length));
            
            int heartRate = calculateHeartRate(bvpSignal, frameSampleRate);
            double signalQuality = calculateSignalQuality(bvpSignal);
            
            return new PhysNetResult(bvpSignal, heartRate, signalQuality, inferenceTime);
            
        } catch (Exception e) {
            Log.e(TAG, "✗ PhysNet inference failed", e);
            return null;
        }
    }
    
    private int calculateHeartRate(float[] bvpSignal, int sampleRate) {
        int n = bvpSignal.length;
        
        int peakCount = 0;
        float threshold = 0.0f;
        
        float mean = 0;
        for (float v : bvpSignal) mean += v;
        mean /= n;
        
        boolean aboveThreshold = false;
        for (float v : bvpSignal) {
            if (v > mean && !aboveThreshold) {
                peakCount++;
                aboveThreshold = true;
            } else if (v <= mean) {
                aboveThreshold = false;
            }
        }
        
        float durationSeconds = (float) n / sampleRate;
        int bpm = (int) ((peakCount / durationSeconds) * 60);
        
        if (bpm < 40) bpm = 40;
        if (bpm > 180) bpm = 180;
        
        return bpm;
    }
    
    private double calculateSignalQuality(float[] signal) {
        float mean = 0, std = 0;
        
        for (float v : signal) mean += v;
        mean /= signal.length;
        
        for (float v : signal) std += (v - mean) * (v - mean);
        std = (float) Math.sqrt(std / signal.length);
        
        return Math.min(1.0, std / (Math.abs(mean) + 1e-6));
    }
    
    public void reset() {
        frameBuffer.clear();
    }
    
    public void release() {
        if (module != null) {
            module = null;
        }
        frameBuffer.clear();
        isLoaded = false;
    }
    
    private String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        file.getParentFile().mkdirs();
        
        if (!file.exists()) {
            try (InputStream is = context.getAssets().open(assetName);
                 FileOutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
        }
        
        return file.getAbsolutePath();
    }
    
    public static class PhysNetResult {
        public final float[] bvpWaveform;
        public final int heartRate;
        public final double signalQuality;
        public final long inferenceTimeMs;
        
        public PhysNetResult(float[] bvp, int hr, double quality, long time) {
            this.bvpWaveform = bvp;
            this.heartRate = hr;
            this.signalQuality = quality;
            this.inferenceTimeMs = time;
        }
        
        @Override
        public String toString() {
            return String.format("PhysNetResult{HR=%d BPM, Quality=%.2f, Time=%dms}",
                               heartRate, signalQuality, inferenceTimeMs);
        }
    }
}
