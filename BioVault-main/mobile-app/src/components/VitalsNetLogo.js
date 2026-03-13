import React from 'react';
import {View, Text, StyleSheet} from 'react-native';

/**
 * VitalsNet brand mark — "V" in accent green, "N" in white,
 * inside a rounded border box with a subtle green bottom accent.
 */
const VitalsNetLogo = ({size = 'large'}) => {
  const isSmall = size === 'small';

  return (
    <View style={[styles.box, isSmall && styles.boxSmall]}>
      <View style={styles.letterRow}>
        <Text style={[styles.letterV, isSmall && styles.letterSmall]}>V</Text>
        <Text style={[styles.letterN, isSmall && styles.letterSmall]}>N</Text>
      </View>
      <View style={[styles.accentBar, isSmall && styles.accentBarSmall]} />
    </View>
  );
};

const styles = StyleSheet.create({
  box: {
    width: 56,
    height: 56,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: '#3f3f46',
    backgroundColor: '#18181b',
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  boxSmall: {
    width: 36,
    height: 36,
    borderRadius: 8,
    borderWidth: 1,
  },
  letterRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  letterV: {
    fontSize: 22,
    fontWeight: '800',
    color: '#22c55e',
    letterSpacing: 2,
  },
  letterN: {
    fontSize: 22,
    fontWeight: '800',
    color: '#fafafa',
    letterSpacing: 2,
  },
  letterSmall: {
    fontSize: 14,
    letterSpacing: 1,
  },
  accentBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    height: 3,
    backgroundColor: '#22c55e',
    opacity: 0.7,
  },
  accentBarSmall: {
    height: 2,
  },
});

export default VitalsNetLogo;
