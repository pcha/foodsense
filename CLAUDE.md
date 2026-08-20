# FoodSense

Android app to manage food items in the pantry and fridge, tracking expiration dates.

## Skills disponibles

Antes de implementar cualquier cosa, consultá estas skills del proyecto:

- `/android_architecture_principles` — cuatro principios no negociables de arquitectura
- `/android_layers` — stack de tres capas (UI → Domain → Data)
- `/android_ui_layer` — UDF, ViewModel, UiState, Compose
- `/android_viewmodel` — reglas del ViewModel (lifecycle, coroutines, state)
- `/android_data_layer` — estructura de repositorios, fuentes de datos
- `/android_di_testing` — Hilt, fakes, unit tests

## Project info

- **Package:** `dev.pcha.foodsense.app`
- **Min SDK:** 26
- **Target SDK:** 36

## Tech stack

- Jetpack Compose + Material3
- Hilt (dependency injection)
- Room (local database, actualmente en schema versión 7)
- Firebase Auth (Google + email/password) + Cloud Firestore (sync multi-dispositivo)
- WorkManager (`@HiltWorker` + `HiltWorkerFactory`) — notificaciones de vencimiento y sync periódico
- Navigation 3
- CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`)
- ML Kit Text Recognition (`play-services-mlkit-text-recognition`)
- ML Kit Barcode Scanning (`play-services-mlkit-barcode-scanning`)
- Kotlin Coroutines + Flow
- Single-module (`base` template)

## Dominio

La entidad central es `Product` — un alimento en la despensa o heladera con fecha de vencimiento.

Conceptos clave:
- La fecha de vencimiento es el dato central: determina el ordenamiento, alertas y estado del producto
- Un producto puede estar en estado: expirado, por vencer pronto, o bien
- Los productos tienen ítems individuales (misma cantidad/unidad/fecha se agrupan en la UI)
- Los ítems se pueden agregar en lote (batch) o editar en grupo

## Arquitectura

Sigue la arquitectura por capas de Android (UI → Data):
- **UI layer:** Compose screens + ViewModels con UiState como data class
- **Data layer:** repositorios Room (`ProductRepository`) + repositorios externos (`BarcodeRepository`)
- Sin capa de dominio por ahora — agregar use cases sólo si la lógica del ViewModel se vuelve compleja o compartida

## Estructura de pantallas

```
ui/
├── Navigation.kt           — NavDisplay con back stack
├── NavigationKeys.kt       — destinos tipados (Main, Scan)
├── product/
│   ├── ProductScreen.kt    — pantalla principal con lista y AddProductForm
│   ├── ProductViewModel.kt — estado de la lista + form + date scanner
│   └── DateScanSheet.kt    — ModalBottomSheet con preview de cámara para escanear fecha
└── scan/
    ├── ScanScreen.kt       — flujo de escaneo (barcode → OCR label)
    ├── ScanViewModel.kt    — lógica del scanner y lookup de barcode
    └── OcrExtractor.kt     — extracción de texto OCR (nombre, cantidad, fecha, barcode)
