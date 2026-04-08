# RoadWise — Technical Documentation

**Version:** 1.0 | **Platform:** Android (Native Kotlin) | **Min SDK:** 24 (Android 7.0) | **Target SDK:** 34

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

RoadWise is a native Android application designed to **detect, record, map, and navigate around road hazards** in real-time. The app uses a dual-sensor approach combining a **machine learning computer vision model** via the rear camera with a **physical accelerometer** to achieve high-confidence, location-stamped hazard detection.

Detected hazards are visualized on a live map using two adaptive views that switch based on zoom level, allowing users to understand road quality at both a neighbourhood scale and a street-level scale.

### Key Goals
| Goal | Solution |
|------|----------|
| Detect potholes/speed bumps without manual input | ML + Sensor Fusion pipeline |
| Prevent false positives (rough roads, car noise) | Two-stage cross-correlation verification |
| Understand road quality at scale | A-F Grading Engine on 100m grid cells |
| Navigate safely away from hazards | OpenRouteService with Pothole Avoidance Polygons |
| Compare multiple route options | Multi-candidate alternate route rendering |
| Test system without driving | Built-in Map Simulation Tool |

---

## 2. Technology Stack

### Core Android
| Component | Library / Version |
|---|---|
| Language | Kotlin |
| Build System | Gradle (AGP 8.1.0 / Gradle 8.7) |
| Architecture | MVVM-inspired with Activity components |
| Minimum SDK | API 24 (Android 7.0 Nougat) |
| Target SDK | API 34 (Android 14) |
| Compile SDK | 34 |
| View Binding | Enabled |

### Computer Vision & AI
| Component | Library / Version |
|---|---|
| Camera Pipeline | CameraX 1.3.x (camera-core, camera-camera2, camera-lifecycle, camera-view) |
| ML Inference | Google ML Kit Object Detection with Custom Models (mlkit:object-detection-custom:17.0.1) |
| On-Device Model | Custom TFLite model `pothole_model.tflite` |

### Maps & Location
| Component | Library / Version |
|---|---|
| Map Rendering | osmdroid 6.1.18 |
| Map Tiles | OpenStreetMap (MAPNIK) |
| Location Provider | Android GpsMyLocationProvider via osmdroid |
| Overlay Drawing | Custom Overlay subclass (AdaptiveRoadOverlay) |

