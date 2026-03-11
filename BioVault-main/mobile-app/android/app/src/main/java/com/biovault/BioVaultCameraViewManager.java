package com.biovault;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

public class BioVaultCameraViewManager extends SimpleViewManager<TextureView> {
    private static final String TAG = "BioVaultCamera";
    private static final String REACT_CLASS = "BioVaultCameraView";
    
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private ImageReader imageReader;
    private Size previewSize = new Size(640, 480);
    private boolean isCalibrationMode = false;
    
    @Override
    public String getName() {
        return REACT_CLASS;
    }
    
    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put("onCameraReady", MapBuilder.of("registrationName", "onCameraReady"))
                .put("onCameraError", MapBuilder.of("registrationName", "onCameraError"))
                .put("onFrameAvailable", MapBuilder.of("registrationName", "onFrameAvailable"))
                .build();
    }
    
    private TextureView currentView;
    
    @Override
    protected TextureView createViewInstance(ThemedReactContext reactContext) {
        Log.d(TAG, "Creating BioVault Camera View");
        TextureView textureView = new TextureView(reactContext);
        currentView = textureView; // Store reference
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "Surface texture available: " + width + "x" + height);
                openCamera(reactContext);
            }
            
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "Surface texture size changed: " + width + "x" + height);
            }
            
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.d(TAG, "Surface texture destroyed");
                closeCamera();
                return true;
            }
            
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Called for every frame - too verbose to log
            }
        });
        
        startBackgroundThread();
        return textureView;
    }
    
    @ReactProp(name = "active", defaultBoolean = false)
    public void setActive(TextureView view, boolean active) {
        Log.d(TAG, "Camera active: " + active);
        if (active && cameraDevice == null) {
            openCamera((ReactContext) view.getContext());
        } else if (!active && cameraDevice != null) {
            closeCamera();
        }
    }

    @ReactProp(name = "calibrationMode", defaultBoolean = false)
    public void setCalibrationMode(TextureView view, boolean calibrationMode) {
        Log.d(TAG, "Calibration mode: " + calibrationMode);
        isCalibrationMode = calibrationMode;
    }
    
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        Log.d(TAG, "Background thread started");
    }
    
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "Background thread stopped");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }
    
    private void openCamera(ReactContext context) {
        Log.d(TAG, "Opening camera...");
        
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission not granted");
            sendEvent(context, "onCameraError", "Camera permission not granted");
            return;
        }
        
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            
            // Find front camera
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    
                    // Get optimal preview size
                    StreamConfigurationMap map = characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                        previewSize = chooseOptimalSize(sizes);
                        Log.d(TAG, "Selected preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
                    }
                    break;
                }
            }
            
            if (cameraId == null) {
                Log.e(TAG, "No front camera found");
                sendEvent(context, "onCameraError", "No front camera found");
                return;
            }
            
            // Setup ImageReader for frame processing
            imageReader = ImageReader.newInstance(
                    previewSize.getWidth(),
                    previewSize.getHeight(),
                    ImageFormat.YUV_420_888,
                    2
            );
            
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        processFrame(image, context);
                    }
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, backgroundHandler);
            
            final String finalCameraId = cameraId;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera opened successfully");
                    cameraDevice = camera;
                    createCameraPreviewSession(context);
                    sendEvent(context, "onCameraReady", null);
                }
                
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera disconnected");
                    camera.close();
                    cameraDevice = null;
                }
                
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    sendEvent(context, "onCameraError", "Camera error: " + error);
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
            sendEvent(context, "onCameraError", "Error opening camera: " + e.getMessage());
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception opening camera", e);
            sendEvent(context, "onCameraError", "Security exception: " + e.getMessage());
        }
    }
    
    private Size chooseOptimalSize(Size[] choices) {
        // Prefer 640x480 for rPPG processing (VGA resolution)
        for (Size size : choices) {
            if (size.getWidth() == 640 && size.getHeight() == 480) {
                return size;
            }
        }
        
        // Fallback to something close
        for (Size size : choices) {
            if (size.getWidth() >= 640 && size.getHeight() >= 480 
                    && size.getWidth() <= 1280 && size.getHeight() <= 960) {
                return size;
            }
        }
        
        return choices[0];
    }
    
    private void createCameraPreviewSession(ReactContext context) {
        if (cameraDevice == null) {
            Log.e(TAG, "Cannot create preview session: camera device is null");
            return;
        }
        
        try {
            SurfaceTexture texture = ((TextureView) getCurrentView()).getSurfaceTexture();
            if (texture == null) {
                Log.e(TAG, "Surface texture is null");
                return;
            }
            
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);
            
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.addTarget(imageReader.getSurface());
            
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                Log.e(TAG, "Camera closed during session creation");
                                return;
                            }
                            
                            captureSession = session;
                            try {
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                                );
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                                );
                                
                                CaptureRequest previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest, null, backgroundHandler);
                                Log.d(TAG, "Camera preview started");
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error starting preview", e);
                            }
                        }
                        
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Failed to configure camera preview session");
                            sendEvent(context, "onCameraError", "Failed to configure preview");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating preview session", e);
        }
    }
    
    private TextureView getCurrentView() {
        return currentView;
    }
    
    private long lastFrameTime = 0;
    private static final long FRAME_INTERVAL_MS = 100; // Process at ~10 FPS
    private BioVaultModule bioVaultModule;
    
    private void processFrame(Image image, ReactContext context) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
            return; // Skip frame to maintain ~10 FPS processing
        }
        lastFrameTime = currentTime;
        
        try {
            // Get BioVaultModule reference
            if (bioVaultModule == null) {
                bioVaultModule = context.getNativeModule(BioVaultModule.class);
                Log.d(TAG, "BioVaultModule reference: " + (bioVaultModule != null ? "SUCCESS" : "NULL"));
            }
            
            // Convert YUV to byte array for native processing
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
            
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            
            byte[] frameData = new byte[ySize + uSize + vSize];
            yBuffer.get(frameData, 0, ySize);
            uBuffer.get(frameData, ySize, uSize);
            vBuffer.get(frameData, ySize + uSize, vSize);

            // CALIBRATION MODE: feed frames to PRNU extractor
            if (isCalibrationMode && bioVaultModule != null) {
                int count = bioVaultModule.addCalibrationFrameFromYUV(
                    frameData, image.getWidth(), image.getHeight());
                WritableMap event = Arguments.createMap();
                event.putInt("calibrationFrameCount", count >= 0 ? count : 0);
                event.putBoolean("calibrationMode", true);
                event.putBoolean("calibrationComplete", count >= 50);
                event.putDouble("timestamp", currentTime);
                sendEvent(context, "onFrameAvailable", event);
                return;
            }
            
            // Process frame with native OpenCV code
            if (bioVaultModule != null) {
                Log.d(TAG, "Calling processVideoFrameSync...");
                WritableMap result = bioVaultModule.processVideoFrameSync(
                    frameData, 
                    image.getWidth(), 
                    image.getHeight(), 
                    0 // rotation
                );
                
                Log.d(TAG, "Native processing result: " + (result != null ? "SUCCESS" : "NULL"));
                if (result != null) {
                    // Send processed result to JavaScript with face detection and BPM
                    result.putDouble("timestamp", currentTime);
                    sendEvent(context, "onFrameAvailable", result);
                    return;
                }
            } else {
                Log.w(TAG, "BioVaultModule is NULL, skipping native processing");
            }
            
            // Fallback: Send basic frame event
            WritableMap event = Arguments.createMap();
            event.putInt("width", image.getWidth());
            event.putInt("height", image.getHeight());
            event.putDouble("timestamp", currentTime);
            event.putInt("facesDetected", 0);
            sendEvent(context, "onFrameAvailable", event);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame", e);
        }
    }
    
    private void sendEvent(ReactContext context, String eventName, @Nullable Object data) {
        if (context.hasActiveReactInstance()) {
            WritableMap event = Arguments.createMap();
            if (data instanceof String) {
                event.putString("message", (String) data);
            } else if (data instanceof WritableMap) {
                event.merge((WritableMap) data);
            }
            
            context.getJSModule(RCTEventEmitter.class)
                    .receiveEvent(getCurrentView().getId(), eventName, event);
        }
    }
    
    private void closeCamera() {
        Log.d(TAG, "Closing camera");
        
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        
        stopBackgroundThread();
    }
    
    @Override
    public void onDropViewInstance(TextureView view) {
        super.onDropViewInstance(view);
        closeCamera();
    }
}
