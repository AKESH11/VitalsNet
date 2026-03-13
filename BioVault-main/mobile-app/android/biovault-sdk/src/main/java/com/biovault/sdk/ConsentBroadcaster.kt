package com.biovault.sdk

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE consent protocol for BioVault — advertisement-only approach.
 *
 * ONE unified scan handles both passive listening ("R|") and approval receiving ("A|").
 * No GATT connections at all.
 *
 * Flow:
 *  1. Requester advertises "R|<session8>|<S/E>"; unified scan catches "A|..." responses
 *  2. Listener's unified scan catches "R|..." requests, shows popup
 *  3. Approver advertises "A|<session8>|Y" (or N) for a few seconds
 *  4. Requester's unified scan sees "A|...", fires approval callback
 */
@SuppressLint("MissingPermission")
class ConsentBroadcaster(private val context: Context) {
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter = bluetoothManager.adapter
    private val advertiser: BluetoothLeAdvertiser? = adapter.bluetoothLeAdvertiser
    private val scanner: BluetoothLeScanner? = adapter.bluetoothLeScanner

    private val serviceUuid: ParcelUuid =
        ParcelUuid(UUID.fromString("12345678-1234-1234-1234-1234567890ab"))

    private val handler = Handler(Looper.getMainLooper())

    // --- Single unified scan ---
    private var unifiedScanCallback: ScanCallback? = null

    // --- Requester state ---
    private var activeSessionId: String? = null
    private var activeSessionShort: String? = null
    private var timeoutRunnable: Runnable? = null
    private var requesterCallback: ConsentRequesterCallback? = null
    private var currentAdvertCallback: AdvertiseCallback? = null

    // --- Listener (passive scan) state ---
    private var listenerCallback: ConsentListenerCallback? = null
    private val discoveredRequests = ConcurrentHashMap<String, Long>()

