#include "bio_vault_native.h"
#include "rppg_engine.h"
#include "prnu_extractor.h"
#include "crypto_utils.h"
#include "watermark.h"
#ifdef HAVE_OPENCV
#include <opencv2/opencv.hpp>
#endif
#include <sstream>
#include <vector>
#include <mutex>
#include <cstring>

#ifdef ANDROID
#include <android/log.h>
#define LOG_TAG "BioVaultNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) printf(__VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

namespace biovault {

BioVaultNative::BioVaultNative()
    : m_isInitialized(false)
{
}

BioVaultNative::~BioVaultNative() {
}

std::string BioVaultNative::initialize() {
    try {
        m_rppgEngine = std::make_unique<RPPGEngine>(30, 150);
        m_prnuExtractor = std::make_unique<PRNUExtractor>();
        m_isInitialized = true;
        
        LOGI("Bio-Vault Native Engine initialized successfully");
        
        return R"({"success": true, "message": "Bio-Vault initialized"})";
    } catch (const std::exception& e) {
        LOGE("Initialization failed: %s", e.what());
        return R"({"success": false, "error": ")" + std::string(e.what()) + R"("})";
    }
}

std::string BioVaultNative::processFrame(
    const std::string& frameData,
    int width,
    int height,
    const std::string& faceBounds)
{
    if (!m_isInitialized) {
        return R"({"error": "Not initialized"})";
    }

    try {
#ifdef HAVE_OPENCV
        // Decode base64 frame data (simplified - use proper base64 decoder)
        std::vector<uint8_t> imageData(frameData.begin(), frameData.end());
        
        // Create Mat from data
        cv::Mat frame(height, width, CV_8UC3, imageData.data());
        
        // Parse face bounds: "x,y,width,height"
        cv::Rect faceBoundingBox;
        std::stringstream ss(faceBounds);
        char comma;
        ss >> faceBoundingBox.x >> comma 
           >> faceBoundingBox.y >> comma 
           >> faceBoundingBox.width >> comma 
           >> faceBoundingBox.height;
        
        // Process frame
        bool success = m_rppgEngine->processFrame(frame, faceBoundingBox);
        
        if (!success) {
            return R"({"error": "Frame processing failed"})";
        }
        
        int bpm = m_rppgEngine->getCurrentBPM();
        float confidence = m_rppgEngine->getConfidence();
        bool liveness = m_rppgEngine->isLivenessDetected();
#else
        // Mock data without OpenCV
        (void)frameData; (void)width; (void)height; (void)faceBounds;
        m_rppgEngine->processFrame(nullptr, nullptr);
        int bpm = m_rppgEngine->getCurrentBPM();
        float confidence = m_rppgEngine->getConfidence();
        bool liveness = m_rppgEngine->isLivenessDetected();
#endif
        
        // Build JSON response
        std::stringstream result;
        result << R"({"bpm": )" << bpm 
               << R"(, "confidence": )" << confidence
               << R"(, "liveness": )" << (liveness ? "true" : "false")
               << R"(, "success": true})";
        
        return result.str();
        
    } catch (const std::exception& e) {
        LOGE("Frame processing error: %s", e.what());
        return R"({"error": ")" + std::string(e.what()) + R"("})";
    }
}

std::string BioVaultNative::calibrateHardware(const std::string& calibrationFramesJson) {
    if (!m_isInitialized) {
        return R"({"error": "Not initialized"})";
    }

    try {
#ifdef HAVE_OPENCV
        // calibrationFramesJson is now unused — use addCalibrationFrame() / finalizeCalibration() instead
        (void)calibrationFramesJson;
        return R"({"error": "Use addCalibrationFrame() + finalizeCalibration() for PRNU calibration"})";
#else
        (void)calibrationFramesJson;
        return R"({"error": "OpenCV required for PRNU calibration"})";
#endif
    } catch (const std::exception& e) {
        LOGE("Hardware calibration error: %s", e.what());
        return R"({"error": ")" + std::string(e.what()) + R"("})";
    }
}

#ifdef HAVE_OPENCV
bool BioVaultNative::addCalibrationFrame(const uint8_t* rgbaData, int width, int height) {
    if (!m_isInitialized) return false;
    try {
        // Create cv::Mat from raw RGBA pixel data (4 channels)
        cv::Mat rgba(height, width, CV_8UC4, const_cast<uint8_t*>(rgbaData));
        cv::Mat bgr;
        cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
        // Deep-copy so caller's buffer can be freed
        m_calibrationFrames.push_back(bgr.clone());
        LOGI("PRNU calibration: added frame %zu/%d", m_calibrationFrames.size(), 50);
        return true;
    } catch (const std::exception& e) {
        LOGE("addCalibrationFrame error: %s", e.what());
        return false;
    }
}
#else
bool BioVaultNative::addCalibrationFrame(const uint8_t* rgbaData, int width, int height) {
    (void)rgbaData; (void)width; (void)height;
    LOGE("addCalibrationFrame: OpenCV required for real PRNU extraction");
    return false;
}
#endif

std::string BioVaultNative::finalizeCalibration() {
    if (!m_isInitialized) {
        return R"({"error": "Not initialized"})";
    }
    try {
#ifdef HAVE_OPENCV
        if (m_calibrationFrames.size() < 50) {
            std::stringstream err;
            err << R"({"error": "Need at least 50 frames, have )" << m_calibrationFrames.size() << R"("})";
            return err.str();
        }
        bool success = m_prnuExtractor->extractPattern(m_calibrationFrames);
        m_calibrationFrames.clear(); // free memory
        if (!success) {
            return R"({"error": "PRNU pattern extraction failed"})";
        }
        m_hardwareFingerprint = m_prnuExtractor->getHardwareFingerprint();
        LOGI("PRNU calibration complete — fingerprint: %s", m_hardwareFingerprint.c_str());
        std::stringstream result;
        result << R"({"success": true, "hardwareFingerprint": ")" 
               << m_hardwareFingerprint << R"("})";
        return result.str();
