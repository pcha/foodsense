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

- **Package:** `com.github.pcha.foodsense.app`
- **Min SDK:** 26
- **Target SDK:** 36

## Tech stack

- Jetpack Compose + Material3
- Hilt (dependency injection)
- Room (local database, actualmente en schema versión 5)
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

Migración Room: la versión 5 agrega la tabla `barcode_registry`.

## Recursos

- `res/drawable/ic_barcode.xml` — vector drawable custom (marcas de esquina + barras verticales)

## Testing

- Siempre escribir tests junto con cada feature — ninguna feature está completa sin ellos
- Unit tests en `src/test/` (JVM, sin emulador)
- Instrumented tests en `src/androidTest/` (requieren dispositivo/emulador)
- Usar fakes sobre mocks — los fakes implementan la interfaz real con lógica en memoria
- Los fakes usan `MutableStateFlow` para emitir valores reactivamente
- Assertar sobre `uiState.value`, no `.first()` ni `.collect()`
- Nombres de tests: `subject_condition_expectedResult`
- Cada fake debe implementar completamente su interfaz incluyendo todos los métodos

### Precaución con ML Kit en tests JVM

`TextRecognition.getClient()` falla en tests JVM con `MlKitContext has not been initialized`.
Usar inicialización lazy en el ViewModel:
```kotlin
private val _recognizer = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
private val recognizer by _recognizer

override fun onCleared() {
    super.onCleared()
    if (_recognizer.isInitialized()) recognizer.close()
}
```

## Qué NO hacer

- No agregar features más allá de lo pedido
- No usar `AndroidViewModel` — usar `ViewModel` con Hilt
- No acceder a DAOs de Room directamente desde ViewModels — siempre ir a través del repositorio
- No llamar a `LocalDate.now()` dentro de entidades — pasar timestamps desde el caller
- No poner `isProcessing` del scanner de fecha en `UiState` — es estado local del composable
- No usar `IconButton` fully-qualified (`androidx.compose.material3.IconButton`) — ya está importado
- No compilar `Regex` inline dentro de funciones que se llaman frecuentemente — mover a companion object
