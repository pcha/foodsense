# FoodSense

Android app for managing food stored at home, focused on pantry/fridge inventory and expiration dates.

## Project-specific guidance

Before implementing non-trivial Android changes, review the project skills if they are available in the current environment:

- `/android_architecture_principles` — non-negotiable Android architecture principles
- `/android_layers` — three-layer stack: UI → Domain → Data
- `/android_ui_layer` — UDF, ViewModel, UiState, Compose
- `/android_viewmodel` — ViewModel rules: lifecycle, coroutines, state
- `/android_data_layer` — repository and data source structure
- `/android_di_testing` — Hilt, fakes, unit tests

## Project info

- **Package:** `dev.pcha.foodsense.app`
- **Min SDK:** 26
- **Target SDK:** 36
- **Module structure:** single Android module, `:app`
- **Room schema:** version 8

## Tech stack

- Kotlin
- Jetpack Compose + Material 3
- Hilt for dependency injection
- Room as the local database
- Preferences DataStore for onboarding state
- Firebase Auth: Google Sign-In and email/password
- Cloud Firestore for multi-device sync
- WorkManager with Hilt workers for expiration notifications and periodic sync
- Navigation 3 with typed `NavKey` destinations
- CameraX: `camera-camera2`, `camera-lifecycle`, `camera-view`
- ML Kit Text Recognition
- ML Kit Barcode Scanning
- Kotlin Coroutines + Flow
- Coil for image loading

## Domain model

The central concept is `Product`: a food product stored by the user.

Key concepts:

- Expiration date is the main business signal. It controls sorting, grouping, warnings, and status.
- A product can be expired, expiring soon, OK, or have no expiration date.
- Products contain individual `Item`s. Items with the same quantity, unit, and expiration date are grouped in the UI.
- Items can be added in batches and edited as groups.
- Product names act as the natural key in local add flows: adding the same name appends items to the existing product.

## Architecture

The project currently follows a two-layer Android architecture:

```text
UI layer -> Data layer
```

- **UI layer:** Compose screens + ViewModels. ViewModels expose immutable `UiState` data classes and receive UI events.
- **Data layer:** repositories and data sources for Room, Firebase Auth, Firestore sync, barcode lookup, preferences, and ML Kit wrappers.
- There is no dedicated domain layer yet. Add use cases only when logic becomes complex, shared, or difficult to test inside a ViewModel/repository.

Important boundaries:

- ViewModels must not access Room DAOs directly. Use repositories.
- ViewModels and composables must not import Firebase, DataStore, or ML Kit SDK types directly.
- Data layer SDK types must be mapped to app/domain models before reaching the UI.

## Main source layout

```text
app/src/main/java/dev/pcha/foodsense/app/
├── data/
│   ├── auth/          # AuthRepository, FirebaseAuthRepository, User model
│   ├── barcode/       # BarcodeRepository, Room cache, Open Food Facts lookup
│   ├── local/         # Room database, entities, DAOs, migrations
│   ├── mlkit/         # TextRecognizer and BarcodeImageScanner abstractions
│   ├── preferences/   # OnboardingRepository backed by Preferences DataStore
│   └── sync/          # Firestore sync repository and SyncWorker
├── notification/      # ExpirationNotificationWorker
└── ui/
    ├── auth/          # LoginScreen and AuthViewModel
    ├── product/       # ProductScreen, ProductViewModel, DateScanSheet
    ├── scan/          # ScanScreen, ScanViewModel, OcrExtractor
    ├── Navigation.kt
    └── NavigationKeys.kt
```

## Navigation and auth gate

- `NavigationKeys.kt` defines typed Navigation 3 destinations: `Main`, `Scan`, and `Login`.
- `Navigation.kt` owns the back stack via `rememberNavBackStack(Main)`.
- `AuthViewModel` exposes the current user and onboarding-completed flag.
- The login gate runs once after onboarding state has loaded. It opens `Login` only when onboarding is not completed and there is no current user.
- `LoginScreen` reflects state and delegates actions to `AuthViewModel`; it should not access Firebase directly.

## Product scanning flow

1. The scan screen starts in barcode mode and analyzes CameraX frames in real time with ML Kit Barcode Scanning.
2. `BarcodeRepositoryImpl` checks the local Room barcode registry first, then Open Food Facts.
3. If a product is found, the user sees a confirmation sheet.
4. If the user rejects a cached result, the local barcode entry is deleted.
5. If no product is found or the result is rejected, the flow can switch to OCR.
6. OCR captures a label image, then `OcrExtractor` tries to extract product name, quantity, expiration date, and barcode.
7. The scan result returns to `ProductViewModel`, which opens the add-product form with prefilled fields.

