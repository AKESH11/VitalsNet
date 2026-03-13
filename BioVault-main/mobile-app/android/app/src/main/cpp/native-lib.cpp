/**
 * native-lib.cpp
 * JNI bridge between C++ Bio-Vault logic and Kotlin StrongBoxManager
 */

#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <map>
#include "BioVaultExtractor.h"
#include "crypto_utils.h"
#include "consensus_handshake.h"

// Global reference to Kotlin StrongBoxManager instance
static JavaVM* g_jvm = nullptr;
static jobject g_strongBoxManager = nullptr;

// Active consensus sessions (sessionId -> ConsensusHandshake)
static std::map<std::string, std::unique_ptr<biovault::consensus::ConsensusHandshake>> g_consensusSessions;

extern "C" {

/**
 * Initialize JNI bridge with StrongBoxManager instance
 * Called from Kotlin: initializeNativeBridge(strongBoxManager)
 */
JNIEXPORT void JNICALL
Java_com_biovault_sdk_StrongBoxManager_initializeNativeBridge(
    JNIEnv* env,
    jobject strongBoxManagerInstance) {
    
    // Store JavaVM pointer for later JNI calls from C++
    if (g_jvm == nullptr) {
        env->GetJavaVM(&g_jvm);
    }
    
    // Store global reference to StrongBoxManager
    if (g_strongBoxManager != nullptr) {
        env->DeleteGlobalRef(g_strongBoxManager);
    }
    g_strongBoxManager = env->NewGlobalRef(strongBoxManagerInstance);
}

/**
 * Get hardware-backed signature from StrongBox
 * Called from C++ code when bio-hash is ready
 */
std::vector<uint8_t> getHardwareSignature(const std::vector<uint8_t>& hash) {
    if (g_jvm == nullptr || g_strongBoxManager == nullptr) {
        // JNI not initialized
        return std::vector<uint8_t>();
    }
    
    JNIEnv* env = nullptr;
    bool needDetach = false;
    
    // Attach current thread to JVM if needed
    int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != 0) {
            return std::vector<uint8_t>();
        }
        needDetach = true;
    }
    
    // Convert C++ vector to Java byte array
    jbyteArray jHash = env->NewByteArray(hash.size());
    env->SetByteArrayRegion(jHash, 0, hash.size(), 
                           reinterpret_cast<const jbyte*>(hash.data()));
    
    // Get StrongBoxManager class and method
    jclass clazz = env->GetObjectClass(g_strongBoxManager);
    jmethodID signMethod = env->GetMethodID(clazz, "signHash", "([B)[B");
    
    if (signMethod == nullptr) {
        env->DeleteLocalRef(jHash);
        if (needDetach) g_jvm->DetachCurrentThread();
        return std::vector<uint8_t>();
    }
    
    // Call Kotlin: StrongBoxManager.signHash(hash)
    jbyteArray jSignature = (jbyteArray)env->CallObjectMethod(
        g_strongBoxManager, signMethod, jHash);
    
    // Check for exceptions (e.g., key not found, biometric not authenticated)
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->DeleteLocalRef(jHash);
        if (needDetach) g_jvm->DetachCurrentThread();
        return std::vector<uint8_t>();
    }
    
    // Convert Java byte array back to C++ vector
    std::vector<uint8_t> signature;
    if (jSignature != nullptr) {
        jsize length = env->GetArrayLength(jSignature);
        signature.resize(length);
        env->GetByteArrayRegion(jSignature, 0, length, 
                               reinterpret_cast<jbyte*>(signature.data()));
        env->DeleteLocalRef(jSignature);
    }
    
    env->DeleteLocalRef(jHash);
    env->DeleteLocalRef(clazz);
    
    if (needDetach) {
        g_jvm->DetachCurrentThread();
    }
    
    return signature;
}

/**
 * Generate Bio-Vault hash and sign with StrongBox
 * Called from React Native: generateBioVaultProof(frameData, bpm, hardwareID)
 */