### Networking
| Component | Library / Version |
|---|---|
| HTTP Client | Retrofit 2.9.0 + OkHttp 4.11.0 |
| JSON Serialization | Gson 2.10.1 + Retrofit Converter |
| Geocoding API | Photon by Komoot (https://photon.komoot.io/) |
| Routing API | OpenRouteService v2 (https://api.openrouteservice.org/) |

### Persistence
| Component | Library / Version |
|---|---|
| Local Storage | Android SharedPreferences |
| Serialization | Gson (JSON) |
| Image Files | App scoped external storage (getExternalFilesDir) |

### Concurrency
| Component | Library / Version |
|---|---|
| Async Processing | Kotlin Coroutines (kotlinx-coroutines-android:1.7.3) |
| Lifecycle Safety | lifecycle-runtime-ktx:2.6.2 + lifecycleScope |

### UI
| Component | Library / Version |
|---|---|
| Material Components | MDC Android 1.10.0 |
| Preference Screen | androidx.preference:preference-ktx:1.2.1 |
| RecyclerView | Part of Material/AndroidX standard |

---

## 3. Application Architecture

RoadWise follows a **modular, component-oriented** structure. Business logic is distributed across dedicated components that communicate via callbacks and coroutines.

```
RoadWise App
|-- MainActivity         <- Central orchestrator (UI, map, camera, routing)
|-- HistoryActivity      <- Detection history browser + PDF export
|-- SettingsActivity     <- All user preferences
|
|-- camera/
|   |-- PotholeAnalyzer  <- CameraX ImageAnalysis.Analyzer (ML inference)
|   |-- GraphicOverlay   <- Canvas-based live bounding box renderer
|
|-- sensors/
|   |-- BumpDetector     <- Accelerometer state machine
|
|-- mapping/
|   |-- AdaptiveRoadOverlay <- Custom osmdroid Overlay for A-F grid & heatmap
|
|-- routing/
|   |-- RoutingManager   <- Facade for ORS + Photon APIs
|   |-- OpenRouteServiceApi <- Retrofit interface for ORS
|   |-- PhotonApi        <- Retrofit interface for geocoding
|   |-- RoutingModels    <- All request/response data classes
|   |-- BoundingBoxUtils <- Polygon helpers for avoidance zones
|
|-- models/
|   |-- PotholeData      <- Core detection data class
|
|-- utils/
    |-- DetectionManager  <- Cross-correlates camera + sensor events
    |-- PotholeRepository <- Singleton data store (SharedPrefs + Gson)
    |-- RoadQualityScorer <- Grid bucketing and A-F grading engine
    |-- ImageAnalyzer     <- Burst image sharpness analyser (Laplacian)
    |-- PotholeAdapter    <- RecyclerView Adapter for history list
```

---

## 4. Module Breakdown

### 4.1 MainActivity
The central controller of the app. Responsible for:
- Wiring all components together
- Managing the CameraX lifecycle
- Coordinating the DetectionManager callback with map and repository updates
- Handling map gestures (single-tap for destination, long-press for simulation)
- Multi-route fetch and polyline management
- Zoom-aware toggle of the road quality legend

### 4.2 HistoryActivity
- Displays a scrollable RecyclerView of all recorded detections.
- Each card shows: type badge, intensity bar, GPS coordinates, timestamp, and embedded thumbnail.
- Supports PDF Export of the entire session (detection list + photos) to device storage.
- Supports delete of individual records.

### 4.3 SettingsActivity
Uses PreferenceFragmentCompat to deliver a standard settings UI. All preferences are persisted to `roadwise_prefs` SharedPreferences.

---

## 5. Core Data Model

### PotholeData
Every detection is stored as a PotholeData instance.

```kotlin
data class PotholeData(
    val location: GeoPoint,       // GPS coordinate of the hazard
    val type: RoadFeature,        // POTHOLE, SPEED_BUMP, or UNKNOWN
    val intensity: Float,         // G-force delta magnitude (0.0 to 3.0+)
    val timestamp: Long,          // Unix epoch in milliseconds
    val imagePaths: List<String>  // Paths to captured JPG frames on disk
)
```

### RoadFeature (Enum)
```kotlin
enum class RoadFeature { POTHOLE, SPEED_BUMP, UNKNOWN }
```

### RoadSegment
Used internally by the scoring engine and the map overlay.

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

Detection follows a strict **two-stage verification pipeline** to eliminate false positives from road noise, car vibrations, and camera shake.

### Stage 1 — Visual Detection (PotholeAnalyzer)

```
Camera Frame -> ML Kit (TFLite) -> Label Filtering -> Burst Capture
```

- CameraX feeds frames to PotholeAnalyzer via `ImageAnalysis.Analyzer`.
- ML Kit Object Detection runs the custom `pothole_model.tflite` locally on-device (no internet required).
- Labels are filtered against a class list from `pothole_labels.txt`. Only results containing "pothole" (but not "no pothole") with confidence > 45% are accepted.
- On a positive detection, a **3-frame burst capture** is triggered.
- The ImageAnalyzer utility selects the sharpest frame from the burst using a **Laplacian variance** score.

### Stage 2 — Physical Verification (BumpDetector)

```
Accelerometer Z-axis -> Delta Calculation -> State Machine -> Signature Match
```

BumpDetector uses a **state machine** to classify physical signatures:

| Pattern | Classification |
|---------|----------------|
| Sharp drop (deltaZ < -threshold) followed by sharp rise within 600ms | POTHOLE |
| Sharp rise followed by sharp drop within 600ms | SPEED BUMP |
| Single spike without a matching return within 600ms | Timeout (Ignored) |

The sensitivity threshold (default: 3.8 m/s2) is user-configurable in Settings.

### Stage 3 — Cross-Correlation (DetectionManager)

DetectionManager is the mediator. When a sensor event fires:
1. It checks if a camera detection occurred **within the last 1500ms**.
2. If yes, the type is confirmed as POTHOLE (visual + physical match).
3. If no, SPEED_BUMP events still pass through (they don't require visual confirmation).
4. A **600ms lockout** prevents rebound spikes from triggering duplicate events.

On verification, the callback fires with `(RoadFeature, intensity: Float, List<Bitmap>)`.

### Final Phase — Persistence & Map Update
1. `PotholeRepository.savePothole()` JSON-serializes and persists the record.
2. `addHeatmapPoint()` creates a map marker.
3. `adaptiveOverlay.refresh()` recomputes the road quality grid scores.
4. The dashboard counters are updated.

---

## 7. Map Visualization System

### 7.1 AdaptiveRoadOverlay
A custom osmdroid `Overlay` that renders two distinct visualizations based on zoom level:

**Zoom Level < 15 — Segment Grade View**
- Detections are bucketed into a ~100m x 100m grid (~0.0009 degrees per cell).
- Each cell is scored and graded A-F by RoadQualityScorer.
- The grid is drawn as translucent colored rectangles with a letter label.
- A MapListener shows/hides the floating legend widget alongside this view.

**Zoom Level >= 15 — Heatmap Blob View**
- Each PotholeData is rendered as a radial gradient circle (blob).
- Red blobs = Potholes, Teal blobs = Speed Bumps.
- Blob radius scales with the `intensity` field (20px to 55px).
- Glow opacity scales with the global overlayAlpha for crossfade animations.

### 7.2 Road Quality Grading Engine (RoadQualityScorer)
| Score Range | Grade | Color | Label |
|---|---|---|---|
| 80 - 100 | A | Green (#2ECC71) | Excellent |
| 60 - 79 | B | Light Green (#82E0AA) | Good |
| 40 - 59 | C | Amber (#F4D03F) | Fair |
| 20 - 39 | D | Orange (#E67E22) | Poor |
| 0 - 19 | F | Red (#E74C3C) | Critical |

**Scoring Penalty per Detection:**
| Intensity (G-force) | Penalty |
|---|---|
| >= 2.5 | -35 points (Critical) |
| >= 1.5 | -20 points (Severe) |
| >= 0.8 | -10 points (Moderate) |
| < 0.8 | -4 points (Minor) |

### 7.3 Zoom Transition Animation
A MapListener monitors ZoomEvent. On a tier change, a ValueAnimator animates the overlay's alpha from 0 to 255 over 400ms, creating a smooth crossfade between the two visualization modes.

### 7.4 Route Polylines
Multi-candidate routes are drawn as Polyline overlays:
- **Active Route** — Action Blue (#3B82F6), 18dp stroke width, fully opaque.
- **Alternate Routes** — Slate Gray (#94A3B8), 14dp stroke width, 200/255 alpha.
- On tap, the selected polyline is promoted to the top of the overlay stack and re-styled as the active route.

---

## 8. Navigation & Routing

### 8.1 Geocoding — Photon API
- Used for fuzzy place search in the destination search bar.
- Requests are debounced by 500ms using a Kotlin Job to avoid API spam on keystroke.
- All searches are constrained to a **bounding box covering India** (68.1, 6.7, 97.4, 35.5).
- Results are displayed in a dropdown adapter and selected to trigger routing.

### 8.2 Smart Routing — OpenRouteService API
- Uses the ORS v2 Directions POST endpoint (`/v2/directions/driving-car/geojson`).
- Requests include the `alternative_routes` parameter to fetch **up to 3 candidate paths**.

**Hazard Avoidance Logic:**
1. On route request, all PotholeData records with `intensity > 0.8` are considered significant hazards.
2. Each hazard location is enclosed in a **20-meter polygon** using BoundingBoxUtils.
3. These polygons are sent as a GeoJSON `MultiPolygon` in the `options.avoid_polygons` field.
4. ORS calculates paths that steer clear of those defined zones.

**Map Interaction:**
- **Single-Tap** on map: Sets destination + triggers routing.
- **Long-Press** on map: Opens the RoadWise Simulator dialog.

### 8.3 Road Quality Analytics (Route Level)
After a route is fetched, RoadWise calculates a density metric:
```
density = total_potholes / route_length_km
```
| Density | Label |
|---|---|
| < 0.5 | EXCELLENT |
| < 1.5 | GREAT |
| < 3.0 | GOOD |
| < 5.0 | FAIR |
| >= 5.0 | HAZARDOUS |

This label updates on the dashboard in real-time whenever a new route or alternate route is selected.

### 8.4 Simulation Tool
Accessible via **long-press** on the map. Displays a dialog to place a virtual POTHOLE (intensity 2.6) or SPEED_BUMP (intensity 1.2) at the tapped coordinate. The hazard is saved to the repository, appears in the heatmap, and is immediately factored into rerouting calculations.

---

## 9. Data Persistence

### PotholeRepository
A singleton `object` managing all I/O operations.

- **Storage Medium**: SharedPreferences with key `pothole_prefs` / `potholes`.
- **Serialization**: Gson with custom JsonSerializer/JsonDeserializer for GeoPoint.
- **In-Memory Cache**: A `cached` field reduces repeated deserialization calls.
- **Startup Hydration**: On `MainActivity.onCreate()`, all saved detections are loaded to re-populate the heatmap markers and dashboard counter.

**CRUD Operations:**
| Method | Description |
|---|---|
| `getAllPotholes(ctx)` | Returns all records (from cache or disk) |
| `savePothole(ctx, data)` | Appends one record and writes to disk |
| `deletePothole(ctx, timestamp)` | Removes record by timestamp key |
| `clearAll(ctx)` | Wipes all JSON data and associated image files |

---

## 10. Settings & Configuration

All settings are stored in `roadwise_prefs` SharedPreferences.

| Preference Key | Type | Default | Effect |
|---|---|---|---|
| `pref_theme` | String | `system` | Dark / Light / System theme |
| `pref_battery_saver` | Boolean | false | Reduces GPS polling interval from 1s to 5s |
| `pref_sensor_threshold` | Float | 3.8 | Minimum G-force delta to trigger BumpDetector |
| `pref_audio_alerts` | Boolean | true | Enables/disables ToneGenerator beeps on detection |

**Data Management (from Settings):**
- **Storage Usage**: Calculates and displays total size of all stored detection image files.
- **Clear History**: Calls `PotholeRepository.clearAll()` to delete all JSON records and associated JPEG files.

---

## 11. UI / Design System

RoadWise uses a premium **"Glassmorphism Obsidian"** design theme built on custom attribute aliases.

### Theme Attributes
| Attribute | Usage |
|---|---|
| `?attr/glassSurface` | Primary background for floating widgets (semi-transparent dark) |
| `?attr/glassSurfaceDeep` | Deeper shade for the bottom navigation pill |
| `?attr/glassBorder` | Border color for all glassmorphic cards |
| `?attr/colorOnSurface` | Primary text color |
| `?attr/textFaded` | Secondary/disabled text color |

### Key UI Components
| Component | Description |
|---|---|
| Bottom Navigation Pill | Floating MaterialCardView with Drive / History / Settings tabs |
| Search Bar | Fuzzy autocomplete with Photon-powered place suggestions |
| HUD Dashboard | Session pothole count, max speed, current speed, route quality |
| Grade Legend | Floating widget showing A-F color reference; appears only at zoom < 15 |
| Camera Card | Draggable mini camera preview with toggleable visibility |
| Recenter Button | Floating button to snap the map back to the current GPS location |

---

## 12. Permissions

| Permission | Reason |
|---|---|
| CAMERA | Required for CameraX preview and ML inference |
| ACCESS_FINE_LOCATION | High-accuracy GPS for hazard geo-tagging |
| ACCESS_COARSE_LOCATION | Fallback coarse location |
| INTERNET | Required for OSM tile download, Photon geocoding, ORS routing |

> `WRITE_EXTERNAL_STORAGE` is intentionally omitted for Android 10+ compatibility. Images are saved to the app's scoped external storage directory instead.

---

## 13. Build System

| Property | Value |
|---|---|
| AGP Version | 8.1.0 |
| Gradle Wrapper | 8.7 |
| Java Compatibility | Java 21 |
| Build Variants | debug, release |
| BuildConfig Fields | ORS_API_KEY (injected from local.properties) |

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
1. Clone the repository.
2. Add your OpenRouteService API key to `local.properties`:
   ```properties
   ORS_API_KEY=your_api_key_here
   ```
3. Connect a physical Android device (recommended for Camera and Accelerometer testing).
4. Run `./gradlew installDebug` to build and install.
