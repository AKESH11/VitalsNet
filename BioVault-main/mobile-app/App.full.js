import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createStackNavigator } from '@react-navigation/stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { Linking } from 'react-native';
import ErrorBoundary from './src/components/ErrorBoundary';
import LoginScreen from './src/screens/LoginScreen';
import HomeScreen from './src/screens/HomeScreen';
import CameraScreen from './src/screens/CameraScreen';
import ResultsScreen from './src/screens/ResultsScreen';
import VerifyScreen from './src/screens/VerifyScreen';
import CalibrationScreen from './src/screens/CalibrationScreen';
import PrivacyShieldScreen from './src/screens/PrivacyShieldScreen';

const Stack = createStackNavigator();

const linking = {
  prefixes: ['vitalsnet://', 'https://vitalsnet.io'],
  config: {
    screens: {
      Home: 'home',
      Camera: 'capture',
      Verify: 'verify/:captureId?',
    },
  },
  async getInitialURL() {
    return await Linking.getInitialURL();
  },
  subscribe(listener) {
    const subscription = Linking.addEventListener('url', ({ url }) => listener(url));
    return () => subscription.remove();
  },
};

export default function App() {
  return (
    <SafeAreaProvider>
      <ErrorBoundary>
        <NavigationContainer linking={linking}>
          <Stack.Navigator
            initialRouteName="Login"
            screenOptions={{
              headerShown: false,
              cardStyle: { backgroundColor: '#09090b' },
              gestureEnabled: true,
            }}>
            <Stack.Screen name="Login" component={LoginScreen} />
            <Stack.Screen name="Calibration" component={CalibrationScreen} />
            <Stack.Screen name="Home" component={HomeScreen} />
            <Stack.Screen name="Camera" component={CameraScreen} />
            <Stack.Screen name="Results" component={ResultsScreen} />
            <Stack.Screen name="Verify" component={VerifyScreen} />
            <Stack.Screen name="PrivacyShield" component={PrivacyShieldScreen} />
          </Stack.Navigator>
        </NavigationContainer>
      </ErrorBoundary>
    </SafeAreaProvider>
  );
}