```

## Flujo de escaneo de productos

1. **Barcode scanner** (fase por defecto): detecta código de barras en tiempo real con `ImageAnalysis`
   - Lookup en `BarcodeRepository` (caché local Room → Open Food Facts API)
   - Si encontrado: muestra `ModalBottomSheet` de confirmación; el usuario acepta o rechaza
   - Si rechazado desde caché: borra la entrada local
   - Si no encontrado: pasa a OCR
2. **OCR de etiqueta**: captura foto con `ImageCapture`, extrae nombre/cantidad/fecha/barcode
   - Si detecta barcode en la imagen: intenta lookup primero
   - Si extrae nombre: retorna al form con datos pre-cargados
3. **Checkbox "Recordar barcode"**: sólo visible cuando hay `pendingBarcode` Y el usuario modifica el nombre pre-cargado (`originalFormName` en UiState)

## Scanner de fecha de vencimiento

- Botón de cámara junto al date picker en `AddProductForm`
- Abre `DateScanSheet`: `ModalBottomSheet` con preview de cámara (CameraX)
- `isProcessing` es estado **local** del composable, no del ViewModel — se activa al capturar, se resetea vía `LaunchedEffect(error)`
- El ViewModel expone sólo `showDateScanner` y `dateScanError` en `ProductUiState`
- `parseDateStr` en `ProductViewModel` maneja:
  - Formatos numéricos: `dd/MM/yyyy`, `dd/MM/yy`, `MM/yyyy` (fin de mes), separadores `.` `-` `/`
  - Nombres de mes: `d MMM yyyy`, `MMM yyyy` (fin de mes) — con fallback locale (dispositivo → EN → ES)
  - Desambiguación dd/mm vs mm/dd por locale del dispositivo (en_US → mes primero)

## Capa de datos — BarcodeRepository

```
data/barcode/
├── BarcodeRepository.kt            — interfaz: lookup / save / delete
├── BarcodeRepositoryImpl.kt        — composición: caché local + Open Food Facts
├── LocalBarcodeRegistry.kt         — Room DAO wrapper (BarcodeEntry, tabla en schema v5)
└── OpenFoodFactsBarcodeRepository  — HTTP a open.fda.gov / openfoodfacts.org
```

Migraciones Room recientes: v5 agrega `barcode_registry`; v6 agrega `serverId` a `product` e `item` (sync); v7 agrega `updatedAt` a `product` (last-write-wins).

## Capa de datos — Sync offline-first (Firestore)

Room es el **SSOT**; la UI lee sólo de Room. Firestore sincroniza en background. Los writes
son optimistas: primero Room, después push a Firestore vía `syncIfAuthenticated` en `appScope`.

```
data/sync/
├── FirestoreSyncRepository.kt         — interfaz + modelos de red (FirestoreProduct/Item)
├── FirebaseFirestoreSyncRepository.kt — impl: upsert / delete / updateItems / listenToChanges (snapshot)
└── SyncWorker.kt                      — reconciliación periódica (fetchAll → applyRemoteChanges)
```

Reglas clave del sync (en `DefaultProductRepository`):
- **Modelos por capa:** `FirestoreProduct` (red) ↔ `ProductEntity` (Room) ↔ `Product` (dominio/UI).
- **`serverId`** en Room mapea la fila local al doc de Firestore; se asigna en Room ANTES de que
  llegue el snapshot para no duplicar.
- **Last-write-wins:** cada mutación sella `product.updatedAt = System.currentTimeMillis()` (desde
  el repo, nunca desde la entidad). `applyRemoteChanges` descarta el remoto si
  `remote.updatedAt < local.updatedAt`, así un edit local no sincronizado no se pisa.
- **Persistencia offline de Firestore** habilitada explícita en `SyncModule` → writes encolados en
  disco (sobreviven cierre de app) + reads desde caché. No hay cola de writes propia.
- **`SyncStatus`** (`Idle/Syncing/Error`) se expone como `Flow` desde el repo → `uiState.syncError`
  → banner discreto en `ProductScreen`. El listener setea `Error` en `.catch` (no lo traga).
- **Logout:** `clearLocalData()` borra sólo lo sincronizado (`deleteSyncedProducts`, `serverId != null`);
  conserva lo local-only, que `migrateLocalDataToFirestore` sube al re-loguear.
- Métodos `internal` (`applyRemoteChanges`, `clearLocalData`) para poder testearlos con fakes.
- **Al aplicar el remoto se reemplazan TODOS los ítems locales**, sin intentar distinguir cuáles ya
  están en Firestore. Con la cola offline, un ítem con `serverId == null` puede estar igual en el
  servidor, y conservarlo duplica la copia que trae el snapshot. Ya se intentó dos veces afinar esto
  y las dos duplicó — ver [docs/pending-plans/stable-sync-ids.md](docs/pending-plans/stable-sync-ids.md)
  antes de volver a tocarlo.

## Planes pendientes

`docs/pending-plans/` guarda propuestas ya analizadas pero sin implementar. Consultarlas antes de
encarar algo de la capa de sync: suelen explicar por qué el código está como está.

## Capa de datos — otros data sources

- **Auth:** `AuthRepository` expone un modelo de dominio propio `User` (`data/auth/User.kt`), NO
  `FirebaseUser`. `FirebaseAuthRepository` mapea `FirebaseUser → User`. La UI/ViewModel nunca ven
  tipos del SDK de Firebase.
- **Preferencias:** `OnboardingRepository` (`data/preferences/`) con **Preferences DataStore**
  guarda el flag de onboarding. `Navigation` no toca `SharedPreferences` ni `FirebaseAuth` directo;
  usa `AuthViewModel` (`currentUser`, `onboardingDone`, `completeOnboarding()`).
- **ML on-device:** `data/mlkit/` — `TextRecognizer` (OCR) y `BarcodeImageScanner` (barcode)
  envuelven ML Kit. Los ViewModels/composables inyectan estas interfaces; **no** importan
  `TextRecognition`/`BarcodeScanning`/`InputImage`. CameraX (preview/analysis/capture) sí vive en
  la UI porque es rendering; los composables pasan `Bitmap`/`android.media.Image` a los data sources.

## Recursos

- `res/drawable/ic_barcode.xml` — vector drawable custom (marcas de esquina + barras verticales)

### Idioma de los strings

`values/strings.xml` está **mezclado**: lo viejo en inglés, y lo de login/cuenta en español porque
se extrajo tal cual de literales hardcodeados. No hay `values-es/`. Antes de agregar traducciones
hay que decidir un idioma para el default y mover el resto.

Todo texto visible va en `strings.xml`, nunca hardcodeado: los tests instrumentados lo referencian
por `R.string.*`, así que renombrar un string rompe la compilación en vez de romper el test en
silencio. Todavía quedan literales en `ScanScreen`, `DateScanSheet` y parte de `ProductScreen`.

## Testing

- Siempre escribir tests junto con cada feature — ninguna feature está completa sin ellos
- Unit tests en `src/test/` (JVM, sin emulador)
- Instrumented tests en `src/androidTest/` (requieren dispositivo/emulador)
- Usar fakes sobre mocks — los fakes implementan la interfaz real con lógica en memoria
- Los fakes usan `MutableStateFlow` para emitir valores reactivamente
- Assertar sobre `uiState.value`, no `.first()` ni `.collect()`
- Nombres de tests: `subject_condition_expectedResult`
- Cada fake debe implementar completamente su interfaz incluyendo todos los métodos

### Emuladores de Firebase

Los tests instrumentados **no necesitan preparación**: el build service `FirebaseEmulators` de
`app/build.gradle.kts` levanta los emuladores antes de `connectedDebugAndroidTest`, espera a que
respondan los puertos 8080 y 9099, y los baja al terminar el build.

```bash
./gradlew :app:connectedDebugAndroidTest   # levanta, corre y baja los emuladores solo
cd firebase && npm test                    # tests de reglas (JS, con su propio emulators:exec)
cd firebase && npm run emulators           # dejarlos corriendo durante el desarrollo
```

Si ya hay emuladores corriendo, el build service los detecta por los puertos, los reusa y **no los
baja** al terminar — no te mata los que levantaste a mano.

El CLI está instalado como devDependency dentro de `firebase/`, así que **hay que correrlo desde
ese directorio** — desde la raíz npx falla con `could not determine executable to run`.

Si los emuladores no están arriba, los 9 tests de Firestore se **saltean** en vez de fallar: un
servicio local apagado no es una regresión, y como falla taparía las que sí importan.

### Tests instrumentados de Compose

Componer el contenido desde el test sobre `HiltTestActivity` (`src/debug`), no lanzar `MainActivity`:
cuando la activity hace su propio `setContent`, el árbol no queda registrado en el test rule y todo
falla con "No compose hierarchies found". `HiltTestActivity` existe porque `MainNavigation()` usa
`hiltViewModel()` y un `ComponentActivity` pelado no tiene inyección. Ver `NavigationTest`.

### ML Kit y tests JVM

ML Kit vive detrás de data sources (`data/mlkit/`), así que los ViewModels se testean con fakes
(`FakeTextRecognizer`, `FakeBarcodeImageScanner`) y ya no aparece `MlKitContext has not been
initialized`. `unitTests.isReturnDefaultValues = true` (en `build.gradle.kts`) hace que
`android.util.Log` devuelva defaults en lugar de tirar excepción en tests JVM.

### Test de migración Room

Los schemas se exportan a `app/schemas/` (`room.schemaLocation`) y se exponen a `androidTest` vía
`sourceSets["androidTest"].assets.srcDir`. `MigrationTest` usa `MigrationTestHelper`
(dep `androidx.room:room-testing`) para validar migraciones (requiere emulador).

## Qué NO hacer

- No agregar features más allá de lo pedido
- No usar `AndroidViewModel` — usar `ViewModel` con Hilt
- No acceder a DAOs de Room directamente desde ViewModels — siempre ir a través del repositorio
- No acceder a SDKs de data source (Firebase, ML Kit, DataStore/`SharedPreferences`) desde
  composables ni ViewModels — envolverlos en un repositorio/data source e inyectar la interfaz
- No exponer tipos del SDK por encima de la capa de datos (`FirebaseUser`, `@Entity` de Room, DTOs
  de red) — mapear a modelos de dominio (p. ej. `User`)
- No llamar a `LocalDate.now()` dentro de entidades — pasar timestamps desde el caller
- No poner `isProcessing` del scanner de fecha en `UiState` — es estado local del composable
- No usar `IconButton` fully-qualified (`androidx.compose.material3.IconButton`) — ya está importado
- No compilar `Regex` inline dentro de funciones que se llaman frecuentemente — mover a companion object
