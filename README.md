# CellSignal Pro — iQOO 9 engineering build (v0.2 source)

Target device: iQOO 9 5G, Android 14 / Funtouch OS 14. Designed for multi-operator use with SIM 1 as the initial preferred subscription.

## Included
- Kotlin + Jetpack Compose Android application.
- Dark / light theme.
- SIM subscription picker, sorted by physical SIM slot (SIM 1 first).
- LTE metrics: RSRP, RSRQ, RSSNR and RSSI where the device exposes them.
- NR metrics: SS-RSRP, SS-RSRQ and SS-SINR where exposed.
- Registered cell information: Cell ID/NCI, PCI, TAC.
- Channel identifiers: LTE EARFCN and NR-ARFCN (these are channel numbers, not decoded band names).
- Two-second polling while live mode is enabled.
- Optional local CSV logging and Android document-picker export.
- Last-known GPS coordinates included in exported records when available.
- Dark/light mode, dashboard, engineering and logs views.

## Open / run
1. Extract this ZIP.
2. Open the `CellSignalPro` folder in Android Studio.
3. Allow Gradle sync.
4. Connect the iQOO 9 by USB, enable Developer options and USB debugging.
5. Run the `app` configuration.
6. Grant Phone and Location permissions. Select SIM 1 in the subscription selector.

## Important device/API constraints
- Android's Telephony APIs and OEM firmware determine which cell fields are available. A field can legitimately be unavailable and is shown as a dash.
- On 5G NSA, Android may report LTE as the data network and/or expose LTE anchor-cell readings rather than NR measurements. This is not proof that 5G is disconnected.
- Some carriers/OEM firmware restrict serving and neighbour cell information.
- The SIM selector chooses the TelephonyManager subscription context. Android data-network state and some signal callbacks may still be governed by the system's default data subscription.
- GPS export uses last-known location only; it is not a continuous GPS survey. Location may be blank until another app/system service has obtained a fix.
- EARFCN / NR-ARFCN are reported as channel identifiers. No unverified band mapping is shown.
- CSV logs are stored privately in app storage and are exported only when the user chooses a destination.
- No APK is included; build/install from Android Studio.

## Not yet included
Continuous GPS route survey, neighbour-cell scanning, decoded band/frequency lookup tables, network registration/NSA anchor correlation, speed test, background logging, and long-term database-backed charts. These need separate device testing and careful battery/privacy handling.


## v0.3 additions
- Common LTE/NR band estimates from EARFCN/NR-ARFCN ranges (explicitly marked estimated).
- Registered and neighbour LTE/NR cell summaries where Android exposes them.
- Updated app version to 0.3.0.

Band mapping is intentionally conservative and only covers selected common bands. It is not a substitute for modem diagnostics or carrier confirmation. Android 14 OEM firmware may return incomplete or no neighbour-cell data.


## Version 0.4.1 bug fix
- Added a fallback LTE SINR read from Android SignalStrength when CellInfoLte.rssnr is unavailable.
- Falls back to TelephonyManager.allCellInfo when a fresh cell-info callback returns an empty list.
- Existing 5G NR SINR reading is unchanged.

## Version 0.6.0 — Location-Based Signal Mapping
- Added a dedicated Signal Map tab while preserving Dashboard, Engineering and Logs.
- Added an opt-in foreground survey recorder. Start and stop recording manually.
- Requests a current Android location during each live monitoring cycle and stores GPS-tagged LTE/5G readings locally (up to 2,000 points).
- Added a relative GPS point plot coloured by SINR quality, plus a recent location/measurement list.
- Added a separate survey CSV export and a clear-survey action.
- Reuses the existing LTE SINR fallback and 5G NR SS-SINR logic without changing the radio-reading paths.
- Retains the existing app icon, dark/light theme and UI style.

### Mapping notes
- The plot is a relative coordinate plot, not a street-map tile layer; no Google Maps key or third-party map SDK is required.
- Keep CellSignal Pro open and Live monitoring enabled while recording. GPS can take several seconds and may return approximate coordinates depending on the permission choice, indoor reception, and device settings.
- The survey recorder is foreground-only. It does not run a location service in the background.
- Location points and survey name are stored locally in app-private preferences. Clear removes the saved map points.
- The app may show no point until Android can provide a location fix. This is expected indoors or when location is disabled.

## Build
- Open the `CellSignalPro` folder in Android Studio.
- Use Gradle JDK 17 and allow the Gradle 8.9 wrapper to download if it is not already installed.
- Build with **Build → Build APK(s)** or run `gradlew.bat assembleDebug` on Windows.
- App version: **0.6.0**, version code **6**.

## Validation status
The project source and Gradle wrapper scripts are included. A full Android build could not be executed in this environment, so perform the first Gradle sync/build in Android Studio and test location permission and GPS behaviour on the iQOO 9 before relying on survey results.
