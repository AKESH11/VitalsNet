import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TextInput,
  TouchableOpacity,
  ScrollView,
  ActivityIndicator,
  NativeModules,
  Alert,
  Clipboard,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import RNFS from 'react-native-fs';
import {lookupCapture, isConfigured as isFirebaseConfigured} from '../services/FirebaseService';

const {BioVaultModule} = NativeModules;
const CAPTURES_KEY = 'biovault_captures';

export default function VerifyScreen({route, navigation}) {
  const [searchId, setSearchId] = useState(route.params?.captureId || '');
  const [result, setResult] = useState(null);
  const [searching, setSearching] = useState(false);
  const [notFound, setNotFound] = useState(false);
  const [watermarkData, setWatermarkData] = useState(null);
  const [wmExtracting, setWmExtracting] = useState(false);

  useEffect(() => {
    if (route.params?.captureId) {
      handleVerify(route.params.captureId);
    }
    // Auto-extract watermark if image was passed
    if (route.params?.watermarkedImageBase64) {
      extractWatermark(route.params.watermarkedImageBase64);
    }
  }, []);

  const handleVerify = async (id) => {
    const lookupId = (id || searchId).trim().toUpperCase();
    if (!lookupId) return;

    setSearching(true);
    setResult(null);
    setNotFound(false);

    try {
      const raw = await AsyncStorage.getItem(CAPTURES_KEY);
      const captures = raw ? JSON.parse(raw) : [];
      const found = captures.find(c => c.captureId === lookupId);

      if (found) {
        setResult({...found, source: 'local'});
      } else if (isFirebaseConfigured()) {
        // Fallback: search Firebase cloud
        try {
          const cloudResult = await lookupCapture(lookupId);
          if (cloudResult) {
            setResult({...cloudResult, source: 'cloud'});
          } else {
            setNotFound(true);
          }
        } catch (fbErr) {
          console.warn('[VitalsNet] Firebase lookup failed:', fbErr.message);
          setNotFound(true);
        }
      } else {
        setNotFound(true);
      }
    } catch (e) {
      console.warn('Verify lookup error:', e);
      setNotFound(true);
    } finally {
      setSearching(false);
    }
  };

  const extractWatermark = async (imageBase64) => {
    if (!imageBase64 || !BioVaultModule?.extractWatermark) return;
    setWmExtracting(true);
    try {
      const decoded = await BioVaultModule.extractWatermark(imageBase64);
      if (decoded) {
        const parsed = JSON.parse(decoded);
        setWatermarkData(parsed);
        Clipboard.setString(JSON.stringify(parsed, null, 2));
        Alert.alert('Watermark Extracted', 'Data copied to clipboard');
        console.log('[VitalsNet] Watermark extracted:', decoded);
      } else {
        setWatermarkData(null);
      }
    } catch (err) {
      console.warn('[VitalsNet] Watermark extraction failed:', err.message);
      setWatermarkData(null);
    } finally {
      setWmExtracting(false);
    }
  };

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>Verify Capture</Text>
          <Text style={styles.headerDesc}>
            Enter a VitalsNet Capture ID to verify its authenticity and origin
          </Text>
        </View>

        {/* Search */}
        <View style={styles.searchBox}>
          <TextInput
            style={styles.input}
            placeholder="BV-XXXXXXXXXXXX"
            placeholderTextColor="#52525b"
            value={searchId}
            onChangeText={setSearchId}
            autoCapitalize="characters"
            autoCorrect={false}
          />
          <TouchableOpacity
            style={styles.searchBtn}
            onPress={() => handleVerify()}
            disabled={searching}>
            {searching ? (
              <ActivityIndicator color="#fff" size="small" />
            ) : (
              <Text style={styles.searchBtnText}>VERIFY</Text>
            )}
          </TouchableOpacity>
        </View>

        {/* Result — Verified */}
        {result && (
          <View style={styles.resultCard}>
            {/* Source indicator */}
            {result.source === 'cloud' && (
              <View style={{backgroundColor: '#22c55e18', borderRadius: 8, paddingHorizontal: 12, paddingVertical: 6, marginBottom: 12, alignSelf: 'flex-start'}}>
                <Text style={{color: '#22c55e', fontSize: 12, fontWeight: '600'}}>☁ Found on Cloud (cross-device)</Text>
              </View>
            )}
            {/* Risk Score (if available) */}
            {result.riskScore != null && (
              <View style={styles.scoreRow}>
                <View style={[styles.scoreCircle, {borderColor: result.riskScore >= 75 ? '#22c55e' : result.riskScore >= 50 ? '#eab308' : result.riskScore >= 25 ? '#f97316' : '#ef4444'}]}>
                  <Text style={[styles.scoreNum, {color: result.riskScore >= 75 ? '#22c55e' : result.riskScore >= 50 ? '#eab308' : result.riskScore >= 25 ? '#f97316' : '#ef4444'}]}>
                    {result.riskScore}
                  </Text>
                </View>
                <View style={styles.scoreInfo}>
                  <Text style={styles.scoreTitle}>Reality Score</Text>
                  <View style={[styles.scoreBadge, {backgroundColor: (result.riskScore >= 75 ? '#22c55e' : result.riskScore >= 50 ? '#eab308' : '#f97316') + '18'}]}>
                    <Text style={[styles.scoreBadgeText, {color: result.riskScore >= 75 ? '#22c55e' : result.riskScore >= 50 ? '#eab308' : '#f97316'}]}>
                      {result.riskLabel || (result.riskScore >= 75 ? 'VERIFIED' : result.riskScore >= 50 ? 'MEDIUM' : 'LOW')}
                    </Text>
                  </View>
                </View>
              </View>
            )}

            <View style={[styles.resultBadge, result.originVerified ? styles.badgeGreen : styles.badgeOrange]}>
              <Text style={styles.badgeIcon}>{result.originVerified ? '' : ''}</Text>
              <Text style={[styles.badgeTitle, result.originVerified ? styles.greenText : styles.orangeText]}>
                {result.originVerified ? 'ORIGIN VERIFIED' : 'ORIGIN UNVERIFIED'}
              </Text>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Capture ID</Text>
              <Text style={styles.detailValue}>{result.captureId}</Text>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Captured</Text>
              <Text style={styles.detailValue}>
                {new Date(result.timestamp).toLocaleString()}
              </Text>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Device Bound</Text>
              <View style={[styles.miniTag, result.deviceFingerprint ? styles.miniGreen : styles.miniRed]}>
                <Text style={styles.miniTagText}>{result.deviceFingerprint ? 'YES' : 'NO'}</Text>
              </View>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Bio-Signal</Text>
              <Text style={styles.detailValue}>{result.bpm} BPM @ {result.confidence}%</Text>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Duration</Text>
              <Text style={styles.detailValue}>{result.duration}s</Text>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Frames</Text>
              <Text style={styles.detailValue}>{result.framesProcessed}</Text>
            </View>

            <View style={styles.detailRow}>
              <Text style={styles.detailLabel}>Faces Detected</Text>
              <Text style={styles.detailValue}>{result.facesDetected}</Text>
            </View>

            <View style={styles.hashSection}>
              <Text style={styles.hashLabel}>Content Hash</Text>
              <Text style={styles.hashValue} selectable>{result.contentHash}</Text>
            </View>

            <View style={styles.hashSection}>
              <Text style={styles.hashLabel}>Device Fingerprint</Text>
              <Text style={styles.hashValue} selectable>{result.deviceFingerprint || 'N/A'}</Text>
            </View>
          </View>
        )}

        {/* Not Found */}
        {notFound && (
          <View style={styles.notFoundCard}>
          <Text style={styles.notFoundTitle}>NO RECORD FOUND</Text>
            <Text style={styles.notFoundDesc}>
              This Capture ID was not found in the local database.
              The media was either not captured through VitalsNet or has been tampered with.
            </Text>
          </View>
        )}

        {/* Watermark Extraction */}
        {(wmExtracting || watermarkData !== null) && (
          <View style={styles.resultCard}>
            <View style={[styles.resultBadge, watermarkData ? styles.badgeGreen : styles.badgeOrange]}>
              <Text style={styles.badgeIcon}>{wmExtracting ? '' : watermarkData ? '' : ''}</Text>
              <Text style={[styles.badgeTitle, watermarkData ? styles.greenText : styles.orangeText]}>
                {wmExtracting ? 'EXTRACTING WATERMARK...' : watermarkData ? 'WATERMARK VERIFIED' : 'NO WATERMARK'}
              </Text>
            </View>
            {watermarkData && (
              <>
                {watermarkData.bpm ? (
                  <View style={styles.detailRow}>
                    <Text style={styles.detailLabel}>Embedded BPM</Text>
                    <Text style={styles.detailValue}>{watermarkData.bpm} BPM</Text>
                  </View>
                ) : null}
                {watermarkData.dna ? (
                  <View style={styles.detailRow}>
                    <Text style={styles.detailLabel}>Device DNA (prefix)</Text>
                    <Text style={styles.detailValue}>{watermarkData.dna}</Text>
                  </View>
                ) : null}
                {watermarkData.ts ? (
                  <View style={styles.detailRow}>
                    <Text style={styles.detailLabel}>Captured At</Text>
                    <Text style={styles.detailValue}>{new Date(watermarkData.ts * 1000).toLocaleString()}</Text>
                  </View>
                ) : null}
                {watermarkData.id ? (
                  <View style={styles.detailRow}>
                    <Text style={styles.detailLabel}>Watermark ID</Text>
                    <Text style={styles.detailValue}>{watermarkData.id}</Text>
                  </View>
                ) : null}
              </>
            )}
          </View>
        )}

        {/* Import Watermarked Image */}
        <TouchableOpacity
          style={styles.importBtn}
          onPress={async () => {
            try {
              // Look for watermarked images saved by VitalsNet in the media dir
              const mediaDir = RNFS.DocumentDirectoryPath + '/biovault/media';
              const exists = await RNFS.exists(mediaDir);
              if (!exists) {
                Alert.alert('No Media', 'No VitalsNet capture files found. Take a capture first, then share the watermarked image and verify it here.');
                return;
              }
              // Read the latest watermarked PNG from Downloads or Documents
              // For demo: use the watermark from the most recent capture passed via navigation
              if (route.params?.watermarkedImageBase64) {
                extractWatermark(route.params.watermarkedImageBase64);
              } else {
                Alert.alert(
                  'Verify Watermark',
                  'To verify a watermarked image:\n\n1. Take a capture in VitalsNet\n2. From Results, tap "Verify This Capture"\n3. The watermark is automatically extracted\n\nThe watermarked image data is embedded in every VitalsNet capture.',
                  [{text: 'OK'}]
                );
              }
            } catch (e) {
              Alert.alert('Error', e.message);
            }
          }}>
          <Text style={styles.importBtnText}>Extract Watermark from Capture</Text>
        </TouchableOpacity>

        {/* Back */}
        <TouchableOpacity
          style={styles.backBtn}
          onPress={() => navigation.navigate('Home')}>
          <Text style={styles.backBtnText}>← Back to Dashboard</Text>
        </TouchableOpacity>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#09090b'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  header: {alignItems: 'center', marginBottom: 24},
  headerTitle: {color: '#fafafa', fontSize: 22, fontWeight: '700', marginBottom: 6},
  headerDesc: {color: '#71717a', fontSize: 13, textAlign: 'center', lineHeight: 18},

  searchBox: {
    flexDirection: 'row',
    marginBottom: 24,
  },
  input: {
    flex: 1,
    backgroundColor: '#18181b',
    borderWidth: 1,
    borderColor: '#27272a',
    borderRadius: 10,
    paddingHorizontal: 16,
    paddingVertical: 14,
    color: '#fafafa',
    fontSize: 16,
    fontFamily: 'monospace',
    marginRight: 10,
  },
  searchBtn: {
    backgroundColor: '#fafafa',
    paddingHorizontal: 20,
    borderRadius: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  searchBtnText: {color: '#09090b', fontSize: 14, fontWeight: '600'},

  resultCard: {
    backgroundColor: '#18181b',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  resultBadge: {
    alignItems: 'center',
    padding: 16,
    borderRadius: 10,
    marginBottom: 16,
  },
  badgeGreen: {backgroundColor: 'rgba(34,197,94,0.08)'},
  badgeOrange: {backgroundColor: 'rgba(249,115,22,0.08)'},
  badgeIcon: {fontSize: 0, height: 0},
  badgeTitle: {fontSize: 16, fontWeight: '700'},
  greenText: {color: '#22c55e'},
  orangeText: {color: '#f97316'},

  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#27272a',
  },
  detailLabel: {color: '#71717a', fontSize: 14},
  detailValue: {color: '#fafafa', fontSize: 14, fontWeight: '600'},

  miniTag: {paddingHorizontal: 10, paddingVertical: 3, borderRadius: 6},
  miniGreen: {backgroundColor: 'rgba(34,197,94,0.12)'},
  miniRed: {backgroundColor: 'rgba(239,68,68,0.12)'},
  miniTagText: {color: '#fafafa', fontSize: 11, fontWeight: '600'},

  hashSection: {marginTop: 12},
  hashLabel: {color: '#71717a', fontSize: 12, marginBottom: 4},
  hashValue: {
    color: '#a1a1aa',
    fontSize: 10,
    fontFamily: 'monospace',
    lineHeight: 14,
    backgroundColor: '#09090b',
    padding: 10,
    borderRadius: 8,
  },

  notFoundCard: {
    backgroundColor: 'rgba(239,68,68,0.05)',
    borderRadius: 12,
    padding: 24,
    alignItems: 'center',
    marginBottom: 20,
    borderWidth: 1,
    borderColor: 'rgba(239,68,68,0.15)',
  },
  notFoundTitle: {color: '#ef4444', fontSize: 18, fontWeight: '700', marginBottom: 8},
  notFoundDesc: {color: '#71717a', fontSize: 13, textAlign: 'center', lineHeight: 18},

  backBtn: {padding: 16, alignItems: 'center'},
  backBtnText: {color: '#71717a', fontSize: 14},

  // ── Risk Score in Result Card ──
  scoreRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 16,
    paddingBottom: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#27272a',
  },
  scoreCircle: {
    width: 60,
    height: 60,
    borderRadius: 30,
    borderWidth: 2,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#09090b',
    marginRight: 14,
  },
  scoreNum: {fontSize: 22, fontWeight: '700'},
  scoreInfo: {flex: 1},
  scoreTitle: {color: '#71717a', fontSize: 11, fontWeight: '700', letterSpacing: 1, marginBottom: 4},
  scoreBadge: {alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 3, borderRadius: 10},
  scoreBadgeText: {fontSize: 12, fontWeight: '700'},

  // ── Import Button ──
  importBtn: {
    backgroundColor: '#18181b',
    padding: 16,
    borderRadius: 10,
    alignItems: 'center',
    marginBottom: 12,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  importBtnText: {color: '#a1a1aa', fontSize: 15, fontWeight: '600'},
});
