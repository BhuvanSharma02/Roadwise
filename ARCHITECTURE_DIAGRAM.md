# RoadWise System Architecture Diagram

```mermaid
graph TD
    subgraph "Hardware Layers (Android Device)"
        ACC[Accelerometer Sensor]
        GPS[GPS / Fused Location Provider]
        ACT[Activity Recognition API]
    end

    subgraph "Perception & Analysis (Background Threads)"
        ACT -->|In-Vehicle Transition| DR[DrivingReceiver]
        DR -->|Start Foreground Service| DGS[DriveGuardService]

        ACC -->|SensorEvent Z-Axis 100Hz| BD[BumpDetector]
        BD -->|256-sample Window + FFT| FE[Feature Extractor]
        FE -->|8 Spectral Features| ONNX[ONNX Runtime - DetectionManager]
    end

    subgraph "Classification & Scoring"
        ONNX -->|Normal / SpeedBump / Pothole| RQS[RoadQualityScorer]
        GPS -->|Lat/Lng at Event Time| RQS
        RQS -->|Severity: Low/Med/High| EVT[Verified Feature Event]
    end

    subgraph "Persistence Layer"
        EVT -->|PotholeData| PR[PotholeRepository]
        PR -->|Local Cache| LOCAL[(SharedPrefs / Local)]
        PR -->|Cloud Sync| FB[Firebase Firestore]
        FB -->|Fetch Global Data| PR
    end

    subgraph "Presentation Layer (UI Thread)"
        GPS --> MA[MainActivity]
        PR --> MA
        MA -->|HeatmapOverlay| OSM[osmdroid MapView]
        MA -->|SafetyAlertManager| ALERT[In-App Alert / Notification]
        MA -->|SessionManager| SM[Drive Session & Points]
    end

    subgraph "Secondary Screens"
        PR --> OV[OverviewActivity - Hotspot Map]
        PR --> HI[HistoryActivity - Drive Log + PDF]
        PR --> RA[RouteAnalysisActivity - Risk Score]
        SM --> RD[RedeemActivity - Reward Points]
    end
```

### Key Logic Paths:

1. **Detection Path:** Accelerometer → `BumpDetector` → FFT Features → ONNX Model → Severity Score
2. **Location Path:** GPS tags every verified event at the exact moment of detection
3. **Sync Path:** Events written locally first, then pushed to Firestore; fetch merges cloud + local
4. **Heatmap Path:** `PotholeRepository` data → `HeatmapOverlay` → rendered as radial glow clusters on osmdroid
5. **Background Path:** Activity Recognition detects vehicle → starts `DriveGuardService` → keeps sensing alive

### Why No Camera?

The current architecture is **sensor-only by design**. Camera-based detection was explored in early prototypes but removed because:
- Sensor + ONNX approach achieves comparable accuracy with **significantly lower battery drain**
- Works in any lighting condition (night, tunnels, rain)
- No camera permission required — better for user privacy
- On-device inference latency < 100ms using only accelerometer features
