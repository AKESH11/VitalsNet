import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Share,
  Alert,
  NativeModules,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import CryptoJS from 'crypto-js';
import {uploadCapture, isConfigured as isFirebaseConfigured} from '../services/FirebaseService';

const {BioVaultModule} = NativeModules;
const CAPTURES_KEY = 'biovault_captures';

export default function ResultsScreen({route, navigation}) {
  const {
    bpm = 0,
    confidence = 0,
    duration = 0,
    facesDetected = 0,
    framesProcessed = 0,
    statistics = {},
    videoHash = '',
    bioSignature = '',
    hardwareDNA = '',
    hardwareFingerprint = '',
    proofOfRealityHash = '',
    mediaFilePath = '',
    watermarkedImageBase64 = '',
    contentCategory = 'SAFE',
    requiresConsent = false,
    contentLabel = '',
    contentConfidence = 0,
    consentParties = null,
    livenessDetected = false,
    bpmVariability = 0,
  } = route.params || {};

  const [captureId, setCaptureId] = useState('');
  const [contentHash, setContentHash] = useState('');
  const [saved, setSaved] = useState(false);
  const [riskScore, setRiskScore] = useState(null);   // 0–100
  const [riskLabel, setRiskLabel] = useState('');      // VERIFIED / MEDIUM / LOW / UNVERIFIED
  const [proofBreakdown, setProofBreakdown] = useState(null);

  // Determine the device fingerprint — use PRNU if available, else hardwareDNA
  const deviceFingerprint = hardwareFingerprint || hardwareDNA || '';
  const hasDeviceBinding = deviceFingerprint.length > 8;
  const hasBioSignal = bpm > 40 && bpm < 180 && confidence > 20;

  // Origin status
  const originVerified = hasDeviceBinding && hasBioSignal;

  useEffect(() => {
    // Generate a deterministic capture ID from content
    const timestamp = Date.now();
    const raw = `${bpm}:${confidence}:${deviceFingerprint}:${timestamp}`;
    const hash = CryptoJS.SHA256(raw).toString();
    const id = 'BV-' + hash.substring(0, 12).toUpperCase();
    setCaptureId(id);

    // Generate content hash (replaces broken IPFS)
    const contentData = `${videoHash}:${bioSignature}:${deviceFingerprint}:${bpm}:${framesProcessed}`;
    const cHash = CryptoJS.SHA256(contentData).toString();
    setContentHash(cHash);

    // Compute risk score via native SDK
    computeRisk(id, cHash, timestamp);
  }, []);

  const computeRisk = async (id, cHash, timestamp) => {
    try {
      if (BioVaultModule?.computeRiskScore) {
        const result = await BioVaultModule.computeRiskScore(
          bpm,
          confidence,
          deviceFingerprint,
          !!watermarkedImageBase64,
          !!(consentParties?.consensusHash),
          consentParties?.signaturesReceived || 0,
          contentCategory,
          videoHash,
          bioSignature,
          proofOfRealityHash,
          livenessDetected,
          bpmVariability,
        );
        const parsed = JSON.parse(result);
        setRiskScore(parsed.riskScore);
        setRiskLabel(parsed.riskLabel);
        setProofBreakdown(parsed);
        // Save with the score
        saveCaptureRecord(id, cHash, timestamp, parsed.riskScore, parsed.riskLabel);
        return;
      }
    } catch (e) {
      console.warn('[VitalsNet] Risk score computation failed:', e.message);
    }
    // Fallback: compute a simple score in JS
    const prnuNorm = deviceFingerprint.length >= 32 ? 1 : deviceFingerprint.length >= 8 ? 0.5 : 0;
    const bpmNorm = (bpm > 40 && bpm < 180) ? Math.min(1, confidence / 100) : 0;
    const consentNorm = consentParties?.consensusHash ? 1 : 0;
    const wmNorm = watermarkedImageBase64 ? 1 : 0;
    const sbNorm = bioSignature ? 1 : 0;
    const score = Math.round((prnuNorm * 0.3 + bpmNorm * 0.3 + consentNorm * 0.2 + wmNorm * 0.1 + sbNorm * 0.1) * 100);
    const label = score >= 75 ? 'VERIFIED' : score >= 50 ? 'MEDIUM' : score >= 25 ? 'LOW' : 'UNVERIFIED';
    setRiskScore(score);
    setRiskLabel(label);
    saveCaptureRecord(id, cHash, timestamp, score, label);
  };

  const saveCaptureRecord = async (id, cHash, timestamp, score, label) => {
    try {
      const record = {
        captureId: id,
        contentHash: cHash,
        timestamp,
        bpm,
        confidence,
        duration,
        facesDetected,
        framesProcessed,
        deviceFingerprint: deviceFingerprint.substring(0, 32) + '...',
        bioSignature: bioSignature.substring(0, 32) + '...',
        originVerified,
        riskScore: score ?? null,
        riskLabel: label ?? '',
        statistics,
      };

      const existing = await AsyncStorage.getItem(CAPTURES_KEY);
      const captures = existing ? JSON.parse(existing) : [];
      captures.unshift(record);
      // Keep last 50 captures
      if (captures.length > 50) captures.length = 50;
      await AsyncStorage.setItem(CAPTURES_KEY, JSON.stringify(captures));
      setSaved(true);

      // Upload to Firebase for cross-device verification
      if (isFirebaseConfigured()) {
        try {
          await uploadCapture({
            captureId: id,
            bpm,
            confidence,
            riskScore: score,
            videoHash,
            hardwareDNA: deviceFingerprint,
            watermarkPresent: !!watermarkedImageBase64,
            consentHash: consentParties?.consensusHash || '',
            contentCategory,
            deviceModel: require('react-native').Platform.constants?.Model || 'unknown',
            timestamp,
          });
          console.log('[VitalsNet] Capture synced to Firebase');
        } catch (fbErr) {
          console.warn('[VitalsNet] Firebase upload failed:', fbErr.message);
        }
      }
    } catch (e) {
      console.warn('Failed to save capture:', e);
    }
  };

  const handleShare = async () => {
    try {
      await Share.share({
        message:
          `VitalsNet Proof of Origin\n\n` +
          `Capture ID: ${captureId}\n` +
          `Reality Score: ${riskScore ?? '...'}/100 (${riskLabel || 'Computing'})\n` +
          `Status: ${originVerified ? 'ORIGIN VERIFIED' : 'UNVERIFIED'}\n` +
          `Heart Rate: ${bpm} BPM @ ${confidence}%\n` +
          `Device-Bound: ${hasDeviceBinding ? 'Yes' : 'No'}\n` +
          `Content Hash: ${contentHash.substring(0, 16)}...\n` +
          `Timestamp: ${new Date().toISOString()}\n\n` +
          `Verify at: vitalsnet://verify/${captureId}`,
      });
    } catch (e) {
      console.warn('Share failed:', e);
    }
  };

  // Render a score breakdown bar
  const renderBar = (label, pct, weight) => {
    const barColor = pct >= 75 ? '#22c55e' : pct >= 50 ? '#eab308' : pct > 0 ? '#f97316' : '#ef4444';
    return (
      <View style={styles.barRow} key={label}>
        <View style={styles.barLabelBox}>
          <Text style={styles.barLabel}>{label}</Text>
          <Text style={styles.barWeight}>{weight}</Text>
        </View>
        <View style={styles.barTrack}>
          <View style={[styles.barFill, {width: `${pct}%`, backgroundColor: barColor}]} />
        </View>
        <Text style={[styles.barPct, {color: barColor}]}>{Math.round(pct)}</Text>
      </View>
    );
  };

  // Score color helper
  const scoreColor = (s) => {
    if (s === null) return '#52525b';
    if (s >= 75) return '#22c55e';
    if (s >= 50) return '#eab308';
    if (s >= 25) return '#f97316';
    return '#ef4444';
  };

  const scoreGradient = scoreColor(riskScore);

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* ═══ Reality Score Gauge ═══ */}
        <View style={[styles.scoreCard, {borderColor: scoreGradient}]}>
          <Text style={styles.scoreLabel}>REALITY SCORE</Text>
          <View style={styles.scoreRing}>
            <View style={[styles.scoreCircle, {borderColor: scoreGradient}]}>
              <Text style={[styles.scoreNumber, {color: scoreGradient}]}>
                {riskScore !== null ? riskScore : '...'}
              </Text>
              <Text style={styles.scoreMax}>/100</Text>
            </View>
          </View>
          <View style={[styles.scoreLabelBadge, {backgroundColor: scoreGradient + '22', borderColor: scoreGradient}]}>
            <Text style={[styles.scoreLabelText, {color: scoreGradient}]}>
              {riskLabel || 'COMPUTING...'}
            </Text>
          </View>
          <Text style={styles.scoreDesc}>
            {riskScore >= 75
              ? 'High confidence: real human, real device, cryptographic proof'
              : riskScore >= 50
              ? 'Moderate confidence: some proof signals present'
              : riskScore >= 25
              ? 'Low confidence: limited proof of reality'
              : riskScore !== null
              ? 'Insufficient proof of human origin'
              : 'Analyzing capture signals...'}
          </Text>
        </View>

        {/* ═══ Score Breakdown ═══ */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Score Breakdown</Text>
          {renderBar('Device DNA (PRNU)', hasDeviceBinding ? (deviceFingerprint.length >= 32 ? 100 : 50) : 0, '30%')}
          {renderBar('Heartbeat (rPPG)', hasBioSignal ? Math.min(100, confidence) : 0, '30%')}
          {renderBar('Consent (BLE)', contentCategory === 'SAFE' ? 100 : (consentParties?.consensusHash ? 100 : 0), '20%')}
          {renderBar('Watermark', watermarkedImageBase64 ? 100 : 0, '10%')}
          {renderBar('StrongBox Sig', bioSignature ? 100 : 0, '10%')}
        </View>

        {/* Capture ID */}
        <View style={styles.captureIdBox}>
          <Text style={styles.captureIdLabel}>Capture ID</Text>
          <Text style={styles.captureIdValue}>{captureId}</Text>
          {saved && <Text style={styles.savedTag}>Saved</Text>}
        </View>

        {/* Device DNA Section */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Device DNA</Text>
          <Text style={styles.sectionDesc}>
            Hardware fingerprint derived from camera sensor imperfections (PRNU)
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Status</Text>
            <View style={[styles.tag, hasDeviceBinding ? styles.tagGreen : styles.tagRed]}>
              <Text style={styles.tagText}>{hasDeviceBinding ? 'BOUND' : 'NOT DETECTED'}</Text>
            </View>
          </View>
          {hasDeviceBinding && (
            <View style={styles.row}>
              <Text style={styles.label}>Fingerprint</Text>
              <Text style={styles.valueSmall} numberOfLines={1}>
                {deviceFingerprint.substring(0, 24)}...
              </Text>
            </View>
          )}
        </View>

        {/* Bio-Signal Section */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Biological Signal</Text>
          <Text style={styles.sectionDesc}>
            Physiological signal extracted from facial video via rPPG
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Heart Rate</Text>
            <Text style={styles.value}>{bpm} BPM</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Signal Confidence</Text>
            <Text style={[styles.value, confidence >= 60 ? styles.green : confidence >= 40 ? styles.yellow : styles.red]}>
              {confidence}%
            </Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Duration</Text>
            <Text style={styles.value}>{duration}s</Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Frames Analyzed</Text>
            <Text style={styles.value}>{framesProcessed}</Text>
          </View>
          {statistics.min !== undefined && (
            <View style={styles.row}>
              <Text style={styles.label}>BPM Range</Text>
              <Text style={styles.value}>{statistics.min} — {statistics.max}</Text>
            </View>
          )}
        </View>

        {/* Anti-Spoofing Section */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Anti-Spoofing</Text>
          <Text style={styles.sectionDesc}>
            Detects AI-generated faces and screen replays
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Liveness</Text>
            <View style={[styles.tag, proofBreakdown?.livenessDetected ? styles.tagGreen : styles.tagRed]}>
              <Text style={styles.tagText}>{proofBreakdown?.livenessDetected ? 'LIVE' : 'NOT DETECTED'}</Text>
            </View>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>BPM Variability (HRV)</Text>
            <Text style={[styles.value, (proofBreakdown?.bpmVariability || 0) > 1.5 ? styles.green : styles.red]}>
              {(proofBreakdown?.bpmVariability || bpmVariability || 0).toFixed(1)}
            </Text>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Screen Spoof Check</Text>
            <View style={[styles.tag, proofBreakdown?.antiSpoofPassed ? styles.tagGreen : styles.tagRed]}>
              <Text style={styles.tagText}>{proofBreakdown?.antiSpoofPassed ? 'PASSED' : 'FAILED'}</Text>
            </View>
          </View>
        </View>

        {/* Content Hash */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Content Hash</Text>
          <Text style={styles.sectionDesc}>
            SHA-256 hash of capture data — tamper-proof content fingerprint
          </Text>
          <View style={styles.hashBox}>
            <Text style={styles.hashText} selectable>
              {contentHash || 'Computing...'}
            </Text>
          </View>
        </View>

        {/* Watermark Status */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Invisible Watermark</Text>
          <Text style={styles.sectionDesc}>
            DWT+DCT+SVD blind watermark embedded in captured image
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Status</Text>
            <View style={[styles.tag, watermarkedImageBase64 ? styles.tagGreen : styles.tagRed]}>
              <Text style={styles.tagText}>{watermarkedImageBase64 ? 'EMBEDDED' : 'NOT AVAILABLE'}</Text>
            </View>
          </View>
          {watermarkedImageBase64 ? (
            <View style={styles.row}>
              <Text style={styles.label}>Payload</Text>
              <Text style={styles.valueSmall}>BPM + Device DNA + Timestamp</Text>
            </View>
          ) : null}
        </View>

        {/* Content Classification (ML Kit) */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Content Classification</Text>
          <Text style={styles.sectionDesc}>
            ML Kit Image Labeling analysis of captured frame
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Category</Text>
            <View style={[styles.tag,
              contentCategory === 'SAFE' ? styles.tagGreen :
              contentCategory === 'SENSITIVE' ? styles.tagOrange :
              styles.tagRed]}>
              <Text style={styles.tagText}>{contentCategory}</Text>
            </View>
          </View>
          {contentLabel ? (
            <View style={styles.row}>
              <Text style={styles.label}>Top Label</Text>
              <Text style={styles.value}>{contentLabel} ({Math.round(contentConfidence * 100)}%)</Text>
            </View>
          ) : null}
          <View style={styles.row}>
            <Text style={styles.label}>Consent Required</Text>
            <View style={[styles.tag, requiresConsent ? styles.tagOrange : styles.tagGreen]}>
              <Text style={styles.tagText}>{requiresConsent ? 'YES' : 'NO'}</Text>
            </View>
          </View>
          {requiresConsent ? (
            <Text style={[styles.sectionDesc, {color: '#f97316', marginTop: 4}]}>
              Inappropriate content detected — this media may violate content policies
            </Text>
          ) : null}
        </View>

        {/* BLE Consent Status */}
        {consentParties ? (
          <View style={styles.section}>
              <Text style={styles.sectionTitle}>Consent Verification</Text>
            <View style={styles.row}>
              <Text style={styles.label}>Method</Text>
              <View style={[styles.tag, consentParties.consentMethod === 'ble' ? styles.tagGreen : styles.tagOrange]}>
                <Text style={styles.tagText}>
                  {consentParties.consentMethod === 'ble' ? 'BLE P2P' : 'SELF-APPROVED'}
                </Text>
              </View>
            </View>
            <View style={styles.row}>
              <Text style={styles.label}>Signatures</Text>
              <Text style={styles.value}>{consentParties.signaturesReceived || 0}</Text>
            </View>
            {consentParties.consensusHash ? (
              <View style={styles.row}>
                <Text style={styles.label}>Consent Hash</Text>
                <Text style={[styles.value, styles.mono]} numberOfLines={1}>
                  {consentParties.consensusHash.substring(0, 16)}...
                </Text>
              </View>
            ) : null}
            <View style={styles.row}>
              <Text style={styles.label}>Timestamp</Text>
              <Text style={styles.value}>
                {consentParties.consentTimestamp
                  ? new Date(consentParties.consentTimestamp).toLocaleTimeString()
                  : 'N/A'}
              </Text>
            </View>
          </View>
        ) : null}

        {/* Actions */}
        <View style={styles.actions}>
          <TouchableOpacity style={styles.shareBtn} onPress={handleShare}>
            <Text style={styles.shareBtnText}>Share Proof</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.verifyBtn}
            onPress={() => navigation.navigate('Verify', {captureId, watermarkedImageBase64})}>
            <Text style={styles.verifyBtnText}>Verify This Capture</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.homeBtn}
            onPress={() => navigation.navigate('Home')}>
            <Text style={styles.homeBtnText}>← Back to Dashboard</Text>
          </TouchableOpacity>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#09090b'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  // ── Reality Score Gauge ──
  scoreCard: {
    alignItems: 'center',
    padding: 24,
    borderRadius: 16,
    marginBottom: 20,
    borderWidth: 1,
    backgroundColor: '#18181b',
  },
  scoreLabel: {color: '#71717a', fontSize: 11, fontWeight: '700', letterSpacing: 2, marginBottom: 12},
  scoreRing: {marginBottom: 12},
  scoreCircle: {
    width: 110,
    height: 110,
    borderRadius: 55,
    borderWidth: 3,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#09090b',
  },
  scoreNumber: {fontSize: 38, fontWeight: '700'},
  scoreMax: {color: '#52525b', fontSize: 13, marginTop: -4},
  scoreLabelBadge: {
    paddingHorizontal: 14,
    paddingVertical: 5,
    borderRadius: 16,
    borderWidth: 1,
    marginBottom: 10,
  },
  scoreLabelText: {fontSize: 12, fontWeight: '700', letterSpacing: 1},
  scoreDesc: {color: '#71717a', fontSize: 12, textAlign: 'center', lineHeight: 17, paddingHorizontal: 10},

  // ── Score Breakdown Bars ──
  barRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 10,
  },
  barLabelBox: {width: 120},
  barLabel: {color: '#a1a1aa', fontSize: 12, fontWeight: '600'},
  barWeight: {color: '#52525b', fontSize: 10},
  barTrack: {
    flex: 1,
    height: 6,
    backgroundColor: '#27272a',
    borderRadius: 3,
    overflow: 'hidden',
    marginHorizontal: 8,
  },
  barFill: {height: 6, borderRadius: 3},
  barPct: {width: 28, fontSize: 12, fontWeight: '700', textAlign: 'right'},

  captureIdBox: {
    backgroundColor: '#18181b',
    borderRadius: 10,
    padding: 16,
    alignItems: 'center',
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  captureIdLabel: {color: '#71717a', fontSize: 12, marginBottom: 4},
  captureIdValue: {color: '#fafafa', fontSize: 18, fontWeight: '700', fontFamily: 'monospace'},
  savedTag: {color: '#22c55e', fontSize: 11, marginTop: 6},

  section: {
    backgroundColor: '#18181b',
    borderRadius: 10,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  sectionTitle: {color: '#fafafa', fontSize: 15, fontWeight: '600', marginBottom: 4},
  sectionDesc: {color: '#52525b', fontSize: 12, marginBottom: 12, lineHeight: 16},

  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#27272a',
  },
  label: {color: '#71717a', fontSize: 14},
  value: {color: '#fafafa', fontSize: 14, fontWeight: '600'},
  valueSmall: {color: '#71717a', fontSize: 11, fontFamily: 'monospace', maxWidth: 180},
  green: {color: '#22c55e'},
  yellow: {color: '#eab308'},
  red: {color: '#ef4444'},
  mono: {fontFamily: 'monospace'},

  tag: {paddingHorizontal: 10, paddingVertical: 4, borderRadius: 8},
  tagGreen: {backgroundColor: 'rgba(34,197,94,0.12)'},
  tagOrange: {backgroundColor: 'rgba(249,115,22,0.12)'},
  tagRed: {backgroundColor: 'rgba(239,68,68,0.12)'},
  tagText: {color: '#fafafa', fontSize: 11, fontWeight: '600'},

  hashBox: {
    backgroundColor: '#09090b',
    borderRadius: 8,
    padding: 12,
  },
  hashText: {color: '#a1a1aa', fontSize: 11, fontFamily: 'monospace', lineHeight: 16},

  actions: {marginTop: 8},
  shareBtn: {
    backgroundColor: '#fafafa',
    padding: 16,
    borderRadius: 10,
    alignItems: 'center',
    marginBottom: 12,
  },
  shareBtnText: {color: '#09090b', fontSize: 16, fontWeight: '600'},
  verifyBtn: {
    backgroundColor: '#18181b',
    padding: 16,
    borderRadius: 10,
    alignItems: 'center',
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  verifyBtnText: {color: '#fafafa', fontSize: 16, fontWeight: '600'},
  homeBtn: {
    padding: 16,
    borderRadius: 10,
    alignItems: 'center',
  },
  homeBtnText: {color: '#71717a', fontSize: 14},
});