### Remembering barcodes

The “Remember barcode” checkbox is shown only when:

- `pendingBarcode != null`, and
- the user changed the prefilled name from `originalFormName`.

This avoids saving an already-known barcode/name pair unless the user corrected it.

## Expiration date scanner

- The add-product form has a camera button next to the date picker.
- It opens `DateScanSheet`, a `ModalBottomSheet` with a CameraX preview.
- `isProcessing` is local composable state, not ViewModel state.
- `ProductViewModel` exposes only `showDateScanner` and `dateScanError` in `ProductUiState`.
- `parseDateStr` in `ProductViewModel` handles:
  - numeric formats such as `dd/MM/yyyy`, `dd/MM/yy`, and `MM/yyyy`;
  - separators `.`, `-`, and `/`;
  - month-name formats such as `d MMM yyyy` and `MMM yyyy`;
  - month-only dates by resolving to the end of the month;
  - locale-based `dd/mm` vs `mm/dd` disambiguation, with `en_US` treated as month-first.

## Data layer: barcode lookup

```text
data/barcode/
├── BarcodeRepository.kt
├── BarcodeRepositoryImpl.kt
├── BarcodeEntry.kt
├── LocalBarcodeRegistry.kt
├── OpenFoodFactsBarcodeRepository.kt
└── di/BarcodeModule.kt
```

The implementation composes:

- a local Room cache, and
- a remote Open Food Facts lookup.

Recent Room migration context:

- v5 added `barcode_registry`.
- v6 added `serverId` to `product` and `item` for sync.
- v7 added `updatedAt` to `product` for last-write-wins conflict resolution.
- v8 adds the account-scoped `sync_outbox` table without rewriting existing inventory.

## Data layer: offline-first Firestore sync

Room is the single source of truth. The UI reads from Room only. Firestore sync runs in the background.

### Versioned outbox rollout status

The versioned outbox protocol is **not active yet**. The app still uses the legacy product-array
Firestore sender described below. Do not wire `InventoryOutbox` to that sender: it cannot enforce
operation deduplication, entity revisions, or deletion tombstones.

The first rollout stage provides:

- `LocalTransactions` / `RoomLocalTransactions`: one Room transaction per batch add, update, or delete.
- `ProductRepository.updateItems` and `deleteItems`: ViewModels submit one action, not per-item writes.
- One legacy remote write per affected product for a batch edit/delete.
- `InventoryOutbox.enqueue`: commits a local mutation and an immutable, versioned operation together.
- Account-scoped FIFO ordering, attempts, transient-error metadata, and a blocked-head state. Ordering
  uses the database sequence, not a device timestamp. Payloads retain base revisions and tombstones.
- No outbox worker is scheduled and application mutations do not enqueue intents yet. The isolated
  remote protocol below exists, but local entity revision persistence, conflict presentation/resolution,
  guest/account ownership, and a legacy-client cutover/migration are still required before activation.

Tests use real Room to check intermediate inventory emissions, rollback on failure/cancellation,
duplicate operation IDs, account isolation, and persistence after reopening the database.

### Isolated V2 remote operation protocol

`FirebaseInventoryOperationRemote` implements `InventoryOperationRemote` without a live Hilt binding.
It only uses the new collections under `users/{uid}`:

- `inventory_products`: independent product documents.
- `inventory_items`: independent lot documents, with `productId` as a field.
- `inventory_operations`: immutable operation receipts, keyed by the persisted operation ID.

An operation reads its receipt and affected entities in one Firestore transaction. Existing receipts
return the original result without touching newer entity state; reusing an ID for a different payload
is rejected. SHA-256 fingerprints canonicalize object keys and entity ordering. Successful operations
write the entities and receipt atomically. Entity revisions advance by one; `updatedAt` / `committedAt`
are server timestamps for diagnostics, not conflict arbitration.

The initial conflict policy is deliberately conservative: edits require an exact base revision;
deletions may beat a concurrent edit, create a persistent tombstone, and repeated deletions are no-ops.
Edits cannot resurrect tombstones. Disjoint-field merges are not implemented: they still conflict.
If CAS security rules reject a concurrent commit before Firestore returns ABORTED, the adapter checks
authorized server reads for revision drift and re-evaluates the same operation at most once. Genuine
authorization failures are propagated. `disableNetwork()` was not a reliable simulation of transaction
isolation in emulator tests; do not claim offline transaction coverage from that API alone.

