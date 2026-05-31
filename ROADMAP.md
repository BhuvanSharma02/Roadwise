# RoadWise Implementation Roadmap

This document tracks the development phases of the RoadWise pothole detection and mapping system.

## Phase 1: Foundation & Project Setup ✅
- [x] Initialize Android (Kotlin) project structure
- [x] Configure `build.gradle` with dependencies (ONNX Runtime, osmdroid, Firebase, Retrofit)
- [x] Define necessary permissions in `AndroidManifest.xml`
- [x] Firebase Authentication (email/password login)
- [x] Splash → Login → Main activity flow with fade animations

## Phase 2: Sensor-Based Detection ✅
- [x] **BumpDetector:** Accelerometer Z-axis streaming at 100Hz
- [x] **Signal Processing:** Low-pass Butterworth filter + windowing (256 samples, 50% overlap)
- [x] **FFT Feature Extraction:** Peak frequency, spectral energy, kurtosis, ZCR, RMS
- [x] **ONNX Inference:** On-device classification via `DetectionManager` → `Normal` / `Speed Breaker` / `Pothole`
- [x] **Severity Scoring:** `RoadQualityScorer` assigns Low / Medium / High based on intensity and RMS

## Phase 3: Location & Data Persistence ✅
- [x] **GPS Tagging:** Fused Location Provider captures coordinates at event detection time
- [x] **Firebase Firestore:** Cloud sync of pothole events with offline-first local storage
- [x] **Session Tracking:** `SessionManager` accumulates GPS distance per drive session
- [x] **Multi-user Support:** Admin vs standard user role separation in data views

## Phase 4: Background Monitoring ✅
- [x] **Activity Recognition:** `DrivingReceiver` + Google Activity Recognition API auto-starts sensing when in vehicle
- [x] **Foreground Service:** `DriveGuardService` keeps detection active while app is backgrounded
- [x] **Boot Receiver:** `BootReceiver` re-registers activity monitoring after device reboot
- [x] **Battery Awareness:** Adaptive sampling rates; background service respects Doze mode

## Phase 5: Mapping & Heatmap Visualization ✅
- [x] **osmdroid Integration:** Offline-capable OpenStreetMap rendering
- [x] **HeatmapOverlay:** Custom tile overlay with radial glow per pothole cluster
- [x] **RoadGrade Clusters:** Segments graded A–F; critical clusters get map markers
- [x] **Overview Dashboard:** Admin view with severity filters, hotspot list, resolve action

## Phase 6: Routing & Route Analysis ✅
- [x] **OpenRouteService:** Turn-by-turn route calculation via Retrofit API
- [x] **Photon Geocoding:** Address search and reverse geocoding
- [x] **Route Risk Scoring:** Pothole exposure per route displayed before navigation
- [x] **Time Estimation:** Dynamic hours/minutes display based on journey duration

## Phase 7: User Features ✅
- [x] **Drive History:** Per-session log with pothole/bump counts, PDF export
- [x] **CSV Export:** Analysis report shareable from Overview screen
- [x] **Account Screen:** User profile, stats, sign-out
- [x] **Reward Points:** `SessionManager` accumulates distance-based points; `RedeemActivity` for redemption

## Phase 8: Polish & Optimization ✅
- [x] **Dark Theme:** Glassmorphism UI with neon accents throughout
- [x] **Micro-animations:** Fade-in/out transitions between screens
- [x] **Sensitivity Controls:** Adjustable detection thresholds in Settings
- [x] **Safety Alerts:** `SafetyAlertManager` shows non-intrusive in-app and notification alerts

## Future / Planned
- [ ] **Pothole-Aware Routing:** Actively avoid high-density pothole zones in route planning
- [ ] **Heatmap Server Tiles:** Pre-render server-side tiles for faster map load
- [ ] **Reward Redemption:** Live integration with UPI/voucher partners
- [ ] **Municipal API:** Push verified hotspot reports directly to local civic portals
