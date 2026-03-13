/**
 * VitalsNet Firebase Service
 * Uses Firestore REST API for cross-device capture verification.
 * No native Firebase SDK needed — works via HTTP.
 *
 * Configure FIREBASE_CONFIG below with your project's values.
 */

const FIREBASE_CONFIG = {
  projectId: 'vitalsnet-1133e',
  apiKey: 'AIzaSyAAY1UJ7jVd9FEKJWly0hbUxzp0pcpAvtA',
};

const FIRESTORE_BASE = `https://firestore.googleapis.com/v1/projects/${FIREBASE_CONFIG.projectId}/databases/(default)/documents`;

/**
 * Upload a capture record to Firestore.
 * Collection: "captures"
 * Document ID: captureId
 */
export async function uploadCapture(captureData) {
  const {
    captureId,
    bpm,
    confidence,
    riskScore,
    videoHash,
    hardwareDNA,
    watermarkPresent,
    consentHash,
    contentCategory,
    deviceModel,
    timestamp,
  } = captureData;

  const docUrl = `${FIRESTORE_BASE}/captures/${encodeURIComponent(captureId)}?key=${FIREBASE_CONFIG.apiKey}`;

  const firestoreDoc = {
    fields: {
      captureId: { stringValue: captureId || '' },
      bpm: { integerValue: String(bpm || 0) },
      confidence: { doubleValue: confidence || 0 },
      riskScore: { doubleValue: riskScore || 0 },
      videoHash: { stringValue: videoHash || '' },
      hardwareDNA: { stringValue: (hardwareDNA || '').substring(0, 32) },
      watermarkPresent: { booleanValue: !!watermarkPresent },
      consentHash: { stringValue: (consentHash || '').substring(0, 32) },
      contentCategory: { stringValue: contentCategory || 'SAFE' },
      deviceModel: { stringValue: deviceModel || 'unknown' },
      timestamp: { timestampValue: new Date(timestamp || Date.now()).toISOString() },
    },
  };

  const response = await fetch(docUrl, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(firestoreDoc),
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`Firestore upload failed (${response.status}): ${err}`);
  }

  console.log('[VitalsNet] Capture uploaded to Firebase:', captureId);
  return true;
}

/**
 * Look up a capture by its ID from any device.
 * Returns parsed capture data or null if not found.
 */
export async function lookupCapture(captureId) {
  const docUrl = `${FIRESTORE_BASE}/captures/${encodeURIComponent(captureId)}?key=${FIREBASE_CONFIG.apiKey}`;

  const response = await fetch(docUrl, {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' },
  });

  if (response.status === 404) return null;

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`Firestore lookup failed (${response.status}): ${err}`);
  }

  const doc = await response.json();
  return parseFirestoreDoc(doc);
}

/**
 * Query recent captures (last N).
 */
export async function queryRecentCaptures(limit = 20) {
  const queryUrl = `https://firestore.googleapis.com/v1/projects/${FIREBASE_CONFIG.projectId}/databases/(default)/documents:runQuery?key=${FIREBASE_CONFIG.apiKey}`;

  const query = {
    structuredQuery: {
      from: [{ collectionId: 'captures' }],
      orderBy: [{ field: { fieldPath: 'timestamp' }, direction: 'DESCENDING' }],
      limit: limit,
    },
  };

  const response = await fetch(queryUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(query),
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`Firestore query failed (${response.status}): ${err}`);
  }

  const results = await response.json();
  return results
    .filter(r => r.document)
    .map(r => parseFirestoreDoc(r.document));
}

