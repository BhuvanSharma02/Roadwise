# RoadWise — Technical Documentation

**Version:** 1.0 | **Platform:** Android (Native Kotlin) | **Min SDK:** 24 (Android 7.0) | **Target SDK:** 36

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Technology Stack](#2-technology-stack)
3. [Application Architecture](#3-application-architecture)
4. [Module Breakdown](#4-module-breakdown)
5. [Core Data Model](#5-core-data-model)
6. [Detection Pipeline](#6-detection-pipeline)
7. [Map Visualization System](#7-map-visualization-system)
8. [Navigation & Routing](#8-navigation--routing)
9. [Data Persistence](#9-data-persistence)
10. [Settings & Configuration](#10-settings--configuration)
11. [UI / Design System](#11-ui--design-system)
12. [Permissions](#12-permissions)
13. [Build System](#13-build-system)

---

## 1. Project Overview

RoadWise is a native Android application that **detects, records, maps, and helps navigate around road hazards** in real-time. The app uses a **sensor-only approach** — the smartphone's accelerometer combined with on-device ONNX model inference — to classify road events without any camera input.

This design makes RoadWise lightweight, privacy-preserving, and capable of working in all lighting conditions, including night and tunnels.

### Key Goals
| Goal | Solution |
|------|----------|
| Detect potholes/speed bumps without manual input | Accelerometer + FFT + ONNX on-device inference |
| Prevent false positives (low-speed bumps, speed transitions) | Speed-buffered verification in DetectionManager |
| Understand road quality at scale | A–F Grading Engine on 100m grid cells (AdaptiveRoadOverlay) |
| Navigate with hazard awareness | OpenRouteService with pothole avoidance polygons |
| Compare multiple route options | RouteAnalysisActivity with multi-route risk scoring |
| Test system without driving | Built-in Simulator (long-press map) |
| Sync data across users | Firebase Firestore with offline-first local cache |

---

## 2. Technology Stack

| Category | Component | Version |
|---|---|---|
| Language | Kotlin | — |
| Build | Gradle AGP | 8.x |
| SDK | Min 24 / Target 36 / Compile 36 | — |
| ML Inference | ONNX Runtime for Android | 1.26.0 |
| Signal Processing | JTransforms (FFT) | 3.2 |
| Maps | osmdroid | 6.1.20 |
| Location | Fused Location Provider (play-services-location) | 21.3.0 |
| Routing API | OpenRouteService v2 | — |
| Geocoding | Photon by Komoot | — |
| Networking | Retrofit + OkHttp | 3.0.0 / 5.x |
| JSON | Gson + Retrofit Converter | 2.14.0 |
| Cloud DB | Firebase Firestore | BOM 34.x |
| Auth | Firebase Auth | BOM 34.x |
| Local Storage | SharedPreferences + Gson | — |
| Async | Kotlin Coroutines + lifecycleScope | 1.11.0 |
| UI | Material Components 3 | 1.14.0 |

---

## 3. Application Architecture

RoadWise follows a **modular, component-oriented** structure. Business logic is distributed across dedicated components that communicate via callbacks and coroutines.

```
RoadWise App
|-- SplashActivity         <- Animated launch screen
|-- LoginActivity          <- Firebase Auth (email/password)
|-- MainActivity           <- Central orchestrator (map, detection, routing, HUD)
|-- OverviewActivity       <- Admin hotspot dashboard with heatmap & filters
|-- HistoryActivity        <- Drive history log + PDF export
|-- RouteAnalysisActivity  <- Multi-route comparison and risk scoring
|-- SettingsActivity       <- All user preferences
|-- AccountActivity        <- User profile and sign-out
|-- RedeemActivity         <- Reward points & redemption screen
|
|-- mapping/
|   |-- HeatmapOverlay       <- Custom osmdroid overlay (radial glow per cluster)
|   |-- AdaptiveRoadOverlay  <- Zoom-aware: A–F grid at low zoom, blobs at high zoom
|
|-- sensors/
|   |-- BumpDetector         <- Accelerometer state machine + FFT + ONNX inference
|
|-- services/
|   |-- DriveGuardService    <- Foreground service for background sensing
|   |-- DrivingReceiver      <- Activity Recognition broadcast receiver
|   |-- BootReceiver         <- Re-registers transitions on device boot
|
|-- routing/
|   |-- RoutingManager       <- Facade for ORS + Photon APIs
|   |-- OpenRouteServiceApi  <- Retrofit interface for ORS
|   |-- PhotonApi            <- Retrofit interface for geocoding
|   |-- RoutingModels        <- All request/response data classes
|   |-- BoundingBoxUtils     <- Polygon helpers for avoidance zones
|
|-- models/
|   |-- PotholeData          <- Core detection data class
|
|-- utils/
    |-- DetectionManager     <- Speed-buffered verification (fires/buffers sensor events)
    |-- PotholeRepository    <- Singleton data store (SharedPrefs + Gson + Firestore)
    |-- RoadQualityScorer    <- Grid bucketing and A–F grading engine
    |-- SessionManager       <- Login state, admin role, GPS distance + reward points
    |-- SafetyAlertManager   <- Proximity hazard alerts and in-app notifications
    |-- ActivityTransitionHelper <- Activity Recognition API registration
    |-- PotholeAdapter       <- RecyclerView Adapter for history list
```

---

## 4. Module Breakdown

### 4.1 MainActivity
The central controller of the app. Responsible for:
- Wiring all components together
- Managing BumpDetector lifecycle (start/stop on toggle)
- Coordinating DetectionManager callback with map and repository updates
- Real-time speed reading from FusedLocationProvider
- Accumulating drive distance per second in SessionManager
- Map gestures: single-tap for destination, long-press for simulator
- Multi-route fetch → RouteAnalysisActivity → navigation mode
- Zoom-aware toggle of the AdaptiveRoadOverlay and legend
- Role-based nav bar (admin sees Overview tab)

### 4.2 HistoryActivity
- Displays a scrollable RecyclerView of all recorded detections (filtered by role).
- Each card shows: type badge, intensity bar, GPS coordinates, timestamp.
- Supports **PDF Export** of the detection list to device cache, shared via FileProvider.
- Supports delete of individual records.
- Admin users see all detections; standard users see only their own.

### 4.3 OverviewActivity
- Admin-only hotspot map with `HeatmapOverlay`.
- Severity filter chips (All / Critical / Moderate).
- Hotspot list sheet with road addresses (reverse geocoded via Android Geocoder).
- Resolve hotspot action: batch-deletes all pothole records in an area from Firestore.
- CSV export of all data, shared via FileProvider.

### 4.4 RouteAnalysisActivity
- Receives up to 3 route candidates from MainActivity.
- Displays distance, estimated travel time (formatted as hours + minutes for long routes), pothole count, and bump count per route.
- User selects a route → result returned to MainActivity → navigation mode starts.

### 4.5 SettingsActivity
Manages all preferences in `roadwise_prefs` SharedPreferences via custom XML-binding UI (not PreferenceFragmentCompat).

---

## 5. Core Data Model

### PotholeData
Every detection is stored as a `PotholeData` instance.

```kotlin
data class PotholeData(
    val location: GeoPoint,        // GPS coordinate of the hazard
    val type: RoadFeature,         // POTHOLE or SPEED_BUMP
    val intensity: Float,          // G-force magnitude (m/s²)
    val severity: Severity,        // LOW, MEDIUM, or HIGH
    val timestamp: Long,           // Unix epoch in milliseconds
    val createdByEmail: String     // Firebase Auth email of the reporting user
)
```

### RoadFeature (Enum)
```kotlin
enum class RoadFeature { POTHOLE, SPEED_BUMP, UNKNOWN }
```

### Severity (Enum)
```kotlin
enum class Severity { LOW, MEDIUM, HIGH }
```

### RoadSegment
Used internally by the scoring engine and AdaptiveRoadOverlay.

```kotlin
data class RoadSegment(
    val segmentId: String,
    val boundingBox: BoundingBox,   // ~100m x 100m grid cell
    val potholes: List<PotholeData>,
    val grade: RoadGrade,           // A, B, C, D, F
    val score: Float                // 0.0 (F) to 100.0 (A)
)
```

---

## 6. Detection Pipeline

Detection uses a **sensor-only pipeline** for on-device road event classification.

### Stage 1 — Signal Acquisition (BumpDetector)

```
Accelerometer Z-axis (100Hz) → Low-pass Filter → 256-sample Window (50% overlap)
```

- `BumpDetector` registers a `SensorEventListener` for `TYPE_ACCELEROMETER`.
- Raw Z-axis data is collected into a rolling window of 256 samples.
- A **state machine** detects initial threshold crossings (configurable via `pref_sensor_threshold`).

### Stage 2 — Spectral Feature Extraction & ONNX Inference

```
Windowed Signal → FFT (JTransforms) → 8 Features → ONNX Runtime → Classification
```

The `DetectionManager` (via ONNX Runtime) classifies each window using 8 features:

| Feature | Description |
|---------|-------------|
| Peak Frequency | Dominant FFT bin (Hz) |
| Spectral Energy | Total power across spectrum |
| Energy Distribution | Ratio across low / mid / high bands |
| Spectral Variance | Spread of energy across frequency bins |
| Peak Amplitude | Max instantaneous acceleration value |
| RMS | Effective signal magnitude |
| Kurtosis | Statistical sharpness; high for impulse events |
| Zero Crossing Rate | Differentiates smooth vs abrupt waveforms |

**Output classes:** `0` = Normal Road, `1` = Speed Breaker, `2` = Pothole

### Stage 3 — Speed-Buffered Verification (DetectionManager)

`DetectionManager` prevents false positives from slow-speed events (e.g. speed bumps rolled over slowly):

1. If current speed ≥ 8 km/h → event fires **immediately**
2. If current speed < 8 km/h → event is **buffered** in a pending list
3. If speed recovers to ≥ 12 km/h within 15 seconds → buffered events are committed
4. If no recovery within 15 seconds → buffered events are **discarded**
5. A **1000ms lockout** after each event prevents rebound duplicate detections

### Stage 4 — Persistence & Map Update

1. `PotholeRepository.savePothole()` JSON-serializes and writes to SharedPreferences
2. `PotholeRepository` emits an update via `StateFlow` — all subscribed screens refresh
3. `addHeatmapPoint()` places a colored pin marker on the osmdroid map
4. `adaptiveOverlay.refresh()` recomputes the road quality grid scores
5. Dashboard counters update; `SafetyAlertManager` checks proximity to new hazard

---

## 7. Map Visualization System

### 7.1 AdaptiveRoadOverlay
A custom osmdroid `Overlay` rendering two distinct visualizations based on zoom level:

**Zoom Level < 15 — Segment Grade View**
- Detections bucketed into ~100m x 100m grid (~0.0009° per cell)
- Each cell scored and graded A–F by `RoadQualityScorer`
- Grid drawn as translucent colored rectangles with grade letter label
- A floating legend widget shows/hides alongside this view

**Zoom Level ≥ 15 — Heatmap Blob View**
- Each `PotholeData` rendered as a radial gradient circle
- Gold blobs = Potholes, Teal blobs = Speed Bumps
- Blob radius scales with `intensity`; glow opacity animates on zoom transition

### 7.2 HeatmapOverlay
Used in `OverviewActivity`. Groups detections into clusters and renders a heatmap.
Critical clusters (grade D or F) receive interactive `Marker` overlays with reverse-geocoded addresses.

### 7.3 Road Quality Grading Engine (RoadQualityScorer)
| Score Range | Grade | Color | Label |
|---|---|---|---|
| 80–100 | A | Green (#2ECC71) | Excellent |
| 60–79 | B | Light Green (#82E0AA) | Good |
| 40–59 | C | Amber (#F4D03F) | Fair |
| 20–39 | D | Orange (#E67E22) | Poor |
| 0–19 | F | Red (#E74C3C) | Critical |

**Scoring Penalty per Detection:**
| Intensity (m/s²) | Penalty |
|---|---|
| ≥ 2.5 | −35 points (Critical) |
| ≥ 1.5 | −20 points (Severe) |
| ≥ 0.8 | −10 points (Moderate) |
| < 0.8 | −4 points (Minor) |

### 7.4 Route Polylines
- **Active Route** — Cyan (#00E0FF), 22dp stroke, Cap.ROUND
- Routes drawn on MainActivity map during navigation mode

---

## 8. Navigation & Routing

### 8.1 Geocoding — Photon API
- Fuzzy place search in the destination search bar
- Requests debounced by 500ms (Kotlin Job cancel/relaunch)
- Searches constrained to India bounding box (68.1, 6.7, 97.4, 35.5)
- Results displayed in dropdown adapter

### 8.2 Smart Routing — OpenRouteService API
- Uses ORS v2 Directions POST endpoint (`/v2/directions/driving-car/geojson`)
- Fetches up to 3 alternative route candidates

**Hazard Avoidance Logic:**
1. All `PotholeData` records with `intensity > 0.8` are considered significant
2. Each hazard is enclosed in a 20-meter polygon via `BoundingBoxUtils`
3. Polygons sent as GeoJSON `MultiPolygon` in `options.avoid_polygons`
4. ORS calculates paths avoiding those zones

**Map Interaction:**
- **Single-Tap** on map: Sets destination → triggers routing → opens `RouteAnalysisActivity`
- **Long-Press** on map: Opens Simulator dialog

### 8.3 Road Quality Analytics (Route Level)
```
density = total_hazards / route_length_km
```
| Density | Label |
|---|---|
| < 0.5 | EXCELLENT |
| < 1.5 | GREAT |
| < 3.0 | GOOD |
| < 5.0 | FAIR |
| ≥ 5.0 | HAZARDOUS |

### 8.4 Simulation Tool
Accessible via **long-press** on the map. Opens a two-step dialog:
1. Select hazard type: **Pothole** or **Speed Bump**
2. Select severity: **Minor** (0.8g) / **Moderate** (1.5g) / **Severe** (2.5g) / **Critical** (3.5g)

The hazard is saved to the repository, appears immediately on the heatmap, and is factored into rerouting.

---

## 9. Data Persistence

### PotholeRepository
A singleton `object` managing all I/O operations.

- **Local Storage**: SharedPreferences, key `pothole_prefs` / `potholes`
- **Serialization**: Gson with custom JsonSerializer/JsonDeserializer for `GeoPoint`
- **In-Memory Cache**: `cached` field reduces repeated deserialization calls
- **Cloud Storage**: Firebase Firestore, collection `road_events`
- **Offline Support**: Detections saved locally when not logged in; synced to Firestore on next login via `syncAfterLogin()`

**CRUD Operations:**
| Method | Description |
|---|---|
| `getAllPotholes(ctx)` | Returns all records (from cache or disk) |
| `savePothole(ctx, data)` | Appends one record and writes to disk + Firestore |
| `deletePothole(ctx, timestamp)` | Removes record by timestamp key |
| `fetchFromCloud(ctx, cb)` | Pulls Firestore records and merges with local |
| `resolveHotspot(ctx, list, cb)` | Batch-deletes a cluster from Firestore + local |
| `clearAll(ctx)` | Wipes all JSON data from SharedPreferences |
| `saveAllInternal(ctx, list)` | Overwrites entire local store |

---

## 10. Settings & Configuration

All settings stored in `roadwise_prefs` SharedPreferences.

| Preference Key | Type | Default | Effect |
|---|---|---|---|
| `pref_battery_saver` | Boolean | false | Reduces GPS/sensing polling frequency |
| `pref_sensor_threshold` | Float | 1.2 | Minimum G-force to trigger BumpDetector |
| `pref_sensitivity_index` | Float | 1.0 | Slider index (0=Reactive, 1=Balanced, 2=Proactive) |
| `pref_audio_alerts` | Float | 65.0 | Alert volume as a 0–100 percentage |
| `pref_voice_alerts` | Boolean | true | Enables/disables proximity hazard alerts |
| `pref_background_detection` | Boolean | true | Enables BumpDetector in background |
| `pref_background_service` | Boolean | false | Controls DriveGuardService foreground service |
| `pref_auto_start` | Boolean | false | Registers Activity Recognition transitions |
| `pref_monitoring_enabled` | Boolean | true | Main toggle for active detection |

**Data Management (from Settings):**
- **Storage Usage**: Displays count of detection records and total JSON storage size
- **Clear History**: Calls `PotholeRepository.clearAll()` to delete all records

---

## 11. UI / Design System

Dark theme with glassmorphism-style cards. Custom color tokens defined in `res/values/colors.xml`:

- `obsidian_background` — primary background
- `glass_surface` — semi-transparent dark for floating cards
- `glass_border` — card borders
- `emerald_neon` — primary accent (teal)
- `electric_gold` — secondary accent
- `cyber_blue` — tertiary accent

### Key UI Components

- **Bottom Navigation Pill** — floating `MaterialCardView` with Drive / History / Overview / Settings / Account tabs; Overview tab hidden for non-admin users
- **Search Bar** — `AutoCompleteTextView` with 500ms debounce, Photon geocoding
- **HUD Dashboard** — pothole count, current speed, max speed, monitoring status
- **Monitoring Toggle** — switch with pulsing alpha animation while sensing is active
- **AdaptiveRoadOverlay Legend** — shows A–F grade colors; only visible at zoom < 15
- **Navigation Panel** — slides in on route start, hides HUD and search bar

---

## 12. Permissions

| Permission | Reason |
|---|---|
| `ACCESS_FINE_LOCATION` | High-accuracy GPS for hazard geo-tagging and routing |
| `ACCESS_COARSE_LOCATION` | Fallback coarse location |
| `ACCESS_BACKGROUND_LOCATION` | GPS access while app is in background |
| `ACTIVITY_RECOGNITION` | Detect when user is in a vehicle to auto-start sensing |
| `INTERNET` | OSM tile download, Photon geocoding, ORS routing, Firebase sync |
| `RECEIVE_BOOT_COMPLETED` | Re-register Activity Recognition on device reboot |
| `FOREGROUND_SERVICE` | Run DriveGuardService as a foreground service |
| `FOREGROUND_SERVICE_LOCATION` | Location access from foreground service |
| `POST_NOTIFICATIONS` | Show detection and service notifications |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Request exclusion from Doze mode |

> `CAMERA` is **not required**. RoadWise uses accelerometer-only detection — no camera access is needed.

---

## 13. Build System

| Property | Value |
|---|---|
| Compile SDK | 36 |
| Target SDK | 36 |
| Min SDK | 24 |
| Java Compatibility | Java 11 |
| Build Variants | debug, release |
| BuildConfig Fields | `ORS_API_KEY` (injected from `local.properties`) |

### Key Build Commands
```bash
# Assemble a debug APK
./gradlew assembleDebug

# Install directly on a connected device
./gradlew installDebug

# Run lint checks
./gradlew lint

# Clean build artifacts
./gradlew clean
```

### Getting Started (Developer Setup)
1. Clone the repository
2. Add your Firebase config file: `app/google-services.json`
3. Add your OpenRouteService API key to `local.properties`:
   ```properties
   ORS_API_KEY=your_api_key_here
   ```
4. Place the ONNX model at `app/src/main/assets/road_model.onnx` (contact the team)
5. Run `./gradlew installDebug` to build and install on a connected Android device
