# RoadWise Implementation Roadmap

This document outlines the step-by-step development process for the RoadWise pothole detection and mapping system.

## Phase 1: Foundation & Project Setup ✅
- [x] Initialize Android (Kotlin) project structure.
- [x] Configure `build.gradle` with dependencies (CameraX, ML Kit, Maps SDK, Firebase).
- [x] Define necessary permissions in `AndroidManifest.xml`.
- [x] Create basic UI layout (Split screen: Camera View + Map View).

## Phase 2: Camera & Computer Vision (Real-time Detection) 🏗️
- [ ] **CameraX Integration:** Implement `Preview` and `ImageAnalysis` use cases.
- [ ] **Pothole Analyzer:** 
    - [ ] Integrate ML Kit Object Detection.
    - [ ] Create a custom `ImageAnalysis.Analyzer` to process frames.
    - [ ] (Optional) Integrate a custom TFLite model specifically trained for potholes.
- [ ] **Detection Overlay:** Draw bounding boxes on the camera feed to visualize detection in real-time.

## Phase 3: Sensor Fusion (Bump Detection)
- [ ] **SensorManager Setup:** Access Accelerometer and Gyroscope data.
- [ ] **Z-Axis Analysis:** Implement a "High-Pass Filter" or thresholding to detect sudden vertical jolts (the "Bump").
- [ ] **Verification Logic:** 
    - [ ] Correlate CV detection with Sensor detection.
    - [ ] Only mark as "Verified Pothole" if both CV detects a hole AND sensors detect a bump within a short time window.

## Phase 4: Location & Data Persistence
- [ ] **Location Tracking:** Implement `FusedLocationProviderClient` for high-accuracy GPS.
- [ ] **Local Storage:** Use Room Database to cache detected potholes when offline.
- [ ] **Cloud Sync:** 
    - [ ] Setup Firebase Firestore.
    - [ ] Upload "Verified Pothole" data (Lat/Lng, Intensity, Timestamp).

## Phase 5: Mapping & Heatmap Visualization
- [ ] **Google Maps Integration:** Display user's current location.
- [ ] **Heatmap Layer:** 
    - [ ] Fetch pothole data from Firestore.
    - [ ] Use `Google Maps Utility Library` to render a color-intensity heatmap.
- [ ] **Marker Management:** Add custom markers for highly dangerous potholes.

## Phase 6: Early Warning System
- [ ] **Proximity Alerts:** Implement logic to check user's distance from the nearest pothole cluster.
- [ ] **UI/Audio Warnings:** Provide visual/audible alerts ("Caution: Bad Road Ahead") when approaching a high-intensity area.
- [ ] **Background Execution:** Implement a `Foreground Service` to keep detection and warnings active while the app is in the background.

## Phase 7: Optimization & Polishing
- [ ] **Battery Optimization:** Fine-tune sensor sampling rates and GPS updates.
- [ ] **Sensitivity Controls:** Allow users to adjust CV and Sensor sensitivity levels.
- [ ] **UI/UX Enhancements:** Dark mode support, smooth animations, and dashboard-friendly UI.
