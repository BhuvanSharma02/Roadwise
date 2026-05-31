# RoadWise — Road Condition Monitoring for Android

A smartphone-based pothole and speed bump detector. Uses the phone's accelerometer and an on-device ONNX model to detect road hazards, tag them with GPS coordinates, and sync to Firebase.

No camera. No extra hardware.

---

## What it does

- Detects potholes and speed bumps in real time using the accelerometer
- Classifies events using on-device FFT + ONNX inference (no internet required for detection)
- Tags each event with GPS location and a Low/Medium/High severity score
- Syncs data to Firebase Firestore so multiple users share the same hazard map
- Shows a live heatmap and A–F road quality grid on an OpenStreetMap view
- Plans routes that avoid pothole-heavy areas via OpenRouteService
- Runs as a foreground service and auto-starts when it detects you're in a vehicle

---

## Problem

India has ~9,438 pothole deaths in 2020–2024 (MoRTH data). Most road monitoring still relies on citizen complaint portals — slow, unstructured, hard to act on. RoadWise automates this by passively collecting data as people drive.

---

## Tech Stack

| What | How |
|------|-----|
| Language | Kotlin, Android SDK (API 24+) |
| ML Inference | ONNX Runtime for Android 1.26.0 |
| Signal Processing | JTransforms 3.2 (FFT) |
| Maps | osmdroid 6.1.20 (OpenStreetMap) |
| Routing | OpenRouteService v2 API |
| Geocoding | Photon by Komoot |
| Backend | Firebase Firestore + Firebase Auth |
| Networking | Retrofit 3.0 + OkHttp |
| Location | Fused Location Provider |
| Activity Detection | Google Activity Recognition API |

---

## How Detection Works

1. `BumpDetector` reads `TYPE_LINEAR_ACCELERATION` at 20 Hz (50ms interval), plus `TYPE_GRAVITY` for orientation-agnostic vertical projection
2. Signal is windowed into 40-sample chunks at 20 Hz (2 seconds), with 50% overlap
3. 12 time-domain statistical features extracted: Z mean, std, max, min, peak-to-peak, RMS, X std, Y std, energy, skewness, kurtosis, impact ratio
4. Features passed to the ONNX model (`RoadModelInference`) → classifies as Normal / Speed Breaker / Pothole with a confidence score; events below 70% confidence are dropped
5. At speeds under 8 km/h, detections are buffered until speed recovers (avoids false positives from slow rolling)
6. On confirmation: GPS coordinate captured, event saved locally and pushed to Firestore

---

## App Screens

| Screen | What it does |
|--------|-------------|
| Main | Live map, heatmap overlay, monitoring toggle, search and route to a destination |
| History | List of all your recorded detections with PDF export |
| Overview | Admin view — hotspot map with severity filters, batch-resolve areas |
| Route Analysis | Compare up to 3 routes, see pothole count and road quality score per route |
| Settings | Sensitivity, audio alerts, battery saver, background service toggle |
| Account | Profile, stats, sign out |
| Redeem | Reward points earned from distance driven (coming soon) |

---

## Setup

### Requirements

- Android Studio (Flamingo or later)
- Android device API 24+ (Android 7.0)
- Firebase project with Firestore and Auth enabled
- OpenRouteService API key — free at [openrouteservice.org](https://openrouteservice.org)
- `road_model.onnx` model file (contact the team)

### Steps

**1. Clone**
```bash
git clone https://github.com/BhuvanSharma02/Roadwise.git
cd Roadwise
```

**2. Firebase config**

Download `google-services.json` from Firebase Console and place it at `app/google-services.json`.

Enable:
- Authentication → Email/Password
- Firestore Database

**3. API key**

Create `local.properties` in the project root:
```properties
sdk.dir=C\:\\Users\\YourUser\\AppData\\Local\\Android\\Sdk
ORS_API_KEY=your_key_here
```

**4. Build**
```bash
./gradlew installDebug
```

Or open in Android Studio and hit Run.

---

## ML Model

The model runs entirely on-device using ONNX Runtime. It was trained on labelled accelerometer recordings collected while driving over known potholes and speed bumps.

Output classes:
- `0` — Normal road
- `1` — Speed breaker
- `2` — Pothole

Severity is determined separately from the RMS and peak amplitude values, not by the classifier.

> The model file (`road_model.onnx`) is included in this repository under `app/src/main/assets/`.

---

## Permissions

The app requests: `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`.

Camera access is not used or requested.

---

## Team

| Name | ID | Role |
|------|----|------|
| Bhuvan Sharma | 2022A1R003 | Android development, sensor integration |
| Simriti Kak | 2022A1R004 | ML model training, signal processing |
| Pratham Seth | 2022A1R037 | Backend, Firebase, routing |
| Asst. Prof. Saurabh Sharma | — | Supervisor |
