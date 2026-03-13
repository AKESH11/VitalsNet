import React, {useState, useEffect} from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ScrollView,
  Alert,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import VitalsNetLogo from '../components/VitalsNetLogo';

const USER_PROFILE_KEY = 'biovault_user_profile';
const CALIBRATION_KEY = 'biovault_prnu_calibrated';

export default function LoginScreen({navigation}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [autoChecking, setAutoChecking] = useState(true);

  // Auto-login: check local profile
  useEffect(() => {
    console.log('[VitalsNet] LoginScreen mounted');
    (async () => {
      try {
        const profile = await AsyncStorage.getItem(USER_PROFILE_KEY);
        console.log('[VitalsNet] LoginScreen auto-check profile:', profile ? 'FOUND' : 'NONE');
        if (profile) {
          // Check if PRNU calibration is done
          const calib = await AsyncStorage.getItem(CALIBRATION_KEY);
          if (!calib) {
            console.log('[VitalsNet] PRNU not calibrated — navigating to Calibration');
            navigation.replace('Calibration');
            return;
          }
          navigation.replace('Home');
          return;
        }
      } catch (e) {
        console.log('[VitalsNet] LoginScreen auto-check error:', e.message);
      }
      setAutoChecking(false);
    })();
  }, []);

  const handleLogin = async () => {
    if (!email.trim()) {
      Alert.alert('Missing Field', 'Please enter your name or email.');
      return;
    }

    setLoading(true);
    try {
      await AsyncStorage.setItem(
        USER_PROFILE_KEY,
        JSON.stringify({email: email.trim(), lastLogin: Date.now()}),
      );
      // New users always go to calibration first
      navigation.replace('Calibration');
    } catch (e) {
      Alert.alert('Error', 'Failed to save profile.');
    } finally {
      setLoading(false);
    }
  };

  if (autoChecking) {
    return (
      <View style={[styles.container, styles.center]}>
        <ActivityIndicator size="large" color="#52525b" />
        <Text style={styles.checkingText}>Loading...</Text>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <VitalsNetLogo />
          <Text style={styles.title}>VitalsNet</Text>
          <Text style={styles.subtitle}>Proof of Human Capture</Text>
        </View>

        <View style={styles.form}>
          <Text style={styles.inputLabel}>Your Name / Email</Text>
          <TextInput
            style={styles.input}
            placeholder="Enter your name"
            placeholderTextColor="#52525b"
            autoCapitalize="none"
            value={email}
            onChangeText={setEmail}
          />

          <TouchableOpacity
            style={[styles.button, loading && styles.buttonDisabled]}
            onPress={handleLogin}
            disabled={loading}>
            {loading ? (
              <ActivityIndicator color="#09090b" />
            ) : (
              <Text style={styles.buttonText}>Get Started</Text>
            )}
          </TouchableOpacity>
        </View>

        <View style={styles.features}>
          <Text style={styles.featureItem}>Device DNA — PRNU fingerprinting</Text>
          <Text style={styles.featureItem}>Bio-Signal — rPPG heartbeat</Text>
          <Text style={styles.featureItem}>Content Hash — SHA-256</Text>
          <Text style={styles.featureItem}>IT Rules 2026 Compliant</Text>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#09090b',
  },
  center: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  checkingText: {
    color: '#71717a',
    marginTop: 16,
    fontSize: 14,
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: 24,
    paddingVertical: 48,
  },
  header: {
    alignItems: 'center',
    marginBottom: 48,
  },
  logoMark: {
    fontSize: 24,
    fontWeight: '800',
    color: '#fafafa',
    letterSpacing: 8,
    marginBottom: 20,
    borderWidth: 1.5,
    borderColor: '#fafafa',
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: 8,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: '#fafafa',
    marginTop: 16,
    marginBottom: 4,
    letterSpacing: 1,
  },
  subtitle: {
    fontSize: 14,
    color: '#71717a',
    fontWeight: '500',
  },
  form: {
    width: '100%',
  },
  inputLabel: {
    color: '#a1a1aa',
    fontSize: 13,
    fontWeight: '500',
    marginBottom: 8,
    marginLeft: 2,
  },
  input: {
    backgroundColor: '#18181b',
    borderWidth: 1,
    borderColor: '#27272a',
    borderRadius: 10,
    paddingHorizontal: 16,
    paddingVertical: 14,
    color: '#fafafa',
    fontSize: 16,
    marginBottom: 16,
  },
  button: {
    backgroundColor: '#fafafa',
    borderRadius: 10,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 8,
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  buttonText: {
    color: '#09090b',
    fontSize: 16,
    fontWeight: '600',
  },
  features: {
    marginTop: 48,
    paddingTop: 20,
    borderTopWidth: 1,
    borderTopColor: '#27272a',
  },
  featureItem: {
    color: '#52525b',
    fontSize: 13,
    marginBottom: 10,
    lineHeight: 18,
    textAlign: 'center',
  },
});
