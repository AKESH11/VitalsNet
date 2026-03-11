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
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

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
        setResult(found);
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
        console.log('[BioVault] Watermark extracted:', decoded);
      } else {
        setWatermarkData(null);
      }
    } catch (err) {
      console.warn('[BioVault] Watermark extraction failed:', err.message);
      setWatermarkData(null);
    } finally {
      setWmExtracting(false);
    }
  };

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.headerIcon}>🔍</Text>
          <Text style={styles.headerTitle}>Verify Capture</Text>
          <Text style={styles.headerDesc}>
            Enter a BioVault Capture ID to verify its authenticity and origin
          </Text>
        </View>

        {/* Search */}
        <View style={styles.searchBox}>
          <TextInput
            style={styles.input}
            placeholder="BV-XXXXXXXXXXXX"
            placeholderTextColor="#555"
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
            <View style={[styles.resultBadge, result.originVerified ? styles.badgeGreen : styles.badgeOrange]}>
              <Text style={styles.badgeIcon}>{result.originVerified ? '✅' : '⚠️'}</Text>
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
            <Text style={styles.notFoundIcon}>❌</Text>
            <Text style={styles.notFoundTitle}>NO RECORD FOUND</Text>
            <Text style={styles.notFoundDesc}>
              This Capture ID was not found in the local database.
              The media was either not captured through BioVault or has been tampered with.
            </Text>
          </View>
        )}

        {/* Watermark Extraction */}
        {(wmExtracting || watermarkData !== null) && (
          <View style={styles.resultCard}>
            <View style={[styles.resultBadge, watermarkData ? styles.badgeGreen : styles.badgeOrange]}>
              <Text style={styles.badgeIcon}>{wmExtracting ? '⏳' : watermarkData ? '🔏' : '❌'}</Text>
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
  container: {flex: 1, backgroundColor: '#0f0f23'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  header: {alignItems: 'center', marginBottom: 24},
  headerIcon: {fontSize: 48, marginBottom: 8},
  headerTitle: {color: '#fff', fontSize: 24, fontWeight: 'bold', marginBottom: 6},
  headerDesc: {color: '#8b8ba7', fontSize: 13, textAlign: 'center', lineHeight: 18},

  searchBox: {
    flexDirection: 'row',
    marginBottom: 24,
  },
  input: {
    flex: 1,
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.15)',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    color: '#fff',
    fontSize: 16,
    fontFamily: 'monospace',
    marginRight: 10,
  },
  searchBtn: {
    backgroundColor: '#6366f1',
    paddingHorizontal: 20,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
  },
  searchBtnText: {color: '#fff', fontSize: 14, fontWeight: 'bold'},

  resultCard: {
    backgroundColor: 'rgba(255,255,255,0.04)',
    borderRadius: 16,
    padding: 20,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  resultBadge: {
    alignItems: 'center',
    padding: 16,
    borderRadius: 12,
    marginBottom: 16,
  },
  badgeGreen: {backgroundColor: 'rgba(0,255,136,0.1)'},
  badgeOrange: {backgroundColor: 'rgba(255,152,0,0.1)'},
  badgeIcon: {fontSize: 36, marginBottom: 6},
  badgeTitle: {fontSize: 18, fontWeight: 'bold'},
  greenText: {color: '#00ff88'},
  orangeText: {color: '#ff9800'},

  detailRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: 'rgba(255,255,255,0.05)',
  },
  detailLabel: {color: '#8b8ba7', fontSize: 14},
  detailValue: {color: '#fff', fontSize: 14, fontWeight: '600'},

  miniTag: {paddingHorizontal: 10, paddingVertical: 3, borderRadius: 6},
  miniGreen: {backgroundColor: 'rgba(0,255,136,0.15)'},
  miniRed: {backgroundColor: 'rgba(255,68,68,0.15)'},
  miniTagText: {color: '#fff', fontSize: 11, fontWeight: 'bold'},

  hashSection: {marginTop: 12},
  hashLabel: {color: '#8b8ba7', fontSize: 12, marginBottom: 4},
  hashValue: {
    color: '#6366f1',
    fontSize: 10,
    fontFamily: 'monospace',
    lineHeight: 14,
    backgroundColor: 'rgba(0,0,0,0.3)',
    padding: 10,
    borderRadius: 8,
  },

  notFoundCard: {
    backgroundColor: 'rgba(255,68,68,0.06)',
    borderRadius: 16,
    padding: 24,
    alignItems: 'center',
    marginBottom: 20,
    borderWidth: 1,
    borderColor: 'rgba(255,68,68,0.2)',
  },
  notFoundIcon: {fontSize: 48, marginBottom: 8},
  notFoundTitle: {color: '#ff4444', fontSize: 20, fontWeight: 'bold', marginBottom: 8},
  notFoundDesc: {color: '#8b8ba7', fontSize: 13, textAlign: 'center', lineHeight: 18},

  backBtn: {padding: 16, alignItems: 'center'},
  backBtnText: {color: '#8b8ba7', fontSize: 14},
});
