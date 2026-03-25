# RoadWise System Architecture Diagram

```mermaid
graph TD
    subgraph "Hardware Layers (Android Device)"
        CAM[Camera Hardware]
        ACC[Accelerometer Sensor]
        GPS[GPS/Location Provider]
    end

    subgraph "Perception & Analysis (Background Threads)"
        CAM -->|ImageProxy| CX[CameraX ImageAnalysis]
        CX -->|InputImage| MLK[ML Kit Object Detection]
        MLK -->|BoundingBoxes / Confidence| PA[PotholeAnalyzer]
        
        ACC -->|SensorEvent Z-Axis| BD[BumpDetector]
        BD -->|Pothole vs SpeedBump| BD
    end

    subgraph "The Brain (Verification Logic)"
        PA -->|Visual Detection Signal| DM[DetectionManager]
        BD -->|Physical Jolt Signal| DM
        
        DM -->|Correlation 1500ms Window| VF[Verified Feature Event]
    end

    subgraph "Presentation Layer (UI Thread)"
        GPS -->|Current Lat/Lng| MA[MainActivity]
        VF -->|Feature Data| MA
        
        MA -->|Draw Heatmap Glow| OSM[OpenStreetMap View]
        MA -->|Draw Bounding Boxes| GO[GraphicOverlay]
        MA -->|Update Stats| HUD[Status HUD / Pothole Counter]
    end

    subgraph "Persistence (Future)"
        MA -.->|Upload| FB[Firebase Firestore]
        FB -.->|Fetch Global Data| MA
    end
```

### Key Logic Paths:
1.  **Visual Path:** Camera $\rightarrow$ ML Kit $\rightarrow$ `PotholeAnalyzer` $\rightarrow$ `GraphicOverlay` (Real-time red boxes).
2.  **Physical Path:** Accelerometer $\rightarrow$ `BumpDetector` (Detection of jolt direction).
3.  **Verification Path:** Both signals meet in `DetectionManager`. If they align in time, it's a **Verified Pothole**.
4.  **Mapping Path:** Verified events are paired with **GPS coordinates** and drawn as a **Radial Glow** on the OpenStreetMap.
