import React, {useState, useEffect, useRef, useCallback} from 'react';
import {
  View,
  Text,
  StyleSheet,
  Alert,
  PermissionsAndroid,
  Platform,
  NativeModules,
  Animated,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import {BioVaultCameraView} from '../components/BioVaultCameraView';

const {BioVaultModule} = NativeModules;
const CALIBRATION_KEY = 'biovault_prnu_calibrated';
const REQUIRED_FRAMES = 50;

export default function CalibrationScreen({navigation}) {
  const [hasPermission, setHasPermission] = useState(false);
  const [frameCount, setFrameCount] = useState(0);
  const [status, setStatus] = useState('requesting_permission');
  const [fingerprint, setFingerprint] = useState(null);
  const progressAnim = useRef(new Animated.Value(0)).current;
  const hasFinalized = useRef(false);

  useEffect(() => {
    requestCameraPermission();
    initNative();
  }, []);

  useEffect(() => {
    Animated.timing(progressAnim, {
      toValue: Math.min(frameCount / REQUIRED_FRAMES, 1),
      duration: 150,
      useNativeDriver: false,
    }).start();
  }, [frameCount]);

  const requestCameraPermission = async () => {
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Camera Permission',
            message: 'BioVault needs camera access to calibrate your Device DNA fingerprint.',
            buttonPositive: 'Allow',
          },
        );
        const ok = granted === PermissionsAndroid.RESULTS.GRANTED;
        setHasPermission(ok);
        if (ok) setStatus('capturing');
      } catch (err) {
        console.warn('Camera permission error:', err);
        setHasPermission(false);
      }
    } else {
      setHasPermission(true);
      setStatus('capturing');
    }
  };

  const initNative = async () => {
    try {
      // Initialize BioVault core engine (PRNU extractor lives here)
      if (BioVaultModule && BioVaultModule.init) {
        const initResult = await BioVaultModule.init();
        console.log('[BioVault] Core engine initialized:', initResult);
      }
      // Initialize camera bridge
      if (BioVaultModule && BioVaultModule.initializeCamera) {
        await BioVaultModule.initializeCamera('');
        console.log('[BioVault] Camera initialized for calibration');
      }
    } catch (e) {
      console.error('[BioVault] Init error:', e);
    }
  };

  const handleFrameAvailable = useCallback(
    async (event) => {
      const data = event.nativeEvent;
      if (!data.calibrationMode) return;

      const count = data.calibrationFrameCount || 0;
      setFrameCount(count);

      if (data.calibrationComplete && !hasFinalized.current) {
        hasFinalized.current = true;
        setStatus('finalizing');

        try {
          const resultJson = await BioVaultModule.finalizeCalibration();
          console.log('[BioVault] Calibration result:', resultJson);

          const result = JSON.parse(resultJson);
          if (result.success && result.hardwareFingerprint) {
            setFingerprint(result.hardwareFingerprint);
            setStatus('complete');

            await AsyncStorage.setItem(
              CALIBRATION_KEY,
              JSON.stringify({
                calibrated: true,
                fingerprint: result.hardwareFingerprint,
                timestamp: Date.now(),
              }),
            );

            // Auto-navigate after showing success
            setTimeout(() => {
              navigation.replace('Home');
            }, 2000);
          } else {
            setStatus('error');
            Alert.alert(
              'Calibration Failed',
              result.error || 'Unknown error. Please retry.',
              [{text: 'Retry', onPress: () => retryCalibration()}],
            );
          }
        } catch (e) {
          console.error('[BioVault] Finalize error:', e);
          setStatus('error');
          Alert.alert('Calibration Error', e.message, [
            {text: 'Retry', onPress: () => retryCalibration()},
          ]);
        }
      }
    },
    [],
  );

  const retryCalibration = () => {
    hasFinalized.current = false;
    setFrameCount(0);
    setStatus('capturing');
    setFingerprint(null);
  };

  const progressWidth = progressAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['0%', '100%'],
  });

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.emoji}>🧬</Text>
        <Text style={styles.title}>Device DNA Calibration</Text>
        <Text style={styles.subtitle}>
          {status === 'requesting_permission'
            ? 'Requesting camera access...'
            : status === 'capturing'
            ? 'Point camera at a flat, evenly-lit surface'
            : status === 'finalizing'
            ? 'Computing PRNU fingerprint...'
            : status === 'complete'
            ? 'Calibration complete!'
            : 'Calibration failed'}
        </Text>
      </View>

      <View style={styles.cameraContainer}>
        {hasPermission && status !== 'complete' ? (
          <BioVaultCameraView
            style={styles.camera}
            active={true}
            calibrationMode={true}
            onFrameAvailable={handleFrameAvailable}
          />
        ) : status === 'complete' ? (
          <View style={styles.dnaDisplay}>
            <Text style={styles.dnaIcon}>✅</Text>
            <Text style={styles.dnaLabel}>Device DNA</Text>
            <Text style={styles.dnaHash}>
              {fingerprint ? fingerprint.substring(0, 32) + '...' : ''}
            </Text>
          </View>
        ) : (
          <View style={styles.cameraPlaceholder}>
            <Text style={styles.placeholderText}>Camera unavailable</Text>
          </View>
        )}
      </View>

      <View style={styles.progressSection}>
        <View style={styles.progressBar}>
          <Animated.View
            style={[
              styles.progressFill,
              {width: progressWidth},
              status === 'complete' && styles.progressComplete,
            ]}
          />
        </View>
        <Text style={styles.progressText}>
          {status === 'capturing'
            ? `Capturing sensor noise: ${frameCount}/${REQUIRED_FRAMES} frames`
            : status === 'finalizing'
            ? 'Extracting PRNU pattern...'
            : status === 'complete'
            ? 'Device fingerprint generated!'
            : status === 'error'
            ? 'Failed — tap retry'
            : 'Waiting for camera...'}
        </Text>
      </View>

      <View style={styles.infoSection}>
        <Text style={styles.infoTitle}>What is Device DNA?</Text>
        <Text style={styles.infoText}>
          Every camera sensor has a unique noise pattern called PRNU
          (Photo-Response Non-Uniformity). BioVault extracts this pattern to
          create an unforgeable fingerprint that binds every capture to YOUR
          specific device.
        </Text>
        <Text style={styles.infoDetail}>
          📸 50 frames → noise extraction → Wiener filter → BLAKE3 hash
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f0f23',
    padding: 20,
  },
  header: {
    alignItems: 'center',
    marginTop: 30,
    marginBottom: 16,
  },
  emoji: {
    fontSize: 40,
    marginBottom: 8,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 6,
  },
  subtitle: {
    fontSize: 14,
    color: '#8b8ba7',
    textAlign: 'center',
  },
  cameraContainer: {
    height: 260,
    borderRadius: 16,
    overflow: 'hidden',
    backgroundColor: '#1a1a3e',
    marginBottom: 20,
  },
  camera: {
    flex: 1,
  },
  cameraPlaceholder: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  placeholderText: {
    color: '#555',
    fontSize: 14,
  },
  dnaDisplay: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#0a2a1a',
  },
  dnaIcon: {
    fontSize: 48,
    marginBottom: 12,
  },
  dnaLabel: {
    fontSize: 18,
    fontWeight: '600',
    color: '#00ff88',
    marginBottom: 8,
  },
  dnaHash: {
    fontSize: 12,
    color: '#6366f1',
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    paddingHorizontal: 20,
    textAlign: 'center',
  },
  progressSection: {
    marginBottom: 24,
  },
  progressBar: {
    height: 8,
    backgroundColor: '#1a1a3e',
    borderRadius: 4,
    overflow: 'hidden',
    marginBottom: 8,
  },
  progressFill: {
    height: '100%',
    backgroundColor: '#6366f1',
    borderRadius: 4,
  },
  progressComplete: {
    backgroundColor: '#00ff88',
  },
  progressText: {
    color: '#8b8ba7',
    fontSize: 13,
    textAlign: 'center',
  },
  infoSection: {
    backgroundColor: '#1a1a3e',
    borderRadius: 12,
    padding: 16,
  },
  infoTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 8,
  },
  infoText: {
    fontSize: 13,
    color: '#8b8ba7',
    lineHeight: 20,
    marginBottom: 8,
  },
  infoDetail: {
    fontSize: 12,
    color: '#6366f1',
    fontStyle: 'italic',
  },
});