Atomic actions are capped at 100 entity changes / 128 KiB canonical payload and are rejected, never
silently split. Rules tests cover both 15-item and maximum-size actions. Receipts have no TTL. Rules
enforce owner isolation, matching receipts on entity writes, revision steps, and immutable tombstones
and receipts. They do not recompute SHA-256 or prove every effect in an owner-created receipt was
written: protocol correctness assumes the owning clients use this adapter; a hostile owner can poison
their own receipt IDs. A trusted backend would be needed to enforce that stronger invariant.

Before activation, also implement parent/product deletion semantics, referential consistency, and
permanent receipt/tombstone retention policy. This adapter does not migrate legacy products or query
and reconcile the V2 collections into Room.

```text
data/sync/
├── FirestoreSyncRepository.kt
├── FirebaseFirestoreSyncRepository.kt
├── SyncWorker.kt
└── di/SyncModule.kt
```

Core rules implemented in `DefaultProductRepository`:

- **Models stay layer-specific:** `FirestoreProduct` / `FirestoreItem` for network, Room entities for persistence, `Product` / `Item` for UI-facing app models.
- **Optimistic local writes:** product mutations update Room first, then push to Firestore via `syncIfAuthenticated` in `appScope`.
- **`serverId` maps Room rows to Firestore documents.** For new products, the product `serverId` is written to Room immediately after the first successful Firestore upsert so snapshot listeners do not insert duplicates.
- **Item `serverId`s are persisted only after a successful push.** Do not assign them up front: an item with a `serverId` means “confirmed on the server”.
- **Last-write-wins:** repository mutations stamp `product.updatedAt = System.currentTimeMillis()`. `applyRemoteChanges` skips a remote product when `remote.updatedAt < local.updatedAt`.
- **Firestore offline persistence is enabled** in `SyncModule`; queued Firestore writes survive app restarts. There is no custom local write queue.
- **`SyncStatus`** is exposed as a `Flow` from the repository. UI turns sync errors into a discreet banner.
- **Sign-out:** `clearLocalData()` deletes synchronized products only (`serverId != null`) and keeps local-only data for migration on the next login.
- **Remote item application replaces all local items** for the product. This is intentional: keeping local unsynced-looking items while Firestore offline writes are replayed has caused duplicate items in previous attempts.
- **Duplicate remote names converge deterministically:** remote documents with the same name collapse into one local row; the lexicographically smallest `serverId` wins and items are unioned by remote item id.

The sync code is delicate. Before changing it, read `DefaultProductRepositoryTest` and any local notes under `docs/pending-plans/` if present. Some pending design notes are intentionally local/private and may not be committed.

## Other data sources

- **Auth:** `AuthRepository` exposes the app-owned `User` model from `data/auth/User.kt`; UI code must never depend on `FirebaseUser`.
- **Preferences:** `OnboardingRepository` uses Preferences DataStore. Navigation should not read `SharedPreferences` or Firebase directly.
- **ML Kit:** `data/mlkit/` wraps text recognition and barcode scanning behind app interfaces. ViewModels/composables should depend on those abstractions, not on ML Kit SDK classes.
- **CameraX:** camera preview, analysis, and capture live in the UI layer because they are rendering/capture concerns. UI code passes `Bitmap` or `android.media.Image` to the data-source abstractions.

## Resources and localization

- Default resources live in `app/src/main/res/values/strings.xml` and are in English.
- Spanish translations live in `app/src/main/res/values-es/strings.xml`.
- Keep visible strings in resources; do not add new hardcoded UI text.
- When adding a new visible string, add the default English value and update Spanish translations when appropriate.
- Some older UI code may still contain hardcoded literals. Prefer moving them to resources when touching that area.

Useful resource:

- `res/drawable/ic_barcode.xml` — custom vector drawable for barcode scan affordance.

## Testing

- Add tests with every feature or behavioral change.
- JVM tests live in `app/src/test/`.
- Instrumented tests live in `app/src/androidTest/` and require an Android device/emulator.
- Prefer fakes over mocks.
- Fakes should implement the full interface they stand in for.
- Fakes that back observable state should use `MutableStateFlow` so tests stay reactive.
- For ViewModel tests, assert on `uiState.value`; avoid fragile `.first()` / long-lived collection patterns unless specifically needed.
- Test names should follow: `subject_condition_expectedResult`.

Existing test areas include:

- `ProductViewModelTest`
- `ScanViewModelTest`
- `OcrExtractorTest`
- `AuthViewModelTest`
- `DefaultProductRepositoryTest`
- `ProductDaoTest`
- `BarcodeRepositoryImplTest`
- `ProductScreenTest`
- `NavigationTest`
- `MigrationTest`
- `FirebaseFirestoreSyncRepositoryTest`
- Firebase rules tests in `firebase/rules.test.js`

### Firebase emulators

Instrumented tests do not need manual emulator setup. The `FirebaseEmulators` Gradle build service in `app/build.gradle.kts` starts Auth and Firestore emulators before `connectedDebugAndroidTest`, waits for ports `9099` and `8080`, and stops only the processes it started.

```bash
./gradlew :app:connectedDebugAndroidTest
cd firebase && npm test
cd firebase && npm run emulators
```

If emulators are already running, Gradle reuses them and does not stop them after the build.

The Firebase CLI is installed as a dev dependency in `firebase/`, so run Firebase commands from that directory.

Firestore instrumented tests share `FirebaseEmulatorSession`: each test opens its own named `FirebaseApp`, signs in a fresh anonymous (or email) user, terminates Firestore and deletes the app in teardown. Do not reintroduce a shared `@BeforeClass` client or cached user; that leaked gRPC channels across suites and let one stalled operation hang the whole run. `firebaseEmulatorTest` bounds every setup, test and teardown at 30s and dumps thread stacks to the `FirebaseEmulatorTest` logcat tag on timeout, so a connection problem fails one test instead of stalling `connectedDebugAndroidTest`. Missing emulators now surface as explicit failures rather than silent skips.

### Compose instrumented tests

Compose tests should set content from the test using `HiltTestActivity` (`src/debug`) instead of launching `MainActivity` directly. `MainActivity` owns its own `setContent`, which can leave the test rule without a registered Compose tree.

`HiltTestActivity` exists because `MainNavigation()` uses `hiltViewModel()` and needs a Hilt-enabled `ComponentActivity`.

### ML Kit and JVM tests

ML Kit is behind data-source abstractions (`data/mlkit/`). ViewModel tests should use fakes such as `FakeTextRecognizer` and `FakeBarcodeImageScanner`.

`unitTests.isReturnDefaultValues = true` lets Android framework calls such as `android.util.Log` return defaults in JVM tests instead of throwing.

### Room migration tests

Room schemas are exported to `app/schemas/` through `room.schemaLocation` and exposed to `androidTest` assets. `MigrationTest` uses Room’s `MigrationTestHelper` and requires an emulator/device.

## CI and release

- `ci.yml` runs on pushes to `develop` and on pull requests.
- CI has three main jobs: unit tests + lint, Firestore rules tests, and instrumented tests on an Android emulator.
- `release.yml` runs on pushes to `main` and through manual dispatch.
- Release tests must pass before publishing.
- Releases use conventional commits via `.version.yml`, build a signed APK, deploy Firestore rules, and attach the APK to the GitHub Release.
- Play Store publishing is intentionally disabled.

Required GitHub configuration includes:

| Name | Type | Purpose |
|---|---|---|
| `GOOGLE_SERVICES_JSON` | Secret | base64 release `google-services.json` for `dev.pcha.foodsense.app` |
| `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | Secrets | APK signing |
| `FIREBASE_PROJECT_ID` | Repository variable | Firebase project for rules deployment |
| `FIREBASE_SERVICE_ACCOUNT` | Secret | service account JSON for deploying Firestore rules |

Notes:

- Release builds write `app/src/release/google-services.json` from the secret.
- Debug builds use `applicationIdSuffix = ".dev"`.
- A local release build needs `app/src/release/google-services.json` (which is ignored by Git).
  With that file present, `assembleRelease` produces an unsigned APK when release signing
  environment variables are absent.

## Do not do this

- Do not add features beyond the requested scope.
- Do not use `AndroidViewModel`; use Hilt-injected `ViewModel`.
- Do not access DAOs directly from ViewModels.
- Do not expose Firebase, Room entity, DTO, DataStore, or ML Kit SDK types above the data layer.
- Do not call `LocalDate.now()` inside entities; pass timestamps/dates from callers.
- Do not move `DateScanSheet` processing state into `ProductUiState`; keep it local to the composable.
- Do not use fully qualified `androidx.compose.material3.IconButton` when the symbol is already imported.
- Do not compile hot-path regexes inline inside frequently called functions; move them to a companion object or shared constant.
- Do not change sync reconciliation casually. Verify with repository tests and emulator-backed Firestore tests.
