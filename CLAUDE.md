# FoodSense

Android app to manage food items in the pantry and fridge, tracking expiration dates.

## Project info

- **Package:** `com.github.pcha.foodsense.app`
- **Min SDK:** 26
- **Target SDK:** 36

## Tech stack

- Jetpack Compose + Material3
- Hilt (dependency injection)
- Room (local database)
- Navigation 3
- Kotlin Coroutines + Flow
- Single-module (`base` template)

## Domain

The core entity is `Product` — a food item stored in the pantry or fridge with an expiration date.

Key concepts to keep in mind:
- Products can be in different storage locations (pantry, fridge, freezer)
- Expiration date is the central piece of data — drives sorting, alerts, and status
- A product can be "expired", "expiring soon", or "ok"

## Architecture

Follows the Android layered architecture (UI → Domain → Data):
- UI layer: Compose screens + ViewModels
- Data layer: Room repository (`ProductRepository`)
- No domain layer yet — add use cases only if ViewModel logic becomes complex or shared

## Testing

- Always write tests alongside every feature — no feature is complete without them
- Unit tests go in `src/test/` (JVM, no emulator needed)
- Instrumented tests go in `src/androidTest/` (require device/emulator)
- Prefer fakes over mocks — fakes implement the real interface with in-memory logic
- Fakes use `MutableStateFlow` so tests can emit values reactively
- Assert on `uiState.value` — not `.first()` or `.collect()`
- Name tests as `subject_condition_expectedResult`
- Each fake must fully implement its interface including all methods

## What NOT to do

- Don't add features beyond what's asked
- Don't use `AndroidViewModel` — use `ViewModel` with Hilt
- Don't access Room DAOs directly from ViewModels — always go through the repository
- Don't use `time.Now()` equivalents inside entities — pass timestamps from the caller