#else
        return R"({"error": "OpenCV required for PRNU calibration"})";
#endif
    } catch (const std::exception& e) {
        LOGE("finalizeCalibration error: %s", e.what());
        m_calibrationFrames.clear();
        return R"({"error": ")" + std::string(e.what()) + R"("})";
    }
}

std::string BioVaultNative::getHardwareDNA() const {
    if (m_hardwareFingerprint.empty()) {
        return "";
    }
    return m_hardwareFingerprint;
}

std::string BioVaultNative::generateAnchorHash(
    const std::string& frameData,
    int bpm,
    const std::string& hardwareID)
{
    try {
        std::vector<uint8_t> data(frameData.begin(), frameData.end());
        uint64_t timestamp = crypto::CryptoUtils::getCurrentTimestamp();
        
        std::string hash = crypto::CryptoUtils::generateBioVaultHash(
            data, bpm, hardwareID, timestamp
        );
        
        std::stringstream result;
        result << R"({"hash": ")" << hash 
               << R"(", "timestamp": )" << timestamp
               << R"(, "bpm": )" << bpm
               << R"(})";
        
        return result.str();
        
    } catch (const std::exception& e) {
        LOGE("Hash generation error: %s", e.what());
        return R"({"error": ")" + std::string(e.what()) + R"("})";
    }
}

void BioVaultNative::reset() {
    if (m_rppgEngine) {
        m_rppgEngine->reset();
    }
    m_hardwareFingerprint.clear();
    m_calibrationFrames.clear();
    LOGI("Bio-Vault reset");
}

} // namespace biovault

// ============================================================================
// JNI Implementation for Android
// ============================================================================
#ifdef ANDROID

