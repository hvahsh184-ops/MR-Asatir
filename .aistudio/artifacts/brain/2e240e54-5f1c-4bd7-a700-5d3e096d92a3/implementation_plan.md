# Implementation Plan - Radar Surface (رادار سنجش سطح)

## User Requirements & Scope Alignment
1. **Direct Tablet Installation**: Clear, step-by-step guide for downloading and installing the APK directly on an Android tablet without a PC in a village.
2. **Crash Prevention & Stability**: Fix any potential crashes in AI tab or UI navigation, and implement automated RAM cleanup (`MemoryManager`) when RAM usage exceeds 90%.
3. **192kHz Passive Audio & Radio Pipeline**: Support high sample rate (up to 192kHz), subsonic filters, bandpass/highpass/lowpass filters, noise removal, automatic calibration, and start controls.
4. **Target Material Discrimination**: Specific filtering for Gold (طلا), Silver (نقره), Bronze (برنز), Brass (برنج), Copper (مس), Iron (آهن), Lead (سرب), Zinc (روی), Opal (سوپال), Sarouj (ساروج), and Cavity (حفره).
5. **Integrated Sensor Suite**: Ground/Space Radar (SAR + DEM), Magnetometer Sensor Fusion with Kalman Filter, Thermal/Optical Receiver Mode, and Passive Radio/Audio Receiver.

## Proposed Changes

### 1. Robustness & Crash Fixes
- Ensure all AI repository calls gracefully handle offline conditions without crashing.
- Verify `MemoryManager` monitors RAM via `ActivityManager` and triggers automated cleanup at 90% utilization.

### 2. High-Frequency 192kHz Passive Audio & Radio Pipeline
- Enhance `AudioHeadphonePassivePipeline` and `AudioStftEngine` to support max hardware sample rates (up to 192kHz when supported by device audio HAL).
- Add subsonic filtering (< 100Hz) and selectable bandpass filters to isolate target harmonic echoes from environmental mechanical vibrations.
- Implement one-touch **Calibrate** (baseline noise floor measurement) and **Start** continuous STFT processing with automated alert beeps (`MediaPlayer`).

### 3. Material Discrimination & Visual Tracking
- Provide material toggles for Gold, Silver, Bronze, Brass, Copper, Iron, Lead, Zinc, Opal, Sarouj, and Cavity.
- Implement pulsating visual target indicator synchronized with audio alert frequencies.

### 4. Tablet APK Installation Guide
- Provide precise, easy-to-follow instructions in Persian for downloading and installing the APK directly via AI Studio menu on an Android tablet.

## Verification Plan
- Run `gradle :app:testDebugUnitTest` to verify all math, filter, and sensor logic.
- Run `compile_applet` to confirm clean compilation build.
