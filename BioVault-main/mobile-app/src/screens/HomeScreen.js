import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import VitalsNetLogo from '../components/VitalsNetLogo';

const CAPTURES_KEY = 'biovault_captures';

export default function HomeScreen({navigation}) {
  const [captures, setCaptures] = useState([]);
  const [stats, setStats] = useState({total: 0, verified: 0, unverified: 0});

  useEffect(() => {
    console.log('[VitalsNet] HomeScreen mounted');
    const unsubscribe = navigation.addListener('focus', loadCaptures);
    return unsubscribe;
  }, [navigation]);

  const loadCaptures = async () => {
    try {
      const raw = await AsyncStorage.getItem(CAPTURES_KEY);
      const data = raw ? JSON.parse(raw) : [];
      setCaptures(data);
      setStats({
        total: data.length,
        verified: data.filter(c => c.originVerified).length,
        unverified: data.filter(c => !c.originVerified).length,
      });
    } catch (e) {
      console.warn('Failed to load captures:', e);
    }
  };

  const renderCapture = (item) => (
    <TouchableOpacity
      key={item.captureId}
      style={styles.captureCard}
      onPress={() => navigation.navigate('Verify', {captureId: item.captureId})}>
      <View style={styles.captureHeader}>
        <View style={[styles.captureIndicator, item.originVerified ? styles.indicatorGreen : styles.indicatorMuted]} />
        <View style={{flex: 1}}>
          <Text style={styles.captureId}>{item.captureId}</Text>
          <Text style={styles.captureTime}>
            {new Date(item.timestamp).toLocaleString()}
          </Text>
        </View>
        {item.riskScore != null ? (
          <View style={[styles.captureScoreBadge, {
            backgroundColor: (item.riskScore >= 75 ? '#22c55e' : item.riskScore >= 50 ? '#eab308' : '#f97316') + '18',
          }]}>
            <Text style={[styles.captureScoreText, {
              color: item.riskScore >= 75 ? '#22c55e' : item.riskScore >= 50 ? '#eab308' : '#f97316',
            }]}>
              {item.riskScore}
            </Text>
          </View>
        ) : (
          <View style={[styles.captureTag, item.originVerified ? styles.tagVerified : styles.tagUnverified]}>
            <Text style={styles.captureTagText}>
              {item.originVerified ? 'VERIFIED' : 'UNVERIFIED'}
            </Text>
          </View>
        )}
      </View>
      <View style={styles.captureDetails}>
        <Text style={styles.captureDetail}>{item.bpm} BPM · {item.confidence}%</Text>
        <Text style={styles.captureDetail}>{item.duration}s</Text>
        <Text style={styles.captureDetail}>{item.deviceFingerprint ? 'Bound' : 'Unbound'}</Text>
        {item.riskLabel ? <Text style={styles.captureDetail}>{item.riskLabel}</Text> : null}
      </View>
    </TouchableOpacity>
  );

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <View style={styles.header}>
          <VitalsNetLogo />
          <Text style={styles.title}>VitalsNet</Text>
          <Text style={styles.subtitle}>Proof of Human Capture</Text>
        </View>

        {/* Stats */}
        <View style={styles.statsRow}>
          <View style={styles.statBox}>
            <Text style={styles.statNumber}>{stats.total}</Text>
            <Text style={styles.statLabel}>Captures</Text>
          </View>
          <View style={[styles.statBox, styles.statBoxGreen]}>
            <Text style={[styles.statNumber, styles.greenText]}>{stats.verified}</Text>
            <Text style={styles.statLabel}>Verified</Text>
          </View>
          <View style={[styles.statBox, styles.statBoxOrange]}>
            <Text style={[styles.statNumber, styles.orangeText]}>{stats.unverified}</Text>
            <Text style={styles.statLabel}>Unverified</Text>
          </View>
        </View>

        {/* Action Buttons */}
        <TouchableOpacity
          style={styles.captureBtn}
          onPress={() => navigation.navigate('Camera')}>
          <View>
            <Text style={styles.captureBtnText}>New Capture</Text>
            <Text style={styles.captureBtnHint}>Record 30s bio-signature + device DNA</Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.verifyBtn}
          onPress={() => navigation.navigate('Verify')}>
          <View>
            <Text style={styles.verifyBtnText}>Verify Media</Text>
            <Text style={styles.verifyBtnHint}>Check origin authenticity with Capture ID</Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.privacyBtn}
          onPress={() => navigation.navigate('PrivacyShield')}>
          <View>
            <Text style={styles.privacyBtnText}>Privacy Shield</Text>
            <Text style={styles.privacyBtnHint}>Broadcast VitalsID · Get notified of unauthorized captures</Text>
          </View>
        </TouchableOpacity>

        <View style={styles.complianceBanner}>
          <Text style={styles.complianceTitle}>IT Rules 2026 Ready</Text>
          <View style={styles.complianceRow}>
            <Text style={styles.complianceItem}>Device Binding</Text>
            <Text style={styles.complianceItem}>Origin Proof</Text>
          </View>
          <View style={styles.complianceRow}>
            <Text style={styles.complianceItem}>Bio-Signal Capture</Text>
            <Text style={styles.complianceItem}>Content Hashing</Text>
          </View>
        </View>

        {/* Recent Captures */}
        {captures.length > 0 && (
          <View style={styles.recentSection}>
            <Text style={styles.recentTitle}>Recent Captures</Text>
            {captures.slice(0, 10).map(renderCapture)}
          </View>
        )}

        {captures.length === 0 && (
          <View style={styles.emptyState}>
            <Text style={styles.emptyTitle}>No captures yet</Text>
            <Text style={styles.emptyDesc}>
              Tap "New Capture" to record your first bio-signature
            </Text>
          </View>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#09090b'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  header: {alignItems: 'center', marginBottom: 28},
  logoMark: {
    fontSize: 20,
    fontWeight: '800',
    color: '#fafafa',
    letterSpacing: 6,
    marginBottom: 14,
    borderWidth: 1.5,
    borderColor: '#fafafa',
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 6,
  },
  title: {color: '#fafafa', fontSize: 24, fontWeight: '700', letterSpacing: 1, marginTop: 16},
  subtitle: {color: '#71717a', fontSize: 13, fontWeight: '500', marginTop: 4},

  statsRow: {
    flexDirection: 'row',
    marginBottom: 24,
    gap: 10,
  },
  statBox: {
    flex: 1,
    backgroundColor: '#18181b',
    borderRadius: 10,
    padding: 16,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#27272a',
  },
  statBoxGreen: {borderColor: 'rgba(34,197,94,0.25)'},
  statBoxOrange: {borderColor: 'rgba(249,115,22,0.25)'},
  statNumber: {color: '#fafafa', fontSize: 22, fontWeight: '700'},
  statLabel: {color: '#71717a', fontSize: 11, marginTop: 4},
  greenText: {color: '#22c55e'},
  orangeText: {color: '#f97316'},

  captureBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fafafa',
    padding: 18,
    borderRadius: 12,
    marginBottom: 12,
  },
  captureBtnText: {color: '#09090b', fontSize: 16, fontWeight: '600'},
  captureBtnHint: {color: '#52525b', fontSize: 12, marginTop: 2},

  verifyBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#18181b',
    padding: 18,
    borderRadius: 12,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  verifyBtnText: {color: '#fafafa', fontSize: 16, fontWeight: '600'},
  verifyBtnHint: {color: '#71717a', fontSize: 12, marginTop: 2},

  privacyBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#0a1a0f',
    padding: 18,
    borderRadius: 12,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: '#22c55e33',
  },
  privacyBtnText: {color: '#22c55e', fontSize: 16, fontWeight: '600'},
  privacyBtnHint: {color: '#71717a', fontSize: 12, marginTop: 2},

  complianceBanner: {
    backgroundColor: '#18181b',
    borderRadius: 10,
    padding: 16,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  complianceTitle: {color: '#a1a1aa', fontSize: 13, fontWeight: '600', marginBottom: 8, letterSpacing: 0.5},
  complianceRow: {flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4},
  complianceItem: {color: '#71717a', fontSize: 12},

  recentSection: {marginTop: 4},
  recentTitle: {color: '#fafafa', fontSize: 15, fontWeight: '600', marginBottom: 12},

  captureCard: {
    backgroundColor: '#18181b',
    borderRadius: 10,
    padding: 14,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  captureHeader: {flexDirection: 'row', alignItems: 'center', marginBottom: 8},
  captureIndicator: {width: 8, height: 8, borderRadius: 4, marginRight: 10},
  indicatorGreen: {backgroundColor: '#22c55e'},
  indicatorMuted: {backgroundColor: '#71717a'},
  captureId: {color: '#a1a1aa', fontSize: 13, fontWeight: '600', fontFamily: 'monospace'},
  captureTime: {color: '#52525b', fontSize: 11, marginTop: 2},
  captureTag: {paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6},
  tagVerified: {backgroundColor: 'rgba(34,197,94,0.12)'},
  tagUnverified: {backgroundColor: 'rgba(249,115,22,0.12)'},
  captureTagText: {color: '#a1a1aa', fontSize: 10, fontWeight: '600'},
  captureScoreBadge: {
    width: 36, height: 36, borderRadius: 18,
    justifyContent: 'center', alignItems: 'center',
  },
  captureScoreText: {fontSize: 14, fontWeight: '700'},
  captureDetails: {flexDirection: 'row', flexWrap: 'wrap', gap: 8},
  captureDetail: {color: '#71717a', fontSize: 11},

  emptyState: {alignItems: 'center', marginTop: 48},
  emptyTitle: {color: '#a1a1aa', fontSize: 16, fontWeight: '600', marginBottom: 6},
  emptyDesc: {color: '#52525b', fontSize: 13, textAlign: 'center'},
});