function parseFirestoreDoc(doc) {
  if (!doc || !doc.fields) return null;
  const f = doc.fields;
  return {
    captureId: f.captureId?.stringValue || '',
    bpm: parseInt(f.bpm?.integerValue || '0', 10),
    confidence: f.confidence?.doubleValue || 0,
    riskScore: f.riskScore?.doubleValue || 0,
    videoHash: f.videoHash?.stringValue || '',
    hardwareDNA: f.hardwareDNA?.stringValue || '',
    watermarkPresent: f.watermarkPresent?.booleanValue || false,
    consentHash: f.consentHash?.stringValue || '',
    contentCategory: f.contentCategory?.stringValue || 'SAFE',
    deviceModel: f.deviceModel?.stringValue || 'unknown',
    timestamp: f.timestamp?.timestampValue || new Date().toISOString(),
  };
}

export function isConfigured() {
  return (
    FIREBASE_CONFIG.projectId !== 'YOUR_PROJECT_ID' &&
    FIREBASE_CONFIG.apiKey !== 'YOUR_WEB_API_KEY'
  );
}

// ============================================
// Privacy Shield — Alerts Collection
// ============================================

/**
 * Report an unauthorized capture (privacy violation).
 * Writes to "privacy_alerts" collection in Firestore.
 */
export async function reportPrivacyViolation(alertData) {
  const {
    subjectVitalsId,
    capturerDeviceId,
    timestamp,
    latitude,
    longitude,
    contentCategory,
  } = alertData;

  const alertId = `alert_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
  const docUrl = `${FIRESTORE_BASE}/privacy_alerts/${encodeURIComponent(alertId)}?key=${FIREBASE_CONFIG.apiKey}`;

  const firestoreDoc = {
    fields: {
      alertId: { stringValue: alertId },
      subjectVitalsId: { stringValue: subjectVitalsId || '' },
      capturerDeviceId: { stringValue: capturerDeviceId || '' },
      timestamp: { timestampValue: new Date(timestamp || Date.now()).toISOString() },
      latitude: { doubleValue: latitude || 0 },
      longitude: { doubleValue: longitude || 0 },
      contentCategory: { stringValue: contentCategory || 'UNKNOWN' },
      acknowledged: { booleanValue: false },
    },
  };

  const response = await fetch(docUrl, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(firestoreDoc),
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`Privacy alert upload failed (${response.status}): ${err}`);
  }

  console.log('[VitalsNet] Privacy violation reported:', alertId, 'subject:', subjectVitalsId);
  return alertId;
}

/**
 * Query privacy alerts for a specific VitalsID (subject side).
 * Returns recent alerts where this user was the unconsented subject.
 */
export async function queryPrivacyAlerts(vitalsId, limit = 20) {
  const queryUrl = `https://firestore.googleapis.com/v1/projects/${FIREBASE_CONFIG.projectId}/databases/(default)/documents:runQuery?key=${FIREBASE_CONFIG.apiKey}`;

  const query = {
    structuredQuery: {
      from: [{ collectionId: 'privacy_alerts' }],
      where: {
        fieldFilter: {
          field: { fieldPath: 'subjectVitalsId' },
          op: 'EQUAL',
          value: { stringValue: vitalsId },
        },
      },
      limit: limit,
    },
  };

  const response = await fetch(queryUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(query),
  });

  if (!response.ok) {
    const err = await response.text();
    throw new Error(`Privacy alerts query failed (${response.status}): ${err}`);
  }

  const results = await response.json();
  return results
    .filter(r => r.document)
    .map(r => parseAlertDoc(r.document))
    .sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
}

function parseAlertDoc(doc) {
  if (!doc || !doc.fields) return null;
  const f = doc.fields;
  return {
    alertId: f.alertId?.stringValue || '',
    subjectVitalsId: f.subjectVitalsId?.stringValue || '',
    capturerDeviceId: f.capturerDeviceId?.stringValue || '',
    timestamp: f.timestamp?.timestampValue || new Date().toISOString(),
    latitude: f.latitude?.doubleValue || 0,
    longitude: f.longitude?.doubleValue || 0,
    contentCategory: f.contentCategory?.stringValue || 'UNKNOWN',
    acknowledged: f.acknowledged?.booleanValue || false,
  };
}