    // --- Approver state ---
    private var approverAdvertCallback: AdvertiseCallback? = null
    private val approvedSessions = ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val TAG = "ConsentBroadcaster"
        private const val REQUEST_TIMEOUT_MS = 20000L
        private const val PASSIVE_DEDUP_MS = 60000L
        private const val APPROVER_ADVERT_DURATION_MS = 6000L
    }

    // --- Data classes ---

    data class ApprovalData(
        val approved: Boolean,
        val faceId: Int,
        val bpm: Int,
        val signature: ByteArray,
        val publicKey: ByteArray,
        val deviceAddress: String
    )

    data class BLESignatureData(
        val faceId: Int,
        val bpm: Int,
        val signature: ByteArray,
        val publicKey: ByteArray
    )

    // --- Callbacks ---

    interface ConsentRequesterCallback {
        fun onApprovalReceived(deviceAddress: String, approval: ApprovalData)
        fun onDenialReceived(deviceAddress: String)
        fun onRequestTimeout(approvalsReceived: Int)
    }

    interface ConsentListenerCallback {
        fun onConsentRequestDiscovered(deviceAddress: String, sessionId: String, category: String)
    }

    // =============================================================
    //  UNIFIED SCAN — single scan handles both R| and A| messages
    // =============================================================

    private fun ensureUnifiedScan() {
        if (unifiedScanCallback != null) return
        if (scanner == null || !adapter.isEnabled) {
            Log.w(TAG, "BLE scanner not available")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleUnifiedScanResult(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { handleUnifiedScanResult(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Unified BLE scan failed: errorCode=$errorCode")
            }
        }

        unifiedScanCallback = cb
        scanner.startScan(listOf(scanFilter), scanSettings, cb)
        Log.d(TAG, "Unified BLE scan started")
    }

    private fun stopUnifiedScan() {
        unifiedScanCallback?.let { scanner?.stopScan(it) }
        unifiedScanCallback = null
        Log.d(TAG, "Unified BLE scan stopped")
    }

    private fun handleUnifiedScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val addr = result.device.address
        val serviceData = scanRecord.getServiceData(serviceUuid) ?: return

        try {
            val payload = String(serviceData, StandardCharsets.UTF_8)

            when {
                payload.startsWith("R|") -> handleIncomingRequest(payload, addr)
                payload.startsWith("A|") -> handleIncomingApproval(payload, addr)
                else -> Log.d(TAG, "Unknown BLE payload: $payload from $addr")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse scan result: " + e.message)
        }
    }

    private fun handleIncomingRequest(payload: String, addr: String) {
        if (listenerCallback == null) return

        val parts = payload.split("|")
        if (parts.size < 3) return

        val sessionId = parts[1]
        val category = if (parts[2] == "E") "EXPLICIT" else "SENSITIVE"

        val now = System.currentTimeMillis()
        if (approvedSessions.containsKey(sessionId)) return
        val lastSeen = discoveredRequests[sessionId]
        if (lastSeen != null && (now - lastSeen) < PASSIVE_DEDUP_MS) return
        discoveredRequests[sessionId] = now

        Log.d(TAG, "Discovered consent request: session=$sessionId category=$category from=$addr")
        handler.post {
            listenerCallback?.onConsentRequestDiscovered(addr, sessionId, category)
        }
    }

    private fun handleIncomingApproval(payload: String, addr: String) {
        if (activeSessionId == null || requesterCallback == null) return

        val parts = payload.split("|")
        if (parts.size < 3) return

        val session = parts[1]
        val approved = parts[2] == "Y"

        if (session != activeSessionShort) return

        Log.d(TAG, "Received approval ad: session=$session approved=$approved from=$addr")

        val approval = ApprovalData(approved, 0, 0, ByteArray(64), ByteArray(32), addr)

        handler.post {
            if (approved) {
                requesterCallback?.onApprovalReceived(addr, approval)
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                timeoutRunnable = null
            } else {
                requesterCallback?.onDenialReceived(addr)
            }
        }
    }

    // =============================================================
    //  MODE 1: REQUESTER — advertise request, unified scan gets A|
    // =============================================================

    fun startConsentRequest(
        sessionId: String,
        category: String,
        callback: ConsentRequesterCallback
    ) {
        if (activeSessionId != null) {
            Log.d(TAG, "Stopping existing session before starting new one")
            stopConsentRequest()
        }

        activeSessionId = sessionId
        activeSessionShort = sessionId.take(8)
        requesterCallback = callback

        startRequestAdvert(sessionId, category)
        ensureUnifiedScan()

        val currentSession = sessionId
        timeoutRunnable = Runnable {
            if (activeSessionId != currentSession) return@Runnable
            Log.d(TAG, "Consent request timeout")
            callback.onRequestTimeout(0)
            stopConsentRequest()
        }
        handler.postDelayed(timeoutRunnable!!, REQUEST_TIMEOUT_MS)
        Log.d(TAG, "Consent request started: session=$sessionId category=$category")
    }

    fun stopConsentRequest() {
        stopRequestAdvert()
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        activeSessionId = null
        activeSessionShort = null
        requesterCallback = null
        Log.d(TAG, "Consent request stopped")
    }

    private fun startRequestAdvert(sessionId: String, category: String) {
        if (advertiser == null || !adapter.isEnabled) return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val shortSession = sessionId.take(8)
        val catChar = if (category.startsWith("E")) "E" else "S"
        val payload = "R|$shortSession|$catChar".toByteArray(StandardCharsets.UTF_8)

        val advData = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(serviceUuid, payload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Request advertising started (connectable=true)")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Request advertising failed: $errorCode")
            }
        }
        currentAdvertCallback = cb
        advertiser.startAdvertising(settings, advData, scanResponse, cb)
    }

    private fun stopRequestAdvert() {
        currentAdvertCallback?.let { advertiser?.stopAdvertising(it) }
        currentAdvertCallback = null
    }

    // =============================================================
    //  MODE 2: LISTENER — starts unified scan, handles R| messages
    // =============================================================

    fun startPassiveScanning(callback: ConsentListenerCallback) {
        listenerCallback = callback
        discoveredRequests.clear()
        ensureUnifiedScan()
        Log.d(TAG, "Passive consent scanning started")
    }

    fun stopPassiveScanning() {
        listenerCallback = null
        discoveredRequests.clear()
        if (activeSessionId == null) {
            stopUnifiedScan()
        }
        Log.d(TAG, "Passive consent scanning stopped")
    }

    // =============================================================
    //  MODE 3: APPROVER — advertise response "A|<session>|Y/N"
    // =============================================================

    fun respondToConsentRequest(
        sessionId: String,
        approved: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        approvedSessions[sessionId] = true
        Log.d(TAG, "respondToConsentRequest: session=$sessionId approved=$approved")

        if (advertiser == null || !adapter.isEnabled) {
            Log.e(TAG, "Advertiser not available")
            handler.post { onComplete(false) }
            return
        }

        val responseChar = if (approved) "Y" else "N"
        val payload = "A|$sessionId|$responseChar".toByteArray(StandardCharsets.UTF_8)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val advData = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(serviceUuid, payload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "Approval ad started for session=$sessionId")
                handler.post { onComplete(true) }
                handler.postDelayed({
                    advertiser.stopAdvertising(this)
                    approverAdvertCallback = null
                    Log.d(TAG, "Approval ad stopped for session=$sessionId")
                }, APPROVER_ADVERT_DURATION_MS)
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "Approval ad failed: $errorCode")
                approverAdvertCallback = null
                handler.post { onComplete(false) }
            }
        }

        approverAdvertCallback?.let { advertiser.stopAdvertising(it) }
        approverAdvertCallback = cb
        advertiser.startAdvertising(settings, advData, scanResponse, cb)
    }

    // =============================================================
    //  Legacy API (backward compat)
    // =============================================================

    fun startConsensusSession(
        sessionId: String,
        expectedFaceCount: Int,
        myBpm: Int,
        myFaceId: Int = 0,
        mySignature: ByteArray = ByteArray(64),
        myPublicKey: ByteArray = ByteArray(32),
        callback: ConsensusCallback
    ) {
        startConsentRequest(sessionId, "SENSITIVE", object : ConsentRequesterCallback {
            override fun onApprovalReceived(deviceAddress: String, approval: ApprovalData) {
                val sigs = listOf(BLESignatureData(approval.faceId, approval.bpm, approval.signature, approval.publicKey))
                callback.onConsensusComplete("ble_approved_$sessionId", sigs)
            }
            override fun onDenialReceived(deviceAddress: String) {
                callback.onConsensusTimeout(0, expectedFaceCount)
            }
            override fun onRequestTimeout(approvalsReceived: Int) {
                callback.onConsensusTimeout(approvalsReceived, expectedFaceCount)
            }
        })
    }

    fun stopConsensusSession() {
        stopConsentRequest()
        stopPassiveScanning()
    }

    interface ConsensusCallback {
        fun onConsensusComplete(consensusHash: String, signatures: List<BLESignatureData>)
        fun onConsensusTimeout(receivedCount: Int, expectedCount: Int)
    }

    // =============================================================
    //  PRIVACY SHIELD — VitalsID beacon broadcast & scan
    // =============================================================

    private var vitalsIdAdvertCallback: AdvertiseCallback? = null
    private var vitalsIdScanCallback: ScanCallback? = null
    private val detectedVitalsIds = ConcurrentHashMap<String, Long>()
    private var vitalsIdScanListener: VitalsIdScanListener? = null

    interface VitalsIdScanListener {
        fun onVitalsIdDetected(vitalsIdHash: String, rssi: Int)
    }

    /**
     * Start broadcasting this device's VitalsID hash via BLE advertisements.
     * Payload: "V|<first8charsOfHash>"
     */
    fun startVitalsIdBroadcast(vitalsIdHash: String) {
        if (advertiser == null || !adapter.isEnabled) {
            Log.w(TAG, "Cannot start VitalsID broadcast — advertiser unavailable")
            return
        }
        stopVitalsIdBroadcast()

        val shortHash = vitalsIdHash.take(8)
        val payload = "V|$shortHash".toByteArray(StandardCharsets.UTF_8)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()

        val advData = AdvertiseData.Builder()
            .addServiceUuid(serviceUuid)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .addServiceData(serviceUuid, payload)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "VitalsID broadcast started: V|$shortHash")
            }
            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "VitalsID broadcast failed: $errorCode")
            }
        }
        vitalsIdAdvertCallback = cb
        advertiser.startAdvertising(settings, advData, scanResponse, cb)
    }

    fun stopVitalsIdBroadcast() {
        vitalsIdAdvertCallback?.let { advertiser?.stopAdvertising(it) }
        vitalsIdAdvertCallback = null
    }

    /**
     * Scan for nearby VitalsID beacons ("V|<hash>").
     * Returns detected IDs via callback + stores them for later retrieval.
     */
    fun startVitalsIdScan(listener: VitalsIdScanListener? = null) {
        if (scanner == null || !adapter.isEnabled) {
            Log.w(TAG, "Cannot start VitalsID scan — scanner unavailable")
            return
        }
        stopVitalsIdScan()
        vitalsIdScanListener = listener
        detectedVitalsIds.clear()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(serviceUuid)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleVitalsIdScanResult(result)
            }
            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { handleVitalsIdScanResult(it) }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "VitalsID scan failed: $errorCode")
            }
        }
        vitalsIdScanCallback = cb
        scanner.startScan(listOf(scanFilter), scanSettings, cb)
        Log.d(TAG, "VitalsID scan started")
    }

    fun stopVitalsIdScan() {
        vitalsIdScanCallback?.let { scanner?.stopScan(it) }
        vitalsIdScanCallback = null
        vitalsIdScanListener = null
    }

    fun getDetectedVitalsIds(): Map<String, Long> = HashMap(detectedVitalsIds)

    fun clearDetectedVitalsIds() { detectedVitalsIds.clear() }

    private fun handleVitalsIdScanResult(result: ScanResult) {
        val scanRecord = result.scanRecord ?: return
        val serviceData = scanRecord.getServiceData(serviceUuid) ?: return

        try {
            val payload = String(serviceData, StandardCharsets.UTF_8)
            if (!payload.startsWith("V|")) return

            val vitalsId = payload.substring(2) // hash portion
            val now = System.currentTimeMillis()
            val lastSeen = detectedVitalsIds[vitalsId]
            if (lastSeen != null && (now - lastSeen) < 5000) return // dedup 5s

            detectedVitalsIds[vitalsId] = now
            Log.d(TAG, "Detected VitalsID beacon: $vitalsId rssi=${result.rssi}")

            handler.post {
                vitalsIdScanListener?.onVitalsIdDetected(vitalsId, result.rssi)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VitalsID scan parse error: ${e.message}")
        }
    }

    val isVitalsIdBroadcasting: Boolean
        get() = vitalsIdAdvertCallback != null
}
