import React, {useState, useEffect, useRef, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  PermissionsAndroid,
  Platform,
  NativeModules,
  NativeEventEmitter,
  Modal,
  ActivityIndicator,
} from 'react-native';
import {BioVaultCameraView} from '../components/BioVaultCameraView';
import RNFS from 'react-native-fs';

const {BioVaultModule} = NativeModules;

// Directory for BioVault media files
const MEDIA_DIR = RNFS.DocumentDirectoryPath + '/biovault/media';

export default function CameraScreen({navigation}) {
  const [isRecording, setIsRecording] = useState(false);
  const [bpm, setBpm] = useState(0);
  const [confidence, setConfidence] = useState(0);
  const [facesDetected, setFacesDetected] = useState(0);
  const [duration, setDuration] = useState(0);
  const [hasPermission, setHasPermission] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [faceBox, setFaceBox] = useState(null);
  
  const cameraRef = useRef(null);
  const startTimeRef = useRef(null);
  const recordingDataRef = useRef({
    frames: [],
    bpmReadings: [],
    startTime: null
  });

  // BLE consent is always active — no toggle needed
  const [consentResult, setConsentResult] = useState(null);
  const consentResultRef = useRef(null);

  // Consent overlay (shown when content is SENSITIVE/EXPLICIT — IN REAL TIME during recording)
  const [showConsentOverlay, setShowConsentOverlay] = useState(false);
  const [consentCountdown, setConsentCountdown] = useState(15);
  const [consentStatus, setConsentStatus] = useState('waiting'); // waiting | approved | denied | timeout
  const [bleSessionActive, setBleSessionActive] = useState(false);
  const pendingNavigationRef = useRef(null); // holds nav params while consent is pending

  // Refs that mirror state — used inside setInterval closures to avoid stale captures
  const consentOverlayRef = useRef(false);
  const bleSessionRef = useRef(false);
  const consentTriggeredRef = useRef(false); // true once consent session started for this recording

  // Live content classification during recording
  const [liveCategory, setLiveCategory] = useState(null); // null | SAFE | SENSITIVE | EXPLICIT
  const [liveLabel, setLiveLabel] = useState('');
  const [liveConfidence, setLiveConfidence] = useState(0);
  const consentApprovedRef = useRef(false); // true once BLE approval received

  // Incoming consent request from ANOTHER device (we are the bystander)
  const [incomingConsentRequest, setIncomingConsentRequest] = useState(null);
  const respondedAddressesRef = useRef(new Set()); // addresses we already responded to

  useEffect(() => {
    let approvalSub, requestSub;
    try {
      if (BioVaultModule) {
        const emitter = new NativeEventEmitter(NativeModules.BioVaultModule);

        // EVENT 1: We are the RECORDER — a nearby device approved/denied/timed out
        approvalSub = emitter.addListener('onConsentApprovalReceived', (event) => {
          console.log('[BioVault] Consent response received:', JSON.stringify(event));
          setConsentResult(event);
          consentResultRef.current = event;
        });

        // EVENT 2: We are the BYSTANDER — a nearby device is requesting consent
        requestSub = emitter.addListener('onConsentRequestReceived', (event) => {
          console.log('[BioVault] Incoming consent request:', JSON.stringify(event));
          // Don't show popup if we already responded to this device
          if (event?.deviceAddress && respondedAddressesRef.current.has(event.deviceAddress)) {
            console.log('[BioVault] Ignoring repeat request from', event.deviceAddress);
            return;
          }
          setIncomingConsentRequest(event);
        });

        // Passive scanning is started after permissions are granted (see requestCameraPermission)
      }
    } catch (_) {}
    return () => {
      if (approvalSub) approvalSub.remove();
      if (requestSub) requestSub.remove();
      try { BioVaultModule?.stopPassiveConsentScan?.(); } catch (_) {}
    };
  }, []);

  useEffect(() => {
    requestCameraPermission();
    initializeNativeModule();
  }, []);

  useEffect(() => {
    let timer;
    if (isRecording) {
      timer = setInterval(() => {
        const elapsed = Math.floor((Date.now() - startTimeRef.current) / 1000);
        setDuration(elapsed);
        
        if (elapsed >= 30) {
          stopRecording();
        }
      }, 1000);
    }
    return () => clearInterval(timer);
  }, [isRecording]);

  // ── Periodic content classification during recording (every 5s) ──
  useEffect(() => {
    if (!isRecording) {
      setLiveCategory(null);
      setLiveLabel('');
      setLiveConfidence(0);
      consentApprovedRef.current = false;
      consentTriggeredRef.current = false;
      return;
    }
    let cancelled = false;
    const classifyInterval = setInterval(async () => {
      if (cancelled || !BioVaultModule?.classifyCurrentFrame) return;
      // Use REFS (not state) to avoid stale closure captures
      if (consentTriggeredRef.current || consentOverlayRef.current || bleSessionRef.current) return;
      try {
        const resultStr = await BioVaultModule.classifyCurrentFrame();
        if (cancelled) return;
        const result = JSON.parse(resultStr);
        setLiveCategory(result.category);
        setLiveLabel(result.label);
        setLiveConfidence(result.confidence);
        console.log('[BioVault] Live classify:', result.category, result.label, result.confidence + '%');

        // If flagged and consent not yet given, trigger consent ONCE per recording
        if (result.requiresConsent && !consentApprovedRef.current && !consentTriggeredRef.current) {
          consentTriggeredRef.current = true; // lock — never re-trigger
          setConsentCountdown(20);
          setConsentStatus('waiting');
          setShowConsentOverlay(true);
          consentOverlayRef.current = true;
          // Broadcast consent request via BLE
          try {
            const sid = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
            await BioVaultModule.startBLEConsentSession(sid, result.category || 'SENSITIVE');
            setBleSessionActive(true);
            bleSessionRef.current = true;
          } catch (_) {
            setBleSessionActive(false);
          }
        }
      } catch (err) {
        console.warn('[BioVault] Live classify error:', err.message);
      }
    }, 5000); // classify every 5 seconds

    // Also run once after 3 seconds for early detection
    const earlyTimer = setTimeout(async () => {
      if (cancelled || !BioVaultModule?.classifyCurrentFrame) return;
      if (consentTriggeredRef.current || consentOverlayRef.current || bleSessionRef.current) return;
      try {
        const resultStr = await BioVaultModule.classifyCurrentFrame();
        if (cancelled) return;
        const result = JSON.parse(resultStr);
        setLiveCategory(result.category);
        setLiveLabel(result.label);
        setLiveConfidence(result.confidence);
        if (result.requiresConsent && !consentApprovedRef.current && !consentTriggeredRef.current) {
          consentTriggeredRef.current = true;
          setConsentCountdown(20);
          setConsentStatus('waiting');
          setShowConsentOverlay(true);
          consentOverlayRef.current = true;
          try {
            const sid = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
            await BioVaultModule.startBLEConsentSession(sid, result.category || 'SENSITIVE');
            setBleSessionActive(true);
            bleSessionRef.current = true;
          } catch (_) { setBleSessionActive(false); }
        }
      } catch (_) {}
    }, 3000);

    return () => {
      cancelled = true;
      clearInterval(classifyInterval);
      clearTimeout(earlyTimer);
    };
  }, [isRecording]);

  const requestCameraPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Camera Permission',
            message: 'BioVault needs camera access for bio-authentication',
            buttonPositive: 'OK',
          }
        );
        setHasPermission(granted === PermissionsAndroid.RESULTS.GRANTED);

        // Request BLE + Location permissions for consent flow
        try {
          const btPerms = [
            PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE,
            PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
            PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
            PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          ].filter(Boolean); // filter undefined on older APIs
          if (btPerms.length > 0) {
            await PermissionsAndroid.requestMultiple(btPerms);
          }
        } catch (btErr) {
          console.warn('[BioVault] BT permission request failed (non-fatal):', btErr.message);
        }

        // Now that permissions are granted, start passive BLE scanning
        // so this device can receive consent requests from nearby recorders
        try {
          if (BioVaultModule?.startPassiveConsentScan) {
            await BioVaultModule.startPassiveConsentScan();
            console.log('[BioVault] Passive consent scanning started');
          }
        } catch (passiveErr) {
          console.warn('[BioVault] Passive scan start failed:', passiveErr.message);
        }
      } catch (err) {
        console.warn(err);
        setHasPermission(false);
      }
    } else {
      setHasPermission(true);
    }
  };

  const initializeNativeModule = async () => {
    try {
      if (BioVaultModule && BioVaultModule.initializeCamera) {
        await BioVaultModule.initializeCamera('');
        console.log('Native camera initialized');
      }
      // Start PRNU capture as soon as camera is ready
      if (BioVaultModule && BioVaultModule.startPRNUCapture) {
        await BioVaultModule.startPRNUCapture();
        console.log('[BioVault] PRNU capture started');
      }
    } catch (error) {
      console.error('Failed to initialize camera:', error);
    }
  };

  const onFrameProcessed = (event) => {
    if (!isRecording) return;
    
    const {bpm: newBpm, confidence: newConf, faces} = event.nativeEvent;
    
    if (newBpm > 0 && newBpm < 200) {
      setBpm(Math.round(newBpm));
      recordingDataRef.current.bpmReadings.push(newBpm);
    }
    
    if (newConf !== undefined) {
      setConfidence(Math.round(newConf * 100));
    }
    
    if (faces !== undefined) {
      setFacesDetected(faces);
    }
    
    recordingDataRef.current.frames.push({
      timestamp: Date.now(),
      bpm: newBpm,
      confidence: newConf,
    });
  };

  const startRecording = async () => {
    if (!hasPermission) {
      Alert.alert('Permission Required', 'Camera permission is required.');
      return;
    }
    
    try {
      setIsRecording(true);
      startTimeRef.current = Date.now();
      recordingDataRef.current = {
        frames: [],
        bpmReadings: [],
        startTime: Date.now()
      };
      
      // Start rPPG session in native code
      try {
        if (BioVaultModule && BioVaultModule.startRPPGExtraction) {
          const success = await BioVaultModule.startRPPGExtraction();
          console.log('[BioVault] rPPG extraction started:', success);
        }
      } catch (error) {
        console.error('[BioVault] Failed to start rPPG:', error);
      }
      
      // Fallback: if no real rPPG data arrives within 15 seconds, warn user.
      // We do NOT simulate BPM — the recording continues and will simply have
      // fewer BPM samples.  The "Recording Failed" alert fires on stop if
      // bpmReadings is still empty.
      const fallbackTimerId = setTimeout(() => {
        if (recordingDataRef.current.bpmReadings.length === 0) {
          console.warn('[BioVault] 15s elapsed with no native rPPG data — rPPG may not be working');
        }
      }, 15000);
      
      recordingDataRef.current.fallbackTimerId = fallbackTimerId;

      // BLE consent request is NOT started here — it's started dynamically
      // when the periodic content classifier detects SENSITIVE/EXPLICIT content.
      
    } catch (error) {
      console.error('[BioVault] Error starting recording:', error);
      Alert.alert('Recording Error', 'Failed to start recording: ' + error.message);
      setIsRecording(false);
    }
  };

  const stopRecording = async () => {
   try {
    setIsRecording(false);
    
    // Clear fallback timer if it exists
    if (recordingDataRef.current.fallbackTimerId) {
      clearTimeout(recordingDataRef.current.fallbackTimerId);
    }
    
    // Clear simulation interval
    if (recordingDataRef.current.intervalId) {
      clearInterval(recordingDataRef.current.intervalId);
    }
    
    // Stop rPPG session — returns real BPM, content hash, hardware DNA
    let rppgResult = null;
    try {
      if (BioVaultModule && BioVaultModule.stopRPPGExtraction) {
        const resultStr = await BioVaultModule.stopRPPGExtraction();
        console.log('[BioVault] rPPG extraction stopped:', resultStr);
        try {
          rppgResult = typeof resultStr === 'string' ? JSON.parse(resultStr) : resultStr;
        } catch (_) {
          rppgResult = null;
        }
      }
    } catch (error) {
      console.error('[BioVault] Failed to stop rPPG:', error);
      // Continue anyway
    }

    // Stop BLE consensus session
    try {
      if (BioVaultModule && BioVaultModule.stopBLEConsentSession) {
        await BioVaultModule.stopBLEConsentSession();
      }
    } catch (_bleErr) {}
    
    const {bpmReadings, frames} = recordingDataRef.current;
    
    if (bpmReadings.length === 0) {
      Alert.alert(
        'Recording Failed',
        'No valid heart rate data detected. Please ensure good lighting and remain still.',
        [{text: 'OK'}]
      );
      return;
    }
    
    const avgBpm = bpmReadings.reduce((a, b) => a + b, 0) / bpmReadings.length;
    const minBpm = Math.min(...bpmReadings);
    const maxBpm = Math.max(...bpmReadings);
    const variance = bpmReadings.reduce((sum, val) => sum + Math.pow(val - avgBpm, 2), 0) / bpmReadings.length;
    const stdDev = Math.sqrt(variance);
    const finalConfidence = Math.max(0, Math.min(100, 100 - (stdDev * 2)));

    // Build navigation params (shared between consent / no-consent paths)
    const buildNavParams = async (consentData) => {
      let videoHash = rppgResult?.videoHash || '';
      let bioSignature = rppgResult?.bioSignature || '';
      let hardwareDNA = rppgResult?.hardwareDNA || '';
      let proofOfRealityHash = '';

      try {
        if (BioVaultModule && BioVaultModule.generateProofOfReality) {
          const proofResult = await BioVaultModule.generateProofOfReality(Math.round(avgBpm));
          proofOfRealityHash = proofResult?.proofOfRealityHash || '';
          if (!videoHash) videoHash = proofResult?.videoHash || '';
          if (!hardwareDNA) hardwareDNA = proofResult?.hardwareID || '';
        }
      } catch (nativeError) {
        console.warn('Native proof-of-reality failed:', nativeError.message);
      }

      let hardwareFingerprint = hardwareDNA;
      if (!hardwareFingerprint) {
        try {
          if (BioVaultModule && BioVaultModule.getHardwareFingerprint) {
            hardwareFingerprint = await BioVaultModule.getHardwareFingerprint();
            hardwareDNA = hardwareFingerprint;
          }
        } catch (_) {}
      }

      console.log('[BioVault] Proof data — videoHash:', videoHash?.substring(0, 18) + '...',
        'BPM:', rppgResult?.averageBPM, 'frames:', rppgResult?.frameCount,
        'DNA:', hardwareDNA?.substring(0, 16) + '...');

      // Embed watermark
      let watermarkedImageBase64 = '';
      try {
        if (BioVaultModule && BioVaultModule.captureLastFrame && BioVaultModule.embedWatermark) {
          const frameBase64 = await BioVaultModule.captureLastFrame();
          if (frameBase64) {
            const wmPayload = JSON.stringify({
              id: Date.now().toString(36).slice(-6),
              dna: (hardwareDNA || '').substring(0, 8),
              ts: Math.floor(Date.now() / 1000),
              bpm: Math.round(avgBpm),
            });
            watermarkedImageBase64 = await BioVaultModule.embedWatermark(frameBase64, wmPayload);
            console.log('[BioVault] Watermark embedded:', wmPayload, 'imgLen:', watermarkedImageBase64?.length);
          }
        }
      } catch (wmErr) {
        console.warn('[BioVault] Watermark embed failed:', wmErr.message);
      }

      // Save recording
      let mediaFilePath = '';
      try {
        await RNFS.mkdir(MEDIA_DIR);
        const recordingId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
        const recordingData = {
          id: recordingId,
          timestamp: Date.now(),
          bpm: Math.round(avgBpm),
          confidence: Math.round(finalConfidence),
          duration,
          facesDetected,
          framesProcessed: frames.length,
          statistics: {
            min: Math.round(minBpm),
            max: Math.round(maxBpm),
            stdDev: parseFloat(stdDev.toFixed(2)),
            readings: bpmReadings.length,
          },
          proofOfReality: {
            videoHash,
            bioSignature,
            hardwareDNA,
            proofOfRealityHash,
          },
        };
        mediaFilePath = MEDIA_DIR + '/' + recordingId + '.json';
        await RNFS.writeFile(mediaFilePath, JSON.stringify(recordingData, null, 2), 'utf8');
      } catch (fsError) {
        console.warn('[BioVault] Failed to save recording file:', fsError.message);
      }

      return {
        bpm: Math.round(avgBpm),
        confidence: Math.round(finalConfidence),
        duration,
        facesDetected,
        framesProcessed: frames.length,
        statistics: {
          min: Math.round(minBpm),
          max: Math.round(maxBpm),
          stdDev: stdDev.toFixed(2),
        },
        videoHash,
        bioSignature,
        hardwareDNA,
        hardwareFingerprint,
        proofOfRealityHash,
        mediaFilePath,
        watermarkedImageBase64,
        contentCategory: rppgResult?.contentCategory || 'SAFE',
        requiresConsent: rppgResult?.requiresConsent || false,
        contentLabel: rppgResult?.contentLabel || '',
        contentConfidence: rppgResult?.contentConfidence || 0,
        consentParties: consentData || consentResultRef.current
          ? {
              consensusHash: (consentData || consentResultRef.current)?.consensusHash || '',
              signaturesReceived: (consentData || consentResultRef.current)?.signaturesReceived || 0,
              consentMethod: consentData?.consentMethod || 'ble',
              consentTimestamp: consentData?.consentTimestamp || Date.now(),
            }
          : null,
      };
    };

    const contentCategory = rppgResult?.contentCategory || liveCategory || 'SAFE';
    const needsConsent = (rppgResult?.requiresConsent || false) || (liveCategory === 'SENSITIVE' || liveCategory === 'EXPLICIT');

    // If consent was already approved mid-recording via BLE, proceed directly
    if (consentApprovedRef.current && consentResultRef.current?.approved) {
      const consentData = consentResultRef.current;
      try {
        const navParams = await buildNavParams(consentData);
        // Override category with live classification if rPPG didn't flag it
        if (liveCategory && liveCategory !== 'SAFE') {
          navParams.contentCategory = liveCategory;
          navParams.requiresConsent = true;
          navParams.contentLabel = liveLabel || navParams.contentLabel;
          navParams.contentConfidence = liveConfidence || navParams.contentConfidence;
        }
        navigation.navigate('Results', navParams);
      } catch (navErr) {
        console.error('[BioVault] Navigation error:', navErr);
        Alert.alert('Error', 'Failed to load results.');
      }
      return;
    }

    if (needsConsent) {
      // Content is SENSITIVE or EXPLICIT — show consent overlay and wait for BLE approval
      try {
        setIsProcessing(true);
        const navParams = await buildNavParams(null);
        setIsProcessing(false);
        pendingNavigationRef.current = navParams;
        setConsentCountdown(20);
        setConsentStatus('waiting');
        setShowConsentOverlay(true);
        consentOverlayRef.current = true;

        // Broadcast consent request via BLE — nearby devices will see this
        try {
          const sessionId = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
          await BioVaultModule.startBLEConsentSession(sessionId, contentCategory);
          setBleSessionActive(true);
          bleSessionRef.current = true;
          console.log('[BioVault] Consent BLE request broadcast:', sessionId);
        } catch (bleErr) {
          console.warn('[BioVault] BLE consent broadcast failed:', bleErr.message);
          setBleSessionActive(false);
        }
      } catch (consentErr) {
        console.error('[BioVault] Consent flow error:', consentErr);
        setIsProcessing(false);
        setShowConsentOverlay(false);
        consentOverlayRef.current = false;
        // No fallback — consent is mandatory. Alert user.
        Alert.alert(
          'Consent Required',
          'Could not initiate consent flow. The recording cannot be saved without consent from nearby parties.',
          [{text: 'OK'}]
        );
      }
    } else {
      // SAFE content — proceed normally
      Alert.alert(
        'Bio-Signature Extracted',
        `Recording complete!\n\nAverage BPM: ${Math.round(avgBpm)}\nConfidence: ${Math.round(finalConfidence)}%`,
        [{
          text: 'View Results',
          onPress: async () => {
            try {
              const navParams = await buildNavParams(null);
              navigation.navigate('Results', navParams);
            } catch (navErr) {
              console.error('[BioVault] Navigation error:', navErr);
              Alert.alert('Error', 'Failed to load results.');
            }
          },
        }]
      );
    }
   } catch (fatalErr) {
    console.error('[BioVault] stopRecording fatal error:', fatalErr);
    setIsRecording(false);
    setIsProcessing(false);
    setShowConsentOverlay(false);
    consentOverlayRef.current = false;
    Alert.alert('Error', 'An error occurred while processing. Please try again.');
   }
  };

  // Consent overlay countdown timer
  useEffect(() => {
    if (!showConsentOverlay || consentStatus !== 'waiting') return;
    if (consentCountdown <= 0) {
      // Timeout — no BLE approval received. Auto-deny and delete capture.
      setConsentStatus('timeout');
      pendingNavigationRef.current = null;
      // Stop recording if still active
      if (isRecording) {
        setIsRecording(false);
        try { BioVaultModule?.stopRPPGExtraction?.(); } catch (_) {}
      }
      try { BioVaultModule?.stopBLEConsentSession?.(); } catch (_) {}
      setBleSessionActive(false);
      bleSessionRef.current = false;
      // Alert user after a brief delay
      setTimeout(() => {
        setShowConsentOverlay(false);
        consentOverlayRef.current = false;
        Alert.alert(
          'No Consent Received',
          'No nearby BioVault device approved this recording within the time limit.\n\nThe capture has been discarded. Sensitive/explicit content requires real consent from all parties.',
          [{text: 'OK'}]
        );
      }, 1500);
      return;
    }
    const timer = setTimeout(() => setConsentCountdown(c => c - 1), 1000);
    return () => clearTimeout(timer);
  }, [showConsentOverlay, consentCountdown, consentStatus]);

  // Handle BLE consent approval received from nearby device
  useEffect(() => {
    if (showConsentOverlay && consentResult?.approved === true && consentStatus === 'waiting') {
      handleBLEApproval();
    }
    // If nearby device explicitly denied
    if (showConsentOverlay && consentResult?.approved === false && consentStatus === 'waiting') {
      handleConsentDeny();
    }
    // If the consent request timed out on the native side
    if (showConsentOverlay && consentResult?.timeout === true && consentStatus === 'waiting') {
      // Let the JS countdown handle the timeout UI
    }
  }, [consentResult, showConsentOverlay, consentStatus]);

  const handleBLEApproval = () => {
    setConsentStatus('approved');
    consentApprovedRef.current = true;
    try { BioVaultModule?.stopBLEConsentSession?.(); } catch (_) {}
    setBleSessionActive(false);
    bleSessionRef.current = false;

    const consentData = {
      approved: true,
      deviceAddress: consentResultRef.current?.deviceAddress || 'ble-device',
      sessionId: consentResultRef.current?.sessionId || '',
      consentMethod: 'ble',
      consentTimestamp: Date.now(),
    };

    // If recording is still active, just dismiss overlay — recording continues
    if (isRecording) {
      consentResultRef.current = consentData;
      setTimeout(() => {
        setShowConsentOverlay(false);
        consentOverlayRef.current = false;
      }, 800);
      return;
    }

    // If recording already stopped (consent shown post-stop), navigate to Results
    setTimeout(() => {
      setShowConsentOverlay(false);
      consentOverlayRef.current = false;
      const navParams = pendingNavigationRef.current;
      if (navParams) {
        navParams.consentParties = consentData;
        navigation.navigate('Results', navParams);
        pendingNavigationRef.current = null;
      }
    }, 800);
  };

  const handleConsentDeny = () => {
    setConsentStatus('denied');
    try { BioVaultModule?.stopBLEConsentSession?.(); } catch (_) {}
    setBleSessionActive(false);
    bleSessionRef.current = false;
    pendingNavigationRef.current = null;

    // If recording is still active, stop it
    if (isRecording) {
      setIsRecording(false);
      try {
        if (BioVaultModule?.stopRPPGExtraction) BioVaultModule.stopRPPGExtraction();
      } catch (_) {}
    }

    setTimeout(() => {
      setShowConsentOverlay(false);
      consentOverlayRef.current = false;
      Alert.alert(
        'Capture Deleted',
        'The recorded media has been discarded because consent was denied.\n\nBioVault requires approval for sensitive/explicit content.',
        [{text: 'OK'}]
      );
    }, 500);
  };

  if (!hasPermission) {
    return (
      <View style={styles.container}>
        <View style={styles.errorContainer}>
          <Text style={styles.errorIcon}>📷</Text>
          <Text style={styles.errorTitle}>Camera Permission Required</Text>
          <Text style={styles.errorText}>
            BioVault needs camera access to extract bio-signatures using rPPG.
          </Text>
          <TouchableOpacity
            style={styles.permissionButton}
            onPress={requestCameraPermission}>
            <Text style={styles.permissionButtonText}>Grant Permission</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  const handleCameraReady = () => {
    console.log('[BioVault] Camera is ready!');
    Alert.alert('Camera Ready', 'Native camera initialized successfully!');
  };
  
  const handleCameraError = (event) => {
    console.error('[BioVault] Camera error:', event.nativeEvent.message);
    Alert.alert('Camera Error', event.nativeEvent.message);
  };
  
  const handleFrameAvailable = (event) => {
    if (!isRecording) return;
    
    const {width, height, timestamp, bpm: frameBpm, confidence: frameConfidence, facesDetected: frameFaces} = event.nativeEvent;
    
    // Update UI with native OpenCV results
    if (frameBpm !== undefined && frameBpm > 0 && frameBpm < 200) {
      setBpm(frameBpm);
      recordingDataRef.current.bpmReadings.push(frameBpm);
    }
    
    if (frameConfidence !== undefined) {
      setConfidence(Math.round(frameConfidence * 100));
    }
    
    if (frameFaces !== undefined) {
      setFacesDetected(frameFaces);
    }
    
    // Update face bounding box for overlay
    if (event.nativeEvent.faceBox) {
      setFaceBox(event.nativeEvent.faceBox);
    } else {
      setFaceBox(null);
    }
    
    recordingDataRef.current.frames.push({
      timestamp: timestamp || Date.now(),
      bpm: frameBpm,
      confidence: frameConfidence,
    });
  };
  
  // NOTE: Simulated heart rate fallback was removed for production.
  // All BPM data must come from the real TS-CAN neural rPPG engine
  // to maintain the "Proof of Reality" guarantee.

  return (
    <View style={styles.container}>
      {/* Real Native Camera View */}
      <View style={styles.cameraContainer}>
        {hasPermission ? (
          <BioVaultCameraView
            style={styles.camera}
            active={true}
            onCameraReady={handleCameraReady}
            onCameraError={handleCameraError}
            onFrameAvailable={handleFrameAvailable}
          />
        ) : (
          <View style={styles.mockCamera}>
            <Text style={styles.cameraTitle}>🎥 BioVault Camera</Text>
            <Text style={styles.setupInstructions}>
              Camera permission required{'\n\n'}
              Please grant camera access to continue
            </Text>
          </View>
        )}
      </View>

      {/* Overlay with controls */}
      <View style={styles.overlay}>
        {/* Dynamic Face Rectangle Overlay */}
        {faceBox && (
          <View
            style={[
              styles.faceRectangle,
              {
                left: (faceBox.x / 640) * 100 + '%',
                top: (faceBox.y / 480) * 100 + '%',
                width: (faceBox.width / 640) * 100 + '%',
                height: (faceBox.height / 480) * 100 + '%',
              },
            ]}>
            <View style={styles.faceCornerTL} />
            <View style={styles.faceCornerTR} />
            <View style={styles.faceCornerBL} />
            <View style={styles.faceCornerBR} />
          </View>
        )}
        
        <View style={styles.topBar}>
          <TouchableOpacity
            style={styles.backButton}
            onPress={() => {
              if (isRecording) {
                Alert.alert('Recording in Progress', 'Please stop recording first.');
              } else {
                navigation.goBack();
              }
            }}>
            <Text style={styles.backButtonText}>← Back</Text>
          </TouchableOpacity>
          
          <View style={styles.statusIndicator}>
            <View style={[styles.statusDot, isRecording && styles.statusDotActive]} />
            <Text style={styles.statusText}>
              {isRecording ? 'RECORDING' : 'READY'}
            </Text>
          </View>
          
          {/* Compact BPM Indicator - Top Right */}
          {isRecording && bpm > 0 && (
            <View style={styles.compactBpmContainer}>
              <Text style={styles.compactBpmIcon}>💚</Text>
              <View style={styles.compactBpmInfo}>
                <Text style={styles.compactBpmValue}>{bpm}</Text>
                <Text style={styles.compactBpmUnit}>BPM</Text>
              </View>
              <View style={[
                styles.compactConfidenceIndicator,
                confidence >= 80 ? styles.confidenceHigh : 
                confidence >= 60 ? styles.confidenceMedium : 
                styles.confidenceLow
              ]}>
                <Text style={styles.compactConfidenceText}>{confidence}%</Text>
              </View>
            </View>
          )}
        </View>

        <View style={styles.centerArea}>
          {/* Minimal center — only initial guidance when no face and not recording */}
          {!isRecording && facesDetected === 0 && (
            <View style={styles.guidanceContainer}>
              <Text style={styles.guidanceIcon}>📸</Text>
              <Text style={styles.guidanceText}>Position your face in frame</Text>
              <Text style={styles.guidanceSubtext}>A tracking rectangle will appear</Text>
            </View>
          )}
        </View>

        <View style={styles.bottomBar}>
          {/* ── Status Strip: compact contextual info ── */}
          {isRecording && (
            <View style={styles.statusStrip}>
              <View style={styles.statusChip}>
                <Text style={styles.statusChipText}>⏱ {duration}s / 30s</Text>
              </View>

              {facesDetected === 0 && (
                <View style={[styles.statusChip, styles.statusChipWarn]}>
                  <Text style={styles.statusChipText}>⚠ No face</Text>
                </View>
              )}

              {confidence < 50 && facesDetected > 0 && (
                <View style={[styles.statusChip, styles.statusChipCaution]}>
                  <Text style={styles.statusChipText}>💡 Stay still</Text>
                </View>
              )}

              {confidence >= 50 && facesDetected > 0 && (
                <View style={[styles.statusChip, styles.statusChipGood]}>
                  <Text style={styles.statusChipText}>✓ Good signal</Text>
                </View>
              )}

              {/* Live content classification badge */}
              {liveCategory && liveCategory !== 'SAFE' && (
                <View style={[styles.statusChip,
                  liveCategory === 'EXPLICIT' ? styles.statusChipExplicit : styles.statusChipSensitive]}>
                  <Text style={styles.statusChipText}>
                    {liveCategory === 'EXPLICIT' ? '🔴' : '🟡'} {liveCategory}
                  </Text>
                </View>
              )}

              {consentApprovedRef.current && (
                <View style={[styles.statusChip, styles.statusChipGood]}>
                  <Text style={styles.statusChipText}>✓ Consent</Text>
                </View>
              )}
            </View>
          )}

          {/* Ready pill when face detected + not recording */}
          {!isRecording && facesDetected > 0 && (
            <View style={styles.readyPill}>
              <Text style={styles.readyPillText}>✓ Face detected — ready to record</Text>
            </View>
          )}
          {/* BLE Consent is always active — no toggle needed */}
          {!isRecording && (
            <View style={[styles.consentToggle, styles.consentToggleActive]}>
              <Text style={styles.consentToggleText}>
                🛡️ BLE Consent Active
              </Text>
            </View>
          )}

          <View style={styles.controlsContainer}>
            {!isRecording ? (
              <TouchableOpacity
                style={styles.recordButton}
                onPress={startRecording}>
                <View style={styles.recordButtonInner} />
              </TouchableOpacity>
            ) : (
              <TouchableOpacity
                style={styles.stopButton}
                onPress={stopRecording}>
                <View style={styles.stopButtonInner} />
              </TouchableOpacity>
            )}
          </View>

          <Text style={styles.hint}>
            {isRecording
              ? `⚡ ${duration}/30s • OpenCV + rPPG Active`
              : '👆 Tap to start 30-second recording'}
          </Text>
          
          {isRecording && (
            <View style={styles.progressBarContainer}>
              <View style={[styles.progressBar, {width: `${(duration / 30) * 100}%`}]} />
            </View>
          )}
        </View>
      </View>

      {/* ═══ Consent Overlay Modal (RECORDER side — waiting for nearby approval) ═══ */}
      <Modal
        visible={showConsentOverlay}
        transparent={true}
        animationType="fade"
        onRequestClose={() => {}}>
        <View style={styles.consentOverlay}>
          <View style={styles.consentCard}>
            {consentStatus === 'waiting' && (
              <>
                {/* Category badge */}
                <View style={[
                  styles.consentBadge,
                  (pendingNavigationRef.current?.contentCategory || liveCategory) === 'EXPLICIT'
                    ? styles.consentBadgeExplicit
                    : styles.consentBadgeSensitive
                ]}>
                  <Text style={styles.consentBadgeText}>
                    {(pendingNavigationRef.current?.contentCategory || liveCategory) === 'EXPLICIT'
                      ? '🔴 EXPLICIT'
                      : '🟡 SENSITIVE'}
                  </Text>
                </View>

                <Text style={styles.consentTitle}>⏳ Waiting for Consent</Text>
                <Text style={styles.consentDesc}>
                  {((pendingNavigationRef.current?.contentCategory || liveCategory) === 'EXPLICIT'
                    ? 'Explicit content detected'
                    : 'Sensitive content detected')
                  + ` — ${pendingNavigationRef.current?.contentLabel || liveLabel || 'flagged'}`
                  + ` (${liveConfidence || Math.round((pendingNavigationRef.current?.contentConfidence || 0) * 100)}%)`}
                  {'\n\n'}Broadcasting consent request via BLE.
                  {'\n'}A nearby BioVault device must approve before this capture can be stored.
                  {isRecording ? '\n\n📹 Recording is still active.' : ''}
                </Text>

                {/* Countdown */}
                <View style={styles.consentCountdownRing}>
                  <Text style={styles.consentCountdownValue}>{consentCountdown}</Text>
                  <Text style={styles.consentCountdownUnit}>seconds left</Text>
                </View>

                <View style={styles.consentBleRow}>
                  <ActivityIndicator size="small" color="#6366f1" />
                  <Text style={styles.consentBleText}>
                    {bleSessionActive
                      ? 'Broadcasting consent request to nearby devices…'
                      : 'Initializing BLE broadcast…'}
                  </Text>
                </View>

                <View style={styles.consentButtons}>
                  <TouchableOpacity
                    style={styles.consentDenyBtn}
                    activeOpacity={0.7}
                    onPress={handleConsentDeny}>
                    <Text style={styles.consentDenyBtnText}>✕  Cancel & Delete Recording</Text>
                  </TouchableOpacity>
                </View>
              </>
            )}

            {consentStatus === 'approved' && (
              <View style={styles.consentResult}>
                <Text style={styles.consentResultIcon}>✅</Text>
                <Text style={styles.consentResultText}>Consent Granted via BLE</Text>
                <Text style={styles.consentResultSub}>
                  Approved by nearby device{consentResultRef.current?.deviceAddress
                    ? ` (${consentResultRef.current.deviceAddress.slice(-5)})`
                    : ''}
                </Text>
              </View>
            )}

            {consentStatus === 'denied' && (
              <View style={styles.consentResult}>
                <Text style={styles.consentResultIcon}>❌</Text>
                <Text style={styles.consentResultText}>Consent Denied</Text>
                <Text style={styles.consentResultSub}>Capture has been deleted</Text>
              </View>
            )}

            {consentStatus === 'timeout' && (
              <View style={styles.consentResult}>
                <Text style={styles.consentResultIcon}>⏰</Text>
                <Text style={styles.consentResultText}>No Consent Received</Text>
                <Text style={styles.consentResultSub}>
                  No nearby device approved — capture discarded.
                </Text>
              </View>
            )}
          </View>
        </View>
      </Modal>

      {/* ═══ Incoming Consent Request Modal (BYSTANDER side — another device is asking us) ═══ */}
      <Modal
        visible={!!incomingConsentRequest}
        transparent={true}
        animationType="slide"
        onRequestClose={() => setIncomingConsentRequest(null)}>
        <View style={styles.consentOverlay}>
          <View style={styles.consentCard}>
            <View style={[
              styles.consentBadge,
              incomingConsentRequest?.category === 'EXPLICIT'
                ? styles.consentBadgeExplicit
                : styles.consentBadgeSensitive
            ]}>
              <Text style={styles.consentBadgeText}>
                {incomingConsentRequest?.category === 'EXPLICIT'
                  ? '🔴 EXPLICIT'
                  : '🟡 SENSITIVE'}
              </Text>
            </View>

            <Text style={styles.consentTitle}>📱 Consent Requested</Text>
            <Text style={styles.consentDesc}>
              A nearby device is recording {incomingConsentRequest?.category === 'EXPLICIT'
                ? 'explicit' : 'sensitive'} content.
              {'\n\n'}They need your approval to save this recording.
              You may be in the frame.
              {'\n\n'}Do you consent to this recording?
            </Text>

            <View style={styles.consentButtons}>
              <TouchableOpacity
                style={styles.consentApproveBtn}
                activeOpacity={0.7}
                onPress={async () => {
                  const addr = incomingConsentRequest?.deviceAddress;
                  const sid = incomingConsentRequest?.sessionId;
                  setIncomingConsentRequest(null);
                  if (addr) respondedAddressesRef.current.add(addr);
                  if (sid && BioVaultModule?.respondToConsentRequest) {
                    try {
                      await BioVaultModule.respondToConsentRequest(sid, true);
                      Alert.alert('Consent Sent', 'You approved the recording.');
                    } catch (e) {
                      Alert.alert('Error', 'Failed to send approval: ' + e.message);
                    }
                  }
                }}>
                <Text style={styles.consentApproveBtnText}>✓  I Consent</Text>
              </TouchableOpacity>

              <TouchableOpacity
                style={styles.consentDenyBtn}
                activeOpacity={0.7}
                onPress={async () => {
                  const addr = incomingConsentRequest?.deviceAddress;
                  const sid = incomingConsentRequest?.sessionId;
                  setIncomingConsentRequest(null);
                  if (addr) respondedAddressesRef.current.add(addr);
                  if (sid && BioVaultModule?.respondToConsentRequest) {
                    try {
                      await BioVaultModule.respondToConsentRequest(sid, false);
                      Alert.alert('Denied', 'You denied the recording.');
                    } catch (_) {}
                  }
                }}>
                <Text style={styles.consentDenyBtnText}>✕  I Do Not Consent</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  cameraContainer: {
    flex: 1,
  },
  camera: {
    flex: 1,
  },
  mockCamera: {
    flex: 1,
    backgroundColor: '#1a1a2e',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 20,
  },
  cameraTitle: {
    fontSize: 22,
    color: '#00ff88',
    fontWeight: 'bold',
    marginBottom: 20,
  },
  setupInstructions: {
    fontSize: 14,
    color: '#fff',
    textAlign: 'center',
    lineHeight: 28,
    backgroundColor: 'rgba(0,255,136,0.1)',
    padding: 20,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: 'rgba(0,255,136,0.3)',
  },
  recordingIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 30,
    backgroundColor: 'rgba(255,0,0,0.2)',
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#ff4444',
  },
  recordingDot: {
    width: 12,
    height: 12,
    borderRadius: 6,
    backgroundColor: '#ff4444',
    marginRight: 8,
  },
  recordingText: {
    fontSize: 14,
    color: '#fff',
    fontWeight: 'bold',
  },
  cameraText: {
    color: '#00ff88',
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 20,
  },
  cameraHint: {
    color: '#888',
    fontSize: 14,
    textAlign: 'center',
    lineHeight: 22,
  },
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'transparent',
    zIndex: 10,
  },
  topBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    padding: 16,
    paddingTop: 40,
  },
  faceRectangle: {
    position: 'absolute',
    borderWidth: 0,
    borderColor: '#00ff88',
  },
  faceCornerTL: {
    position: 'absolute',
    top: -2,
    left: -2,
    width: 30,
    height: 30,
    borderTopWidth: 4,
    borderLeftWidth: 4,
    borderColor: '#00ff88',
    borderTopLeftRadius: 8,
  },
  faceCornerTR: {
    position: 'absolute',
    top: -2,
    right: -2,
    width: 30,
    height: 30,
    borderTopWidth: 4,
    borderRightWidth: 4,
    borderColor: '#00ff88',
    borderTopRightRadius: 8,
  },
  faceCornerBL: {
    position: 'absolute',
    bottom: -2,
    left: -2,
    width: 30,
    height: 30,
    borderBottomWidth: 4,
    borderLeftWidth: 4,
    borderColor: '#00ff88',
    borderBottomLeftRadius: 8,
  },
  faceCornerBR: {
    position: 'absolute',
    bottom: -2,
    right: -2,
    width: 30,
    height: 30,
    borderBottomWidth: 4,
    borderRightWidth: 4,
    borderColor: '#00ff88',
    borderBottomRightRadius: 8,
  },
  compactBpmContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.85)',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: 'rgba(0,255,136,0.3)',
    marginLeft: 12,
  },
  compactBpmIcon: {
    fontSize: 18,
    marginRight: 8,
  },
  compactBpmInfo: {
    alignItems: 'center',
    marginRight: 8,
  },
  compactBpmValue: {
    color: '#00ff88',
    fontSize: 20,
    fontWeight: 'bold',
    lineHeight: 22,
  },
  compactBpmUnit: {
    color: '#888',
    fontSize: 10,
  },
  compactConfidenceIndicator: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 10,
  },
  compactConfidenceText: {
    color: '#fff',
    fontSize: 11,
    fontWeight: 'bold',
  },
  backButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: 'rgba(0,0,0,0.5)',
    borderRadius: 8,
  },
  backButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  statusIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.8)',
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.1)',
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#4CAF50',
    marginRight: 8,
  },
  statusDotActive: {
    backgroundColor: '#ff4444',
  },
  statusText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: 'bold',
  },
  centerArea: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 20,
  },
  guidanceContainer: {
    alignItems: 'center',
    backgroundColor: 'rgba(0,0,0,0.55)',
    padding: 20,
    borderRadius: 14,
  },
  guidanceIcon: {
    fontSize: 36,
    marginBottom: 8,
  },
  guidanceText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  guidanceSubtext: {
    color: '#999',
    fontSize: 13,
  },
  // ── Status strip (bottom, compact pills) ──
  statusStrip: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    gap: 8,
    marginBottom: 12,
    flexWrap: 'wrap',
  },
  statusChip: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(255,255,255,0.12)',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
  },
  statusChipWarn: {
    backgroundColor: 'rgba(255,152,0,0.3)',
  },
  statusChipCaution: {
    backgroundColor: 'rgba(255,235,59,0.2)',
  },
  statusChipGood: {
    backgroundColor: 'rgba(0,255,136,0.2)',
  },
  statusChipExplicit: {
    backgroundColor: 'rgba(255,68,68,0.35)',
  },
  statusChipSensitive: {
    backgroundColor: 'rgba(255,193,7,0.35)',
  },
  statusChipText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '600',
  },
  readyPill: {
    backgroundColor: 'rgba(0,255,136,0.15)',
    paddingHorizontal: 20,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: 'rgba(0,255,136,0.4)',
    marginBottom: 12,
  },
  readyPillText: {
    color: '#00ff88',
    fontSize: 14,
    fontWeight: '600',
  },
  confidenceBar: {
    width: '100%',
    height: 6,
    backgroundColor: 'rgba(255,255,255,0.15)',
    borderRadius: 3,
    overflow: 'hidden',
    marginBottom: 6,
  },
  confidenceFill: {
    height: '100%',
    borderRadius: 3,
  },
  confidenceHigh: {
    backgroundColor: '#00ff88',
  },
  confidenceMedium: {
    backgroundColor: '#ffc107',
  },
  confidenceLow: {
    backgroundColor: '#ff9800',
  },

  bottomBar: {
    alignItems: 'center',
    paddingBottom: 40,
  },
  consentToggle: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: 'rgba(255,255,255,0.1)',
    borderRadius: 20,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.2)',
  },
  consentToggleActive: {
    backgroundColor: 'rgba(99,102,241,0.25)',
    borderColor: '#6366f1',
  },
  consentToggleText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: '600',
  },
  controlsContainer: {
    marginBottom: 16,
  },
  recordButton: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: 'rgba(255,68,68,0.2)',
    justifyContent: 'center',
    alignItems: 'center',
    borderWidth: 3,
    borderColor: '#ff4444',
  },
  recordButtonInner: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: '#ff4444',
  },
  stopButton: {
    width: 80,
    height: 80,
    borderRadius: 40,
    backgroundColor: 'rgba(255,255,255,0.3)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  stopButtonInner: {
    width: 32,
    height: 32,
    backgroundColor: '#ff4444',
    borderRadius: 4,
  },
  hint: {
    color: '#aaa',
    fontSize: 13,
    textAlign: 'center',
    fontWeight: '500',
  },
  progressBarContainer: {
    width: '80%',
    height: 3,
    backgroundColor: 'rgba(255,255,255,0.2)',
    borderRadius: 2,
    overflow: 'hidden',
    marginTop: 12,
  },
  progressBar: {
    height: '100%',
    backgroundColor: '#00ff88',
    borderRadius: 2,
  },
  errorContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    backgroundColor: '#0f0f23',
  },
  errorIcon: {
    fontSize: 64,
    marginBottom: 20,
  },
  errorTitle: {
    color: '#fff',
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 12,
  },
  errorText: {
    color: '#888',
    fontSize: 16,
    textAlign: 'center',
    marginBottom: 30,
    lineHeight: 24,
  },
  permissionButton: {
    backgroundColor: '#667eea',
    paddingHorizontal: 32,
    paddingVertical: 16,
    borderRadius: 12,
  },
  permissionButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: 'bold',
  },
  // ═══ Consent Overlay Styles ═══
  consentOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.88)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 24,
  },
  consentCard: {
    backgroundColor: '#16162a',
    borderRadius: 24,
    padding: 28,
    width: '100%',
    maxWidth: 380,
    borderWidth: 1.5,
    borderColor: 'rgba(255,68,68,0.4)',
    alignItems: 'center',
  },
  consentBadge: {
    paddingHorizontal: 16,
    paddingVertical: 6,
    borderRadius: 20,
    marginBottom: 16,
  },
  consentBadgeExplicit: {
    backgroundColor: 'rgba(255,68,68,0.2)',
  },
  consentBadgeSensitive: {
    backgroundColor: 'rgba(255,193,7,0.2)',
  },
  consentBadgeText: {
    color: '#fff',
    fontSize: 13,
    fontWeight: 'bold',
    letterSpacing: 1,
  },
  consentTitle: {
    color: '#fff',
    fontSize: 22,
    fontWeight: 'bold',
    textAlign: 'center',
    marginBottom: 10,
  },
  consentDesc: {
    color: '#aaa',
    fontSize: 14,
    textAlign: 'center',
    lineHeight: 20,
    marginBottom: 20,
  },
  consentCountdownRing: {
    width: 80,
    height: 80,
    borderRadius: 40,
    borderWidth: 3,
    borderColor: '#ff4444',
    justifyContent: 'center',
    alignItems: 'center',
    marginBottom: 20,
  },
  consentCountdownValue: {
    color: '#ff4444',
    fontSize: 28,
    fontWeight: 'bold',
    lineHeight: 30,
  },
  consentCountdownUnit: {
    color: '#ff8888',
    fontSize: 10,
  },
  consentBleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(99,102,241,0.12)',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 12,
    marginBottom: 16,
    width: '100%',
  },
  consentBleText: {
    color: '#a5b4fc',
    fontSize: 13,
    marginLeft: 10,
    flex: 1,
  },
  consentDivider: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    marginBottom: 16,
  },
  consentDividerLine: {
    flex: 1,
    height: 1,
    backgroundColor: 'rgba(255,255,255,0.1)',
  },
  consentDividerText: {
    color: '#666',
    fontSize: 12,
    marginHorizontal: 12,
  },
  consentButtons: {
    width: '100%',
  },
  consentApproveBtn: {
    backgroundColor: '#00ff88',
    paddingVertical: 16,
    borderRadius: 14,
    alignItems: 'center',
    marginBottom: 10,
  },
  consentApproveBtnText: {
    color: '#000',
    fontSize: 16,
    fontWeight: 'bold',
  },
  consentDenyBtn: {
    backgroundColor: 'rgba(255,68,68,0.12)',
    paddingVertical: 16,
    borderRadius: 14,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,68,68,0.4)',
  },
  consentDenyBtnText: {
    color: '#ff4444',
    fontSize: 16,
    fontWeight: '600',
  },
  consentResult: {
    alignItems: 'center',
    paddingVertical: 24,
  },
  consentResultIcon: {
    fontSize: 56,
    marginBottom: 12,
  },
  consentResultText: {
    color: '#fff',
    fontSize: 20,
    fontWeight: 'bold',
    marginBottom: 8,
  },
  consentResultSub: {
    color: '#aaa',
    fontSize: 14,
    textAlign: 'center',
  },
});