static biovault::BioVaultNative* g_nativeInstance = nullptr;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_nativeInitialize(JNIEnv* env, jobject /* thiz */) {
    if (!g_nativeInstance) {
        g_nativeInstance = new biovault::BioVaultNative();
    }
    
    std::string result = g_nativeInstance->initialize();
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_processFrame(
    JNIEnv* env, jobject /* thiz */,
    jstring frameData, jint width, jint height, jstring faceBounds)
{
    if (!g_nativeInstance) {
        return env->NewStringUTF(R"({"error": "Not initialized"})");
    }
    
    const char* frameStr = env->GetStringUTFChars(frameData, nullptr);
    const char* boundsStr = env->GetStringUTFChars(faceBounds, nullptr);
    
    std::string result = g_nativeInstance->processFrame(
        frameStr, width, height, boundsStr
    );
    
    env->ReleaseStringUTFChars(frameData, frameStr);
    env->ReleaseStringUTFChars(faceBounds, boundsStr);
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_calibrateHardware(
    JNIEnv* env, jobject /* thiz */, jstring calibrationFramesJson)
{
    if (!g_nativeInstance) {
        return env->NewStringUTF(R"({"error": "Not initialized"})");
    }
    
    const char* jsonStr = env->GetStringUTFChars(calibrationFramesJson, nullptr);
    std::string result = g_nativeInstance->calibrateHardware(jsonStr);
    env->ReleaseStringUTFChars(calibrationFramesJson, jsonStr);
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_generateAnchorHash(
    JNIEnv* env, jobject /* thiz */,
    jstring frameData, jint bpm, jstring hardwareID)
{
    if (!g_nativeInstance) {
        return env->NewStringUTF(R"({"error": "Not initialized"})");
    }
    
    const char* frameStr = env->GetStringUTFChars(frameData, nullptr);
    const char* hwIDStr = env->GetStringUTFChars(hardwareID, nullptr);
    
    std::string result = g_nativeInstance->generateAnchorHash(
        frameStr, bpm, hwIDStr
    );
    
    env->ReleaseStringUTFChars(frameData, frameStr);
    env->ReleaseStringUTFChars(hardwareID, hwIDStr);
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_biovault_BioVaultModule_reset(JNIEnv* /* env */, jobject /* thiz */) {
    if (g_nativeInstance) {
        g_nativeInstance->reset();
    }
}

// ---------------------------------------------------------------------------
// PRNU Calibration JNI — incremental frame-by-frame approach
// ---------------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_biovault_BioVaultModule_nativeAddCalibrationFrame(
    JNIEnv* env, jobject /* thiz */,
    jbyteArray rgbaData, jint width, jint height)
{
    if (!g_nativeInstance) return JNI_FALSE;

    jsize len = env->GetArrayLength(rgbaData);
    jbyte* bytes = env->GetByteArrayElements(rgbaData, nullptr);
    if (!bytes) return JNI_FALSE;

    bool ok = g_nativeInstance->addCalibrationFrame(
        reinterpret_cast<const uint8_t*>(bytes), width, height);

    env->ReleaseByteArrayElements(rgbaData, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_nativeFinalizeCalibration(
    JNIEnv* env, jobject /* thiz */)
{
    if (!g_nativeInstance) {
        return env->NewStringUTF(R"({"error": "Not initialized"})");
    }
    std::string result = g_nativeInstance->finalizeCalibration();
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_nativeGetHardwareDNA(
    JNIEnv* env, jobject /* thiz */)
{
    if (!g_nativeInstance) {
        return env->NewStringUTF("");
    }
    std::string dna = g_nativeInstance->getHardwareDNA();
    return env->NewStringUTF(dna.c_str());
}

// ---------------------------------------------------------------------------
// Watermark JNI — embed / extract
// ---------------------------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_biovault_BioVaultModule_nativeEmbedWatermark(
    JNIEnv* env, jobject /* thiz */,
    jbyteArray imageRgba, jint w, jint h, jstring payloadJson)
{
    jsize len = env->GetArrayLength(imageRgba);
    jbyte* bytes = env->GetByteArrayElements(imageRgba, nullptr);
    if (!bytes) return nullptr;

    const char* payloadStr = env->GetStringUTFChars(payloadJson, nullptr);
    std::string payload(payloadStr);
    env->ReleaseStringUTFChars(payloadJson, payloadStr);

    // Allocate output buffer (same size as input)
    std::vector<uint8_t> outBuf(len);
    bool ok = biovault::Watermark::embed(
        reinterpret_cast<const uint8_t*>(bytes), w, h,
        payload, outBuf.data());

    env->ReleaseByteArrayElements(imageRgba, bytes, JNI_ABORT);

    if (!ok) return nullptr;

    jbyteArray result = env->NewByteArray(len);
    env->SetByteArrayRegion(result, 0, len,
                            reinterpret_cast<const jbyte*>(outBuf.data()));
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_nativeExtractWatermark(
    JNIEnv* env, jobject /* thiz */,
    jbyteArray imageRgba, jint w, jint h)
{
    jsize len = env->GetArrayLength(imageRgba);
    jbyte* bytes = env->GetByteArrayElements(imageRgba, nullptr);
    if (!bytes) return env->NewStringUTF("");

    std::string decoded = biovault::Watermark::extract(
        reinterpret_cast<const uint8_t*>(bytes), w, h);

    env->ReleaseByteArrayElements(imageRgba, bytes, JNI_ABORT);
    return env->NewStringUTF(decoded.c_str());
}

// Consensus JNI methods are defined in android/app/src/main/cpp/native-lib.cpp
// to avoid duplicate symbol errors.

} // extern "C"

#endif // ANDROID
