# 🛣️ RoadWise — Edge-AI Road Condition Monitoring

**Turning every smartphone into a road health sensor — no extra hardware required.**

---

## 📄 Abstract

Poor road infrastructure remains a major safety concern in India, contributing to thousands of accidents annually due to undetected potholes and inefficient maintenance workflows. Existing reporting systems depend on manual complaints, resulting in delayed responses and lack of structured, actionable data for municipal authorities.

This project proposes a mobile-based Edge-AI system that enables real-time pothole detection, classification, and geo-tagged reporting using only a smartphone's built-in sensors. The system eliminates the need for external hardware by leveraging the phone's accelerometer and GPS, making it highly scalable and easy to deploy across large populations.

The application runs on-device, ensuring low latency, privacy, and minimal reliance on cloud infrastructure. By combining inertial sensing with spectral analysis, the system accurately detects road anomalies and distinguishes between potholes and speed breakers, significantly reducing false positives.

---

## 🚨 Problem Statement

India's road infrastructure crisis is not just an inconvenience — it is a public health emergency backed by stark data:

> **📊 53% rise in pothole fatalities between 2020–2024, totalling 9,438 deaths**
> — Source: MoRTH Parliament Data

> **🌍 India has just 1% of the world's vehicles, yet accounts for 11% of global road crash deaths**
> — Source: World Bank

> **💸 Road crashes cost India 3.14% of GDP annually** — a figure that dwarfs most infrastructure budgets.

> **⏳ Average delay between road damage and repair: 3–6 months** — creating prolonged hazard windows for millions of daily commuters.

Despite the scale of this crisis, most municipal road monitoring still relies on citizen complaints via phone or web portals — processes that are slow, unstructured, and difficult to act on at scale. There is a clear, urgent need for an automated, data-driven, and deployable solution.

---

## ✨ Key Features

| # | Feature | Description |
|---|---------|-------------|
| 📱 | **Zero Extra Hardware** | Works entirely with the sensors already in your smartphone — no attachments needed |
| ⚡ | **Real-Time Edge AI** | On-device inference with sub-100ms latency, no internet required for detection |
| 🔊 | **FFT Spectral Analysis** | Distinguishes potholes from speed breakers using frequency-domain signal features |
| 📍 | **GPS Geo-Tagging + Severity** | Every event is tagged with location and classified as Low / Medium / High severity |
| 🗺️ | **Live Road Health Heatmap** | Interactive map showing pothole hotspots updated in real time from crowdsourced data |
| 📴 | **Offline Capable** | Events are queued locally and synced to the cloud once connectivity is restored |
| 🔒 | **Privacy-First** | No continuous audio/video recording; only accelerometer and GPS data are used |
| 🤝 | **Crowdsourced Confidence** | Multiple user reports of the same event increase confidence scoring for authorities |

---

## 🏗️ Core Architecture

The architecture consists of three core modules:

### 1. 📡 Inertial Sensing & Signal Acquisition

The smartphone's built-in accelerometer continuously captures vertical and longitudinal motion data while the user is traveling. These signals reflect road surface irregularities but are inherently noisy due to varying driving conditions and user behavior.

### 2. 🔬 Spectral Analysis & Event Classification

The captured time-series accelerometer data is transformed into the frequency domain using Fast Fourier Transform (FFT).

* **Potholes** generate sharp, high-amplitude, broadband spikes due to sudden impact.
* **Speed breakers** produce smoother, periodic waveforms with dominant low-frequency components.

By analyzing spectral features such as energy distribution, dominant frequencies, and signal variance, a lightweight on-device classifier (e.g., Random Forest or 1D-CNN) accurately distinguishes between potholes and speed breakers. This improves detection precision while maintaining computational efficiency on mobile devices.

#### 📊 Frequency Signature Comparison

| Road Event | Frequency Profile | Amplitude | Duration | Key Characteristic |
|------------|------------------|-----------|----------|--------------------|
| 🟢 **Normal Road** | Low, consistent baseline across all bands | Low | Continuous | Negligible spectral energy; flat FFT output |
| 🟡 **Speed Breaker** | Smooth periodic; dominant low-frequency components (1–5 Hz) | Moderate | Extended (0.5–1.5s) | Narrow-band, low-frequency peak; gradual rise and fall |
| 🔴 **Pothole** | Broadband spike across multiple frequency bands (5–50 Hz) | High | Short (< 0.3s) | Wide energy spread; sharp transient with high kurtosis |