JNIEXPORT jbyteArray JNICALL
Java_com_biovault_BioVaultModule_generateBioVaultProof(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray frameData,
    jint bpm,
    jstring hardwareID) {
    
    // Convert Java inputs to C++ types
    jsize frameDataLength = env->GetArrayLength(frameData);
    std::vector<uint8_t> frameBytes(frameDataLength);
    env->GetByteArrayRegion(frameData, 0, frameDataLength, 
                           reinterpret_cast<jbyte*>(frameBytes.data()));
    
    const char* hwIDChars = env->GetStringUTFChars(hardwareID, nullptr);
    std::string hwID(hwIDChars);
    env->ReleaseStringUTFChars(hardwareID, hwIDChars);
    
    // Generate Bio-Vault hash (video + pulse + hardware)
    uint64_t timestamp = biovault::crypto::CryptoUtils::getCurrentTimestamp();
    std::string bioHashStr = biovault::crypto::CryptoUtils::generateBioVaultHash(
        frameBytes, bpm, hwID, timestamp);
    
    // Convert hex string to bytes (32 bytes for BLAKE3)
    std::vector<uint8_t> bioHash = biovault::crypto::CryptoUtils::fromHex(bioHashStr);
    
    // Get hardware signature from StrongBox via JNI callback
    std::vector<uint8_t> signature = getHardwareSignature(bioHash);
    
    if (signature.empty()) {
        // Biometric auth failed or StrongBox not available
        return nullptr;
    }
    
    // Combine bio-hash + signature for blockchain anchoring
    std::vector<uint8_t> proof;
    proof.insert(proof.end(), bioHash.begin(), bioHash.end());
    proof.insert(proof.end(), signature.begin(), signature.end());
    
    // Convert back to Java byte array
    jbyteArray result = env->NewByteArray(proof.size());
    env->SetByteArrayRegion(result, 0, proof.size(), 
                           reinterpret_cast<const jbyte*>(proof.data()));
    
    return result;
}

/**
 * Test hardware signature functionality
 * Called from React Native: testStrongBoxSignature()
 */
JNIEXPORT jboolean JNICALL
Java_com_biovault_BioVaultModule_testStrongBoxSignature(
    JNIEnv* env,
    jobject /* this */) {
    
    // Create test hash (32 bytes)
    std::vector<uint8_t> testHash(32, 0x42);
    
    // Try to get signature
    std::vector<uint8_t> signature = getHardwareSignature(testHash);
    
    return signature.empty() ? JNI_FALSE : JNI_TRUE;
}

/**
 * Cleanup JNI bridge
 */
JNIEXPORT void JNICALL
Java_com_biovault_sdk_StrongBoxManager_cleanupNativeBridge(
    JNIEnv* env,
    jobject /* this */) {
    
    if (g_strongBoxManager != nullptr) {
        env->DeleteGlobalRef(g_strongBoxManager);
        g_strongBoxManager = nullptr;
    }
}

/**
 * Initialize consensus handshake session
 * @param sessionId Unique session identifier
 * @param expectedFaceIds Array of face IDs detected
 * @param videoFrameHash Hash of video frame
 * @param hardwareDNA Hardware fingerprint (PRNU)
 * @return True if session created successfully
 */
JNIEXPORT jboolean JNICALL
Java_com_biovault_BioVaultModule_initConsensusSession(
    JNIEnv* env,
    jobject /* this */,
    jstring sessionId,
    jintArray expectedFaceIds,
    jbyteArray videoFrameHash,
    jstring hardwareDNA) {
    
    // Convert sessionId
    const char* sessionIdChars = env->GetStringUTFChars(sessionId, nullptr);
    std::string sessionIdStr(sessionIdChars);
    env->ReleaseStringUTFChars(sessionId, sessionIdChars);
    
    // Convert face IDs
    jsize faceIdCount = env->GetArrayLength(expectedFaceIds);
    std::vector<int> faceIds(faceIdCount);
    env->GetIntArrayRegion(expectedFaceIds, 0, faceIdCount, faceIds.data());
    
    // Convert video frame hash
    jsize hashLength = env->GetArrayLength(videoFrameHash);
    std::vector<uint8_t> frameHash(hashLength);
    env->GetByteArrayRegion(videoFrameHash, 0, hashLength,
                           reinterpret_cast<jbyte*>(frameHash.data()));
    
    // Convert hardware DNA
    const char* hwDNAChars = env->GetStringUTFChars(hardwareDNA, nullptr);
    std::string hwDNAStr(hwDNAChars);
    env->ReleaseStringUTFChars(hardwareDNA, hwDNAChars);
    
    // Create consensus session
    auto session = std::make_unique<biovault::consensus::ConsensusHandshake>(
        faceIds, frameHash, hwDNAStr, 5.0);
    
    g_consensusSessions[sessionIdStr] = std::move(session);
    
    return JNI_TRUE;
}

