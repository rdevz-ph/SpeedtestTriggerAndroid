# Speedtest Trigger Android

A lightweight Android application designed to maintain stable internet performance by periodically triggering a speed test using a minimal data approach.

This app is built using Java + Chaquopy (Python) and integrates a custom speed test logic to simulate network activity without requiring a full browser-based test.

---

> [!NOTE]
> Some internet service providers throttle bandwidth during idle periods. This application helps mitigate that behavior by periodically triggering a lightweight speed test process, encouraging the network to maintain peak performance.

The app runs a loop-based trigger system with configurable intervals and supports skipping heavy download/upload tests to reduce data usage.

---

## Features

- Periodic speed test trigger loop
- Configurable interval (seconds)
- ISP detection and display
- Best server selection
- Ping monitoring
- Optional download and upload tests
- Lightweight Python integration via Chaquopy
- Log output for monitoring activity
- Peak result tracking (download, upload, server, ping)
- Background-ready architecture (extendable via service)

---

## Technology Stack

- Java (Android)
- Python (Chaquopy)
- Gradle
- GitHub Actions (CI/CD for APK build and release)

---

## Requirements

- Android Studio (for development)
- Minimum SDK: 24
- Internet permission required

---

## Build Instructions

### Local Build

./gradlew assembleDebug

APK output:
app/build/outputs/apk/debug/

---

### GitHub Actions Build

This project includes an automated CI pipeline:

- Builds APK on every push to main
- Publishes APK to GitHub Releases under latest

To download:
1. Go to the repository
2. Open the Releases section
3. Download the latest APK

---

## Notes

- This project is intended for educational and experimental purposes
- Actual network behavior may vary depending on ISP policies
- Use responsibly to avoid excessive data usage

---

## License

This project is licensed under the MIT License. See the [LICENSE](./LICENSE) file for details.