### 3. 🗺️ Geo-Tagging & Civic Dashboard Integration

Each detected event is tagged with GPS coordinates using the smartphone's location services. The data is then uploaded to a centralized dashboard where authorities can monitor:

* **Pothole hotspots** (heatmaps)
* **Severity levels** (based on vibration intensity)
* **Temporal trends** for predictive maintenance

The system can generate automated reports and alerts, enabling faster response and better prioritization of road repairs.

---

## 🤖 ML Model

### Feature Set

The classifier operates on the following spectral and statistical features extracted from each windowed signal segment:

| Feature | Description |
|---------|-------------|
| **Peak Frequency** | The dominant frequency bin with maximum energy in the FFT output |
| **Spectral Energy** | Total power across the spectrum; high energy indicates a significant road event |
| **Energy Distribution** | Ratio of energy across low, mid, and high frequency bands to identify event type |
| **Spectral Variance** | Spread of energy across the spectrum; higher for potholes than speed breakers |
| **Peak Amplitude** | Maximum instantaneous acceleration value during the event window |
| **RMS (Root Mean Square)** | Effective signal magnitude; correlates with severity level |
| **Kurtosis** | Statistical sharpness of the signal distribution; very high for impulse events like potholes |
| **Zero Crossing Rate** | Rate at which the signal crosses zero; differentiates smooth from abrupt waveforms |

### Performance Targets

| Metric | Target |
|--------|--------|
| **Classification Accuracy** | > 85% |
| **False Positive Rate** | < 10% |
| **Inference Latency** | < 100ms (on-device) |
| **Minimum Training Samples** | 500 labelled events |

---

## ⚙️ How It Works

The end-to-end detection and reporting pipeline runs as follows:

1. **App Start** — User opens RoadWise and begins a trip; background sensing service is activated.
2. **Sensor Collection** — The accelerometer streams Z-axis (vertical) and X-axis (longitudinal) data at 100 Hz.
3. **Noise Filtering** — A low-pass Butterworth filter removes high-frequency electronic noise unrelated to road events.
4. **Windowing** — The signal is divided into overlapping time windows (e.g., 256 samples with 50% overlap) for continuous analysis.
5. **FFT Transform** — Each window is converted to the frequency domain; spectral features are extracted.
6. **Classification** — The on-device TFLite model classifies the segment as: `Normal`, `Speed Breaker`, or `Pothole`, along with a severity score.
7. **GPS Tagging** — If a pothole is detected, the Fused Location Provider captures the precise GPS coordinate at the moment of impact.
8. **Firebase Upload** — The event payload (location, severity, timestamp, features) is pushed to Firestore. If offline, it is queued locally in Room DB.
9. **Dashboard Update** — The civic dashboard updates the heatmap and severity layer in real time.
10. **Driver Alert** — A non-intrusive haptic + visual notification is shown to the driver for awareness.

---

## 🛠️ Tech Stack

| Category | Technology | Purpose |
|----------|-----------|---------|
| **Mobile App** | Kotlin + Android SDK | Core application logic, UI, background services |
| **Signal Processing** | FFT (JTransforms / custom) | Frequency-domain feature extraction from accelerometer data |
| **Model Training** | Python + Scikit-learn | Offline training of Random Forest / 1D-CNN classifier |
| **On-Device Inference** | TensorFlow Lite | Lightweight, fast ML inference running on the mobile device |
| **Location Services** | Fused Location Provider | GPS coordinate capture at event detection time |
| **Cloud Backend** | Firebase Firestore | Real-time cloud storage and sync of road event data |
| **Map Visualization** | Google Maps API / osmdroid | Heatmap rendering and pothole marker display |
| **Sensor Access** | Android Sensor Framework | Low-level access to accelerometer and gyroscope hardware |

---

## 📁 Project Structure