/**
 * Append BLE signature to consensus session
 * @param sessionId Session identifier
 * @param faceId Face ID of the signatory
 * @param bpm Heart rate of the signatory
 * @param signature Ed25519 signature bytes
 * @param publicKey Ed25519 public key bytes
 * @return True if signature appended successfully
 */
JNIEXPORT jboolean JNICALL
Java_com_biovault_BioVaultModule_appendConsensusSignature(
    JNIEnv* env,
    jobject /* this */,
    jstring sessionId,
    jint faceId,
    jint bpm,
    jbyteArray signature,
    jbyteArray publicKey) {
    
    // Get session ID
    const char* sessionIdChars = env->GetStringUTFChars(sessionId, nullptr);
    std::string sessionIdStr(sessionIdChars);
    env->ReleaseStringUTFChars(sessionId, sessionIdChars);
    
    // Find session
    auto it = g_consensusSessions.find(sessionIdStr);
    if (it == g_consensusSessions.end()) {
        return JNI_FALSE;
    }
    
    // Convert signature
    jsize sigLength = env->GetArrayLength(signature);
    std::vector<uint8_t> sigBytes(sigLength);
    env->GetByteArrayRegion(signature, 0, sigLength,
                           reinterpret_cast<jbyte*>(sigBytes.data()));
    
    // Convert public key
    jsize pkLength = env->GetArrayLength(publicKey);
    std::vector<uint8_t> pkBytes(pkLength);
    env->GetByteArrayRegion(publicKey, 0, pkLength,
                           reinterpret_cast<jbyte*>(pkBytes.data()));
    
    // Build BLESignature struct
    biovault::consensus::BLESignature bleSig;
    bleSig.faceId = faceId;
    bleSig.bpm = bpm;
    bleSig.signature = sigBytes;
    bleSig.publicKey = pkBytes;
    bleSig.receivedAt = biovault::crypto::CryptoUtils::getCurrentTimestamp();
    
    // Append to session
    bool success = it->second->appendSignature(bleSig);
    
    return success ? JNI_TRUE : JNI_FALSE;
}

/**
 * Finalize consensus and get result
 * @param sessionId Session identifier
 * @return JSON string with consensus result or null if session not found
 */
JNIEXPORT jstring JNICALL
Java_com_biovault_BioVaultModule_finalizeConsensus(
    JNIEnv* env,
    jobject /* this */,
    jstring sessionId) {
    
    // Get session ID
    const char* sessionIdChars = env->GetStringUTFChars(sessionId, nullptr);
    std::string sessionIdStr(sessionIdChars);
    env->ReleaseStringUTFChars(sessionId, sessionIdChars);
    
    // Find session
    auto it = g_consensusSessions.find(sessionIdStr);
    if (it == g_consensusSessions.end()) {
        return nullptr;
    }
    
    // Finalize and get consensus hash
    std::string consensusHash = it->second->finalizeConsensusHash();
    biovault::consensus::ConsensusResult result = it->second->getResult();
    
    // Build JSON result
    std::string statusStr;
    switch (result.status) {
        case biovault::consensus::ConsensusStatus::COMPLETE:
            statusStr = "COMPLETE";
            break;
        case biovault::consensus::ConsensusStatus::TIMEOUT:
            statusStr = "TIMEOUT";
            break;
        case biovault::consensus::ConsensusStatus::STATUS_UNVERIFIED:
            statusStr = "STATUS_UNVERIFIED";
            break;
        default:
            statusStr = "PENDING";
    }
    
    std::string json = "{";
    json += "\"status\":\"" + statusStr + "\",";
    json += "\"consensusHash\":\"" + consensusHash + "\",";
    json += "\"expectedSignatures\":" + std::to_string(result.expectedSignatures) + ",";
    json += "\"receivedSignatures\":" + std::to_string(result.receivedSignatures) + ",";
    json += "\"elapsedSeconds\":" + std::to_string(result.elapsedSeconds);
    json += "}";
    
    // Cleanup session
    g_consensusSessions.erase(it);
    
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
