import React, {useState, useEffect, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  NativeModules,
  Alert,
  ActivityIndicator,
  Switch,
  PermissionsAndroid,
  Platform,
} from 'react-native';
import {queryPrivacyAlerts, isConfigured} from '../services/FirebaseService';

const {BioVaultModule} = NativeModules;

export default function PrivacyShieldScreen({navigation}) {
  const [shieldActive, setShieldActive] = useState(false);
  const [vitalsId, setVitalsId] = useState('');
  const [loading, setLoading] = useState(true);
  const [alerts, setAlerts] = useState([]);
  const [alertsLoading, setAlertsLoading] = useState(false);

  useEffect(() => {
    console.log('[VitalsNet] PrivacyShieldScreen mounted');
    init();
  }, []);

  const init = async () => {
    try {
      // Get VitalsID
      if (BioVaultModule?.getVitalsId) {
        const id = await BioVaultModule.getVitalsId();
        setVitalsId(id);
      }
      // Check if service is running
      if (BioVaultModule?.isPrivacyShieldActive) {
        const active = await BioVaultModule.isPrivacyShieldActive();
        setShieldActive(active);
      }
    } catch (e) {
      console.warn('[VitalsNet] Privacy Shield init error:', e.message);
    } finally {
      setLoading(false);
    }
  };

  const requestNotificationPermission = async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
        );
        return granted === PermissionsAndroid.RESULTS.GRANTED;
      } catch {
        return false;
      }
    }
    return true;
  };

  const toggleShield = async (newValue) => {
    try {
      if (newValue) {
        await requestNotificationPermission();

        if (!vitalsId) {
          Alert.alert('Error', 'VitalsID not available. Please calibrate your device first.');
          return;
        }
        await BioVaultModule.startPrivacyShield(vitalsId);
        setShieldActive(true);
        console.log('[VitalsNet] Privacy Shield activated');
      } else {
        await BioVaultModule.stopPrivacyShield();
        setShieldActive(false);
        console.log('[VitalsNet] Privacy Shield deactivated');
      }
    } catch (e) {
      Alert.alert('Error', 'Failed to toggle Privacy Shield: ' + e.message);
    }
  };

  const loadAlerts = useCallback(async () => {
    if (!vitalsId || !isConfigured()) return;
    setAlertsLoading(true);
    try {
      const shortId = vitalsId.substring(0, 8);
      const results = await queryPrivacyAlerts(shortId, 20);
      setAlerts(results.filter(Boolean));
      console.log('[VitalsNet] Loaded', results.length, 'privacy alerts');
    } catch (e) {
      console.warn('[VitalsNet] Failed to load alerts:', e.message);
    } finally {
      setAlertsLoading(false);
    }
  }, [vitalsId]);

  useEffect(() => {
    if (vitalsId) loadAlerts();
  }, [vitalsId, loadAlerts]);

  const formatTime = (ts) => {
    try {
      return new Date(ts).toLocaleString();
    } catch {
      return ts;
    }
  };

  if (loading) {
    return (
      <View style={[styles.container, styles.center]}>
        <ActivityIndicator size="large" color="#22c55e" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        {/* Header */}
        <View style={styles.header}>
          <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backBtn}>
            <Text style={styles.backText}>← Back</Text>
          </TouchableOpacity>
          <Text style={styles.title}>Privacy Shield</Text>
          <Text style={styles.subtitle}>
            Protect your identity from unauthorized captures
          </Text>
        </View>

        {/* Shield Toggle */}
        <View style={[styles.shieldCard, shieldActive && styles.shieldCardActive]}>
          <View style={styles.shieldHeader}>
            <View style={[styles.shieldIndicator, shieldActive ? styles.indicatorActive : styles.indicatorInactive]} />
            <View style={{flex: 1}}>
              <Text style={styles.shieldTitle}>
                {shieldActive ? 'Shield Active' : 'Shield Inactive'}
              </Text>
              <Text style={styles.shieldDesc}>
                {shieldActive
                  ? 'Your VitalsID is being broadcast via BLE'
                  : 'Enable to broadcast your presence via BLE'}
              </Text>
            </View>
            <Switch
              value={shieldActive}
              onValueChange={toggleShield}
              trackColor={{false: '#3f3f46', true: '#16a34a'}}
              thumbColor={shieldActive ? '#22c55e' : '#71717a'}
            />
          </View>
        </View>

        {/* VitalsID */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Your VitalsID</Text>
          <View style={styles.idBox}>
            <Text style={styles.idText}>{vitalsId || 'Not generated'}</Text>
          </View>
          <Text style={styles.idHint}>
            BLAKE3 hash derived from your device's PRNU fingerprint
          </Text>
        </View>

        {/* How it Works */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>How It Works</Text>
          <View style={styles.stepRow}>
            <View style={styles.stepDot}><Text style={styles.stepNum}>1</Text></View>
            <Text style={styles.stepText}>Your device broadcasts a VitalsID beacon via BLE</Text>
          </View>
          <View style={styles.stepRow}>
            <View style={styles.stepDot}><Text style={styles.stepNum}>2</Text></View>
            <Text style={styles.stepText}>When someone captures media nearby, their device scans for beacons</Text>
          </View>
          <View style={styles.stepRow}>
            <View style={styles.stepDot}><Text style={styles.stepNum}>3</Text></View>
            <Text style={styles.stepText}>If your VitalsID is detected but no consent signature exists → violation</Text>
          </View>
          <View style={styles.stepRow}>
            <View style={styles.stepDot}><Text style={styles.stepNum}>4</Text></View>
            <Text style={styles.stepText}>You receive a real-time alert via Firebase</Text>
          </View>
        </View>

        {/* Alerts */}
        <View style={styles.section}>
          <View style={styles.sectionHeaderRow}>
            <Text style={styles.sectionTitle}>Privacy Alerts</Text>
            <TouchableOpacity onPress={loadAlerts} style={styles.refreshBtn}>
              <Text style={styles.refreshText}>{alertsLoading ? '...' : '↻ Refresh'}</Text>
            </TouchableOpacity>
          </View>

          {alerts.length === 0 ? (
            <View style={styles.emptyAlert}>
              <Text style={styles.emptyAlertIcon}>✓</Text>
              <Text style={styles.emptyAlertText}>No violations detected</Text>
              <Text style={styles.emptyAlertHint}>
                You'll be notified if anyone captures media near you without consent
              </Text>
            </View>
          ) : (
            alerts.map((alert, idx) => (
              <View key={alert.alertId || idx} style={styles.alertCard}>
                <View style={styles.alertHeader}>
                  <View style={styles.alertDot} />
                  <Text style={styles.alertTitle}>Privacy Violation Detected</Text>
                </View>
                <Text style={styles.alertTime}>{formatTime(alert.timestamp)}</Text>
                <View style={styles.alertDetails}>
                  <Text style={styles.alertDetail}>
                    Capturer: {(alert.capturerDeviceId || 'Unknown').substring(0, 12)}...
                  </Text>
                  {alert.contentCategory !== 'UNKNOWN' && (
                    <Text style={styles.alertDetail}>Content: {alert.contentCategory}</Text>
                  )}
                  {(alert.latitude !== 0 || alert.longitude !== 0) && (
                    <Text style={styles.alertDetail}>
                      Location: {alert.latitude.toFixed(4)}, {alert.longitude.toFixed(4)}
                    </Text>
                  )}
                </View>
              </View>
            ))
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#09090b'},
  center: {justifyContent: 'center', alignItems: 'center'},
  scroll: {padding: 20, paddingTop: 50, paddingBottom: 40},

  header: {marginBottom: 24},
  backBtn: {marginBottom: 16},
  backText: {color: '#a1a1aa', fontSize: 14},
  title: {color: '#fafafa', fontSize: 24, fontWeight: '700', letterSpacing: 0.5},
  subtitle: {color: '#71717a', fontSize: 13, marginTop: 4},

  shieldCard: {
    backgroundColor: '#18181b',
    borderRadius: 14,
    padding: 20,
    marginBottom: 24,
    borderWidth: 1.5,
    borderColor: '#27272a',
  },
  shieldCardActive: {
    borderColor: '#22c55e44',
    backgroundColor: '#0a1a0f',
  },
  shieldHeader: {flexDirection: 'row', alignItems: 'center'},
  shieldIndicator: {width: 12, height: 12, borderRadius: 6, marginRight: 14},
  indicatorActive: {backgroundColor: '#22c55e', shadowColor: '#22c55e', shadowRadius: 8, elevation: 4},
  indicatorInactive: {backgroundColor: '#52525b'},
  shieldTitle: {color: '#fafafa', fontSize: 17, fontWeight: '700'},
  shieldDesc: {color: '#71717a', fontSize: 12, marginTop: 2},

  section: {marginBottom: 24},
  sectionTitle: {color: '#a1a1aa', fontSize: 13, fontWeight: '600', letterSpacing: 0.5, marginBottom: 12},
  sectionHeaderRow: {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12},

  idBox: {
    backgroundColor: '#18181b',
    borderRadius: 8,
    padding: 14,
    borderWidth: 1,
    borderColor: '#27272a',
  },
  idText: {color: '#22c55e', fontSize: 13, fontWeight: '600', fontFamily: 'monospace'},
  idHint: {color: '#52525b', fontSize: 11, marginTop: 6},

  stepRow: {flexDirection: 'row', alignItems: 'flex-start', marginBottom: 12},
  stepDot: {
    width: 24, height: 24, borderRadius: 12,
    backgroundColor: '#22c55e18',
    justifyContent: 'center', alignItems: 'center',
    marginRight: 12,
  },
  stepNum: {color: '#22c55e', fontSize: 12, fontWeight: '700'},
  stepText: {flex: 1, color: '#a1a1aa', fontSize: 13, lineHeight: 18},

  refreshBtn: {
    paddingHorizontal: 12, paddingVertical: 6,
    backgroundColor: '#27272a',
    borderRadius: 6,
  },
  refreshText: {color: '#a1a1aa', fontSize: 12, fontWeight: '600'},

  emptyAlert: {alignItems: 'center', paddingVertical: 32},
  emptyAlertIcon: {color: '#22c55e', fontSize: 28, marginBottom: 8},
  emptyAlertText: {color: '#a1a1aa', fontSize: 15, fontWeight: '600'},
  emptyAlertHint: {color: '#52525b', fontSize: 12, textAlign: 'center', marginTop: 4, maxWidth: 260},

  alertCard: {
    backgroundColor: '#1c1117',
    borderRadius: 10,
    padding: 14,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: '#ef444433',
  },
  alertHeader: {flexDirection: 'row', alignItems: 'center', marginBottom: 6},
  alertDot: {width: 8, height: 8, borderRadius: 4, backgroundColor: '#ef4444', marginRight: 8},
  alertTitle: {color: '#ef4444', fontSize: 14, fontWeight: '600'},
  alertTime: {color: '#71717a', fontSize: 11, marginBottom: 6},
  alertDetails: {marginTop: 4},
  alertDetail: {color: '#a1a1aa', fontSize: 12, marginBottom: 2},
});