```
RoadWise/
│
├── android-app/                    # Android application source
│   ├── sensing/                    # Accelerometer data collection & filtering
│   ├── processing/                 # FFT, windowing, feature extraction
│   ├── classification/             # TFLite model integration & inference engine
│   ├── reporting/                  # Firebase upload, offline queue (Room DB)
│   └── ui/                         # Jetpack Compose / XML UI components
│
├── ml-model/                       # Machine learning pipeline
│   ├── data_collection/            # Raw accelerometer data & labelling scripts
│   ├── preprocessing/              # Noise filtering, windowing, FFT scripts
│   ├── training/                   # Model training (Random Forest / 1D-CNN)
│   ├── evaluation/                 # Metrics, confusion matrix, cross-validation
│   └── conversion/                 # TFLite conversion and quantization scripts
│
├── dashboard/                      # Civic monitoring dashboard
│   ├── heatmap/                    # Pothole hotspot map visualization
│   ├── reports/                    # Automated PDF/CSV report generation
│   └── alerts/                     # Notification & alert dispatch system
│
└── docs/                           # Project documentation and research references
```

---

## 🚀 Setup and Installation

### Prerequisites

- Android Studio (Hedgehog or later)
- Android device with API 26+ (Android 8.0 Oreo or higher)
- Python 3.8+ with `pip`
- A Firebase project with Firestore and Authentication enabled
- Google Maps API key (or osmdroid for offline maps)

---

### 📱 Android App Setup

```bash
# 1. Clone the repository
git clone https://github.com/your-org/roadwise.git
cd roadwise/android-app

# 2. Add your Firebase configuration file
# Download google-services.json from Firebase Console
# Place it in: android-app/app/google-services.json

# 3. Add your Maps API key in local.properties
echo "MAPS_API_KEY=your_api_key_here" >> local.properties

# 4. Build and run on a connected device or emulator
./gradlew assembleDebug
./gradlew installDebug
```

---

### 🧠 ML Model Training

```bash
# 1. Navigate to the ml-model directory
cd roadwise/ml-model

# 2. Install Python dependencies
pip install -r requirements.txt
# Installs: numpy, scipy, scikit-learn, tensorflow, pandas, matplotlib

# 3. Preprocess raw accelerometer data
python preprocessing/preprocess.py --input data_collection/raw/ --output data_collection/processed/

# 4. Train the classifier
python training/train_model.py --data data_collection/processed/ --output training/saved_model/

# 5. Evaluate performance
python evaluation/evaluate.py --model training/saved_model/

# 6. Convert to TensorFlow Lite for on-device deployment
python conversion/convert_to_tflite.py \
    --model training/saved_model/ \
    --output ../android-app/app/src/main/assets/roadwise_model.tflite \
    --quantize
```

---

## 🌍 Social Impact

RoadWise is designed not just as a technical tool, but as a platform for civic empowerment and sustainable development.

| UN Goal | Relevance |
|---------|-----------|
| 🏗️ **SDG 9 — Resilient Infrastructure** | Enables data-driven road maintenance, reducing infrastructure degradation and improving public safety |
| 🏙️ **SDG 11 — Sustainable Cities & Communities** | Supports smarter, safer urban mobility by giving city authorities real-time, structured road health data |

**Additional Impact:**

- 🚗 **Reduces vehicle damage costs** for everyday commuters by providing pothole-aware navigation and early alerts
- 🏛️ **Free automated road health data** delivered to municipal authorities — no procurement budget required
- 📲 **Passive citizen participation** — users contribute to a public good just by driving with the app open
- 📉 **Accelerates repair prioritization** by surfacing severity-ranked hotspots instead of a flat complaint queue

---

## 👥 Team

| Name | Student ID | Role |
|------|-----------|------|
| Bhuvan Sharma | 2022A1R003 | Android Development & Sensor Integration |
| Simriti Kak | 2022A1R004 | ML Model Training & Signal Processing |
| Pratham Seth | 2022A1R037 | Backend, Dashboard & Firebase Integration |
| **Asst. Prof. Saurabh Sharma** | — | Supervisor, Dept. of Computer Science & Engineering |

---

## 📜 Conclusion

By leveraging widely available smartphones and edge AI techniques, this solution offers a cost-effective, scalable, and real-time approach to road condition monitoring. It empowers citizens to passively contribute data while providing municipal authorities with actionable insights for smarter infrastructure maintenance.

---

*Because every road deserves to be monitored, not just repaired.*


