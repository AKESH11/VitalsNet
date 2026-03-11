import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

const CAPTURES_KEY = 'biovault_captures';

export default function HomeScreen({navigation}) {
  const [captures, setCaptures] = useState([]);
  const [stats, setStats] = useState({total: 0, verified: 0, unverified: 0});

  useEffect(() => {
    console.log('[BioVault] HomeScreen mounted');
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
        <Text style={styles.captureIcon}>
          {item.originVerified ? '✅' : '⚠️'}
        </Text>
        <View style={{flex: 1}}>
          <Text style={styles.captureId}>{item.captureId}</Text>
          <Text style={styles.captureTime}>
            {new Date(item.timestamp).toLocaleString()}
          </Text>
        </View>
        <View style={[styles.captureTag, item.originVerified ? styles.tagVerified : styles.tagUnverified]}>
          <Text style={styles.captureTagText}>
            {item.originVerified ? 'VERIFIED' : 'UNVERIFIED'}
          </Text>
        </View>
      </View>
      <View style={styles.captureDetails}>
        <Text style={styles.captureDetail}>💚 {item.bpm} BPM @ {item.confidence}%</Text>
        <Text style={styles.captureDetail}>⏱️ {item.duration}s</Text>
        <Text style={styles.captureDetail}>📱 {item.deviceFingerprint ? 'Bound' : 'No DNA'}</Text>
      </View>
    </TouchableOpacity>
  );

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.logo}>🔐</Text>
          <Text style={styles.title}>BioVault</Text>
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
          <Text style={styles.captureBtnIcon}>📸</Text>
          <View>
            <Text style={styles.captureBtnText}>New Capture</Text>
            <Text style={styles.captureBtnHint}>Record 30s bio-signature + device DNA</Text>
          </View>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.verifyBtn}
          onPress={() => navigation.navigate('Verify')}>
          <Text style={styles.verifyBtnIcon}>🔍</Text>
          <View>
            <Text style={styles.verifyBtnText}>Verify Media</Text>
            <Text style={styles.verifyBtnHint}>Check origin authenticity with Capture ID</Text>
          </View>
        </TouchableOpacity>

        {/* IT Rules 2026 Compliance Banner */}
        <View style={styles.complianceBanner}>
          <Text style={styles.complianceTitle}>🇮🇳 IT Rules 2026 Ready</Text>
          <View style={styles.complianceRow}>
            <Text style={styles.complianceItem}>✓ Device Binding</Text>
            <Text style={styles.complianceItem}>✓ Origin Proof</Text>
          </View>
          <View style={styles.complianceRow}>
            <Text style={styles.complianceItem}>✓ Bio-Signal Capture</Text>
            <Text style={styles.complianceItem}>✓ Content Hashing</Text>
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
            <Text style={styles.emptyIcon}>📱</Text>
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
  container: {flex: 1, backgroundColor: '#0f0f23'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  header: {alignItems: 'center', marginBottom: 24},
  logo: {fontSize: 48, marginBottom: 8},
  title: {color: '#fff', fontSize: 28, fontWeight: 'bold'},
  subtitle: {color: '#00ff88', fontSize: 14, fontWeight: '600', marginTop: 4},

  statsRow: {
    flexDirection: 'row',
    marginBottom: 24,
    gap: 10,
  },
  statBox: {
    flex: 1,
    backgroundColor: 'rgba(255,255,255,0.04)',
    borderRadius: 12,
    padding: 16,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  statBoxGreen: {borderColor: 'rgba(0,255,136,0.2)'},
  statBoxOrange: {borderColor: 'rgba(255,152,0,0.2)'},
  statNumber: {color: '#fff', fontSize: 24, fontWeight: 'bold'},
  statLabel: {color: '#8b8ba7', fontSize: 11, marginTop: 4},
  greenText: {color: '#00ff88'},
  orangeText: {color: '#ff9800'},

  captureBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#6366f1',
    padding: 18,
    borderRadius: 14,
    marginBottom: 12,
  },
  captureBtnIcon: {fontSize: 28, marginRight: 14},
  captureBtnText: {color: '#fff', fontSize: 17, fontWeight: 'bold'},
  captureBtnHint: {color: 'rgba(255,255,255,0.7)', fontSize: 12, marginTop: 2},

  verifyBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0,255,136,0.08)',
    padding: 18,
    borderRadius: 14,
    marginBottom: 20,
    borderWidth: 1,
    borderColor: 'rgba(0,255,136,0.3)',
  },
  verifyBtnIcon: {fontSize: 28, marginRight: 14},
  verifyBtnText: {color: '#00ff88', fontSize: 17, fontWeight: 'bold'},
  verifyBtnHint: {color: '#8b8ba7', fontSize: 12, marginTop: 2},

  complianceBanner: {
    backgroundColor: 'rgba(255,152,0,0.06)',
    borderRadius: 12,
    padding: 16,
    marginBottom: 24,
    borderWidth: 1,
    borderColor: 'rgba(255,152,0,0.2)',
  },
  complianceTitle: {color: '#ff9800', fontSize: 14, fontWeight: 'bold', marginBottom: 8},
  complianceRow: {flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4},
  complianceItem: {color: '#8b8ba7', fontSize: 12},

  recentSection: {marginTop: 4},
  recentTitle: {color: '#fff', fontSize: 16, fontWeight: 'bold', marginBottom: 12},

  captureCard: {
    backgroundColor: 'rgba(255,255,255,0.04)',
    borderRadius: 12,
    padding: 14,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.06)',
  },
  captureHeader: {flexDirection: 'row', alignItems: 'center', marginBottom: 8},
  captureIcon: {fontSize: 20, marginRight: 10},
  captureId: {color: '#6366f1', fontSize: 13, fontWeight: 'bold', fontFamily: 'monospace'},
  captureTime: {color: '#666', fontSize: 11, marginTop: 2},
  captureTag: {paddingHorizontal: 8, paddingVertical: 3, borderRadius: 6},
  tagVerified: {backgroundColor: 'rgba(0,255,136,0.15)'},
  tagUnverified: {backgroundColor: 'rgba(255,152,0,0.15)'},
  captureTagText: {color: '#fff', fontSize: 10, fontWeight: 'bold'},
  captureDetails: {flexDirection: 'row', justifyContent: 'space-between'},
  captureDetail: {color: '#8b8ba7', fontSize: 11},

  emptyState: {alignItems: 'center', marginTop: 40},
  emptyIcon: {fontSize: 48, marginBottom: 12},
  emptyTitle: {color: '#fff', fontSize: 18, fontWeight: '600', marginBottom: 6},
  emptyDesc: {color: '#8b8ba7', fontSize: 13, textAlign: 'center'},
});
