# FoodSense

FoodSense is an Android app for managing food stored at home, with a strong focus on expiration dates. It helps track pantry and fridge items, identify products that are expired or close to expiring, and keep the list available across devices.

## Features

- Add food products with quantity, unit, and expiration date.
- Group individual items of the same product in the UI.
- Highlight expired products and products that are about to expire.
- Scan barcodes with CameraX and ML Kit Barcode Scanning.
- Look up scanned products through a local barcode cache and Open Food Facts.
- Capture labels with OCR to extract product names, quantities, dates, and barcodes.
- Scan expiration dates directly from the add-product form.
- Sign in with Firebase Auth using Google or email/password.
- Sync products across devices with Cloud Firestore.
- Keep Room as the local source of truth for offline-first behavior.
- Schedule expiration and sync work with WorkManager.

## Tech stack

- Kotlin
- Jetpack Compose + Material 3
- Navigation 3
- Hilt
- Room
- Preferences DataStore
- Firebase Auth
- Cloud Firestore
- WorkManager
- CameraX
- ML Kit Text Recognition
- ML Kit Barcode Scanning
- Kotlin Coroutines + Flow

## Project details

- **Package:** `dev.pcha.foodsense.app`
- **Min SDK:** 26
- **Target SDK:** 36
- **Architecture:** single Android module, `:app`
- **Local database:** Room, schema version 7

## Architecture overview

FoodSense follows a layered Android architecture:

```text
UI layer  ->  Data layer
```

- The **UI layer** is built with Compose screens and ViewModels. ViewModels expose immutable UI state and receive user events.
- The **data layer** exposes repositories for local data, authentication, synchronization, barcode lookup, preferences, and ML Kit wrappers.
- Room is the single source of truth. The UI reads local state from Room through repository flows.
- Firestore synchronization runs in the background and updates the local database when remote changes arrive.

There is no dedicated domain layer yet. Use cases should only be added when logic becomes complex or shared across multiple ViewModels.

## Important source areas

```text
app/src/main/java/dev/pcha/foodsense/app/
├── data/
│   ├── auth/          # Firebase Auth wrapper and User model
│   ├── barcode/       # Local barcode registry and Open Food Facts lookup
│   ├── local/         # Room database, entities, DAOs, migrations
│   ├── mlkit/         # Text and barcode scanner abstractions
│   ├── preferences/   # Onboarding preferences
│   └── sync/          # Firestore sync repository and worker
├── notification/      # Expiration notification worker
└── ui/
    ├── auth/          # Login and authentication state
    ├── product/       # Main product list, add/edit form, date scanner
    ├── scan/          # Barcode/OCR scan flow
    └── Navigation.kt  # Typed Navigation 3 back stack
```

## Product scanning flow

1. The scan screen starts in barcode mode and analyzes camera frames in real time.
2. The app checks the local barcode registry first, then Open Food Facts.
3. If a product is found, the user can confirm or reject it.
4. If the barcode is unknown or rejected, the flow can switch to OCR.
5. OCR extracts a product name, quantity, expiration date, and possible barcode from the label.
6. The result pre-fills the add-product form.

The add-product form also includes a dedicated expiration-date scanner that captures a date with CameraX and parses common numeric and month-name formats.

## Synchronization model

The app is offline-first:

- Local writes go to Room first.
- Authenticated writes are pushed to Firestore in the background.
- Firestore snapshots are reconciled back into Room.
- `serverId` links local Room rows to Firestore documents.
- `updatedAt` supports last-write-wins conflict resolution.
- On sign-out, synchronized local data is cleared while local-only data is preserved for future migration.

Sync behavior is one of the most delicate parts of the project. Before changing it, read the notes in `CLAUDE.md` and the tests around `DefaultProductRepository`.

## Running the project

Open the repository in Android Studio and run the `app` module.

Useful Gradle commands:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Instrumented tests require an Android device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

The Gradle build service starts Firebase Auth and Firestore emulators automatically for instrumented tests when needed. If emulators are already running, it reuses them.

## Firebase rules

Firestore rules and their tests live in `firebase/`.

```bash
cd firebase
npm ci
npm test
npm run emulators
```

Use the Firebase CLI from the `firebase/` directory because it is installed as a local dev dependency there.

## CI and release

GitHub Actions are configured for:

- Unit tests and lint on pushes to `develop` and pull requests.
- Firestore rules tests.
- Instrumented tests on an Android emulator.
- Release builds on pushes to `main`.

Releases use conventional commits, generate a signed APK, deploy Firestore rules, and attach the APK to the GitHub Release. Play Store publishing is intentionally disabled.

## Development notes

- Keep visible strings in Android resources.
- Do not access Firebase, Room DAOs, DataStore, or ML Kit SDK types directly from UI code.
- Prefer fakes over mocks in tests.
- Add tests with new features.
- Keep Room schemas exported under `app/schemas/`.
- Treat synchronization changes with extra care and verify them with tests.

For deeper project-specific guidance, read `CLAUDE.md`.
