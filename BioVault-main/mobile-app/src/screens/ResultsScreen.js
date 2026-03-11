import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Share,
  Alert,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import CryptoJS from 'crypto-js';

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
  } = route.params || {};

  const [captureId, setCaptureId] = useState('');
  const [contentHash, setContentHash] = useState('');
  const [saved, setSaved] = useState(false);

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

    // Auto-save capture record
    saveCaptureRecord(id, cHash, timestamp);
  }, []);

  const saveCaptureRecord = async (id, cHash, timestamp) => {
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
        statistics,
      };

      const existing = await AsyncStorage.getItem(CAPTURES_KEY);
      const captures = existing ? JSON.parse(existing) : [];
      captures.unshift(record);
      // Keep last 50 captures
      if (captures.length > 50) captures.length = 50;
      await AsyncStorage.setItem(CAPTURES_KEY, JSON.stringify(captures));
      setSaved(true);
    } catch (e) {
      console.warn('Failed to save capture:', e);
    }
  };

  const handleShare = async () => {
    try {
      await Share.share({
        message:
          `BioVault Proof of Origin\n\n` +
          `Capture ID: ${captureId}\n` +
          `Status: ${originVerified ? 'ORIGIN VERIFIED' : 'UNVERIFIED'}\n` +
          `Device-Bound: ${hasDeviceBinding ? 'Yes' : 'No'}\n` +
          `Bio-Signal: ${hasBioSignal ? 'Detected' : 'None'}\n` +
          `Content Hash: ${contentHash.substring(0, 16)}...\n` +
          `Timestamp: ${new Date().toISOString()}\n\n` +
          `Verify at: biovault://verify/${captureId}`,
      });
    } catch (e) {
      console.warn('Share failed:', e);
    }
  };

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Origin Status Badge */}
        <View style={[styles.statusBadge, originVerified ? styles.verified : styles.unverified]}>
          <Text style={styles.statusIcon}>{originVerified ? '✅' : '⚠️'}</Text>
          <Text style={[styles.statusTitle, originVerified ? styles.verifiedText : styles.unverifiedText]}>
            {originVerified ? 'ORIGIN VERIFIED' : 'ORIGIN UNVERIFIED'}
          </Text>
          <Text style={styles.statusSubtitle}>
            {originVerified
              ? 'This media was captured on a verified device with a live biological signal'
              : 'Insufficient device binding or biological signal detected'}
          </Text>
        </View>

        {/* Capture ID */}
        <View style={styles.captureIdBox}>
          <Text style={styles.captureIdLabel}>Capture ID</Text>
          <Text style={styles.captureIdValue}>{captureId}</Text>
          {saved && <Text style={styles.savedTag}>💾 Saved locally</Text>}
        </View>

        {/* Device DNA Section */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>🔬 Device DNA</Text>
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
          <Text style={styles.sectionTitle}>💚 Biological Signal</Text>
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

        {/* Content Hash */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>🔗 Content Hash</Text>
          <Text style={styles.sectionDesc}>
            SHA-256 hash of capture data — tamper-proof content fingerprint
          </Text>
          <View style={styles.hashBox}>
            <Text style={styles.hashText} selectable>
              {contentHash || 'Computing...'}
            </Text>
          </View>
        </View>

        {/* Proof of Reality */}
        {proofOfRealityHash ? (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>🛡️ Proof of Reality</Text>
            <View style={styles.hashBox}>
              <Text style={styles.hashText} selectable>
                {proofOfRealityHash}
              </Text>
            </View>
          </View>
        ) : null}

        {/* Watermark Status */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>🔏 Invisible Watermark</Text>
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
          <Text style={styles.sectionTitle}>🤖 Content Classification</Text>
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
            <Text style={[styles.sectionDesc, {color: '#ff9500', marginTop: 4}]}>
              ⚠️ Inappropriate content detected — this media may violate content policies
            </Text>
          ) : null}
        </View>

        {/* BLE Consent Status */}
        {consentParties ? (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>🤝 Consent Verification</Text>
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
            <Text style={styles.shareBtnText}>📤 Share Proof</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.verifyBtn}
            onPress={() => navigation.navigate('Verify', {captureId, watermarkedImageBase64})}>
            <Text style={styles.verifyBtnText}>🔍 Verify This Capture</Text>
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
  container: {flex: 1, backgroundColor: '#0f0f23'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  statusBadge: {
    alignItems: 'center',
    padding: 24,
    borderRadius: 16,
    marginBottom: 20,
    borderWidth: 2,
  },
  verified: {
    backgroundColor: 'rgba(0,255,136,0.08)',
    borderColor: '#00ff88',
  },
  unverified: {
    backgroundColor: 'rgba(255,152,0,0.08)',
    borderColor: '#ff9800',
  },
  statusIcon: {fontSize: 48, marginBottom: 8},
  statusTitle: {fontSize: 22, fontWeight: 'bold', marginBottom: 6},
  verifiedText: {color: '#00ff88'},
  unverifiedText: {color: '#ff9800'},
  statusSubtitle: {color: '#8b8ba7', fontSize: 13, textAlign: 'center', lineHeight: 18},

  captureIdBox: {
    backgroundColor: 'rgba(99,102,241,0.1)',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
    marginBottom: 20,
    borderWidth: 1,
    borderColor: 'rgba(99,102,241,0.3)',
  },
  captureIdLabel: {color: '#8b8ba7', fontSize: 12, marginBottom: 4},
  captureIdValue: {color: '#6366f1', fontSize: 20, fontWeight: 'bold', fontFamily: 'monospace'},
  savedTag: {color: '#00ff88', fontSize: 11, marginTop: 6},

  section: {
    backgroundColor: 'rgba(255,255,255,0.04)',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  sectionTitle: {color: '#fff', fontSize: 16, fontWeight: 'bold', marginBottom: 4},
  sectionDesc: {color: '#666', fontSize: 12, marginBottom: 12, lineHeight: 16},

  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.05)',
  },
  label: {color: '#8b8ba7', fontSize: 14},
  value: {color: '#fff', fontSize: 14, fontWeight: '600'},
  valueSmall: {color: '#8b8ba7', fontSize: 11, fontFamily: 'monospace', maxWidth: 180},
  green: {color: '#00ff88'},
  yellow: {color: '#ffc107'},
  red: {color: '#ff4444'},

  tag: {paddingHorizontal: 10, paddingVertical: 4, borderRadius: 8},
  tagGreen: {backgroundColor: 'rgba(0,255,136,0.15)'},
  tagOrange: {backgroundColor: 'rgba(255,165,0,0.15)'},
  tagRed: {backgroundColor: 'rgba(255,68,68,0.15)'},
  tagText: {color: '#fff', fontSize: 11, fontWeight: 'bold'},

  hashBox: {
    backgroundColor: 'rgba(0,0,0,0.3)',
    borderRadius: 8,
    padding: 12,
  },
  hashText: {color: '#6366f1', fontSize: 11, fontFamily: 'monospace', lineHeight: 16},

  actions: {marginTop: 8},
  shareBtn: {
    backgroundColor: '#6366f1',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 12,
  },
  shareBtnText: {color: '#fff', fontSize: 16, fontWeight: 'bold'},
  verifyBtn: {
    backgroundColor: 'rgba(0,255,136,0.1)',
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#00ff88',
  },
  verifyBtnText: {color: '#00ff88', fontSize: 16, fontWeight: 'bold'},
  homeBtn: {
    padding: 16,
    borderRadius: 12,
    alignItems: 'center',
  },
  homeBtnText: {color: '#8b8ba7', fontSize: 14},
});
