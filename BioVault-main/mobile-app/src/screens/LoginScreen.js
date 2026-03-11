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

const USER_PROFILE_KEY = 'biovault_user_profile';
const CALIBRATION_KEY = 'biovault_prnu_calibrated';

export default function LoginScreen({navigation}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [autoChecking, setAutoChecking] = useState(true);

  // Auto-login: check local profile
  useEffect(() => {
    console.log('[BioVault] LoginScreen mounted');
    (async () => {
      try {
        const profile = await AsyncStorage.getItem(USER_PROFILE_KEY);
        console.log('[BioVault] LoginScreen auto-check profile:', profile ? 'FOUND' : 'NONE');
        if (profile) {
          // Check if PRNU calibration is done
          const calib = await AsyncStorage.getItem(CALIBRATION_KEY);
          if (!calib) {
            console.log('[BioVault] PRNU not calibrated — navigating to Calibration');
            navigation.replace('Calibration');
            return;
          }
          navigation.replace('Home');
          return;
        }
      } catch (e) {
        console.log('[BioVault] LoginScreen auto-check error:', e.message);
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
        <ActivityIndicator size="large" color="#6366f1" />
        <Text style={styles.checkingText}>Loading BioVault...</Text>
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
          <Text style={styles.logo}>🔐</Text>
          <Text style={styles.title}>BioVault</Text>
          <Text style={styles.subtitle}>Proof of Human Capture</Text>
          <Text style={styles.tagline}>
            Cryptographic device binding + biological signals
          </Text>
        </View>

        <View style={styles.form}>
          <Text style={styles.inputLabel}>Your Name / Email</Text>
          <TextInput
            style={styles.input}
            placeholder="Enter your name"
            placeholderTextColor="#555"
            autoCapitalize="none"
            value={email}
            onChangeText={setEmail}
          />

          <TouchableOpacity
            style={[styles.button, loading && styles.buttonDisabled]}
            onPress={handleLogin}
            disabled={loading}>
            {loading ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.buttonText}>Get Started</Text>
            )}
          </TouchableOpacity>
        </View>

        <View style={styles.features}>
          <Text style={styles.featureItem}>🔬 Device DNA — PRNU camera fingerprinting</Text>
          <Text style={styles.featureItem}>💚 Bio-Signal — rPPG heartbeat extraction</Text>
          <Text style={styles.featureItem}>🔗 Content Hash — SHA-256 tamper-proof</Text>
          <Text style={styles.featureItem}>🇮🇳 IT Rules 2026 Compliant</Text>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0f0f23',
  },
  center: {
    justifyContent: 'center',
    alignItems: 'center',
  },
  checkingText: {
    color: '#8b8ba7',
    marginTop: 16,
    fontSize: 14,
  },
  scrollContent: {
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: 24,
    paddingVertical: 40,
  },
  header: {
    alignItems: 'center',
    marginBottom: 40,
  },
  logo: {
    fontSize: 56,
    marginBottom: 12,
  },
  title: {
    fontSize: 32,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 16,
    color: '#00ff88',
    fontWeight: '600',
    marginBottom: 8,
  },
  tagline: {
    fontSize: 13,
    color: '#8b8ba7',
    textAlign: 'center',
  },
  form: {
    width: '100%',
  },
  inputLabel: {
    color: '#8b8ba7',
    fontSize: 13,
    marginBottom: 6,
    marginLeft: 4,
  },
  input: {
    backgroundColor: '#1a1a3e',
    borderWidth: 1,
    borderColor: '#2d2d5f',
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
    color: '#ffffff',
    fontSize: 16,
    marginBottom: 16,
  },
  button: {
    backgroundColor: '#6366f1',
    borderRadius: 12,
    paddingVertical: 16,
    alignItems: 'center',
    marginTop: 8,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonText: {
    color: '#ffffff',
    fontSize: 17,
    fontWeight: '600',
  },
  features: {
    marginTop: 40,
    paddingTop: 20,
    borderTopWidth: 1,
    borderTopColor: '#2d2d5f',
  },
  featureItem: {
    color: '#8b8ba7',
    fontSize: 13,
    marginBottom: 10,
    lineHeight: 18,
  },
});
