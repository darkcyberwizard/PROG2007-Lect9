# RouteMapper — PROG2007 Lecture 9: Background Work

A small Android app that powers four live demos for **PROG2007 Lecture 9: Background Work** (NTNU). It shows how an app keeps working when the user isn't looking at it: foreground services, WorkManager, and REST networking.

> **This repository contains only `MainActivity.kt`.** It is not a complete Android Studio project. To run the app, create a new project and add the dependencies, permissions and service declaration described in [Setup](#setup-build-the-project-from-scratch).

## The story

A student does a part-time delivery shift by bike with RouteMapper.

| Time  | What happens                                                                  | Tool                   |
|-------|-------------------------------------------------------------------------------|------------------------|
| 09:00 | The route is recorded all morning, with the phone in a pocket                 | Foreground service     |
| 12:30 | Lunch break. The phone sits on a table; delivery notes need to reach a server | WorkManager + Retrofit |
| 17:00 | The shift ends. Is the whole route there?                                     | Room + Repository      |

The lecture question: *at 17:00, will the whole route be there?*

## What the app does

The app opens on a start screen (**Start shift**), then has three tabs:

- **Tracking** — records the route on a Google Map. Two modes:
  - *Track (no service)*: location updates run from the screen only. When the app goes to the background, Android stops delivering them and the route gets a gap.
  - *Track (foreground service)*: a foreground service of type `location` keeps recording, with a visible notification and a Stop button.
- **Work** — add delivery notes (saved in Room, with the last route position) and upload them with a WorkManager job that runs only when there is a network and the battery is not low. It also has a coroutine timer to show how timers are delayed under Doze.
- **Network** — fetch notes from a REST API through the Repository, with buttons that break the request on purpose (bad path, plain `http://`).

## Live demos

| Demo | Where | What it shows |
|------|-------|---------------|
| 1. The route survives the pocket | Tracking tab | Without a service the route has a gap; with a foreground service it is continuous. A demo switch starts the service without the permission check to show the `SecurityException`. |
| 2. See what Doze does | Work tab | A coroutine timer ticks every 10 seconds. **Simulate Doze** holds the ticks back and **Maintenance window** releases them at once. |
| 3. WorkManager waits for good conditions | Work tab | With Airplane mode on, the upload stays `ENQUEUED`. When the network returns, it runs and reaches `SUCCEEDED`. |
| 4. The Repository layer | Network tab | Offline, bad path (HTTP 404) and cleartext (`http://`) errors are all handled as one `Result`; cached notes stay visible. |

**Simulate Doze** and the permission switch *simulate* the effect so every demo works from buttons alone. Real Doze needs the phone to be unplugged, still and idle for a long time.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Foreground service (`location` type) and the Fused Location Provider
- WorkManager (`CoroutineWorker`, `Constraints`, unique work)
- Retrofit and kotlinx.serialization, the Repository pattern, `Result`
- Room (local source of truth)
- Google Maps SDK (map only) through Maps Compose
- REST API: [JSONPlaceholder](https://jsonplaceholder.typicode.com/), a free fake API that accepts POSTs but saves nothing

## Requirements

- A recent Android Studio with AGP 9 support
- An emulator or phone running Android 10 (API 29) or newer. To match the lecture slides, use an Android 15 or 16 image **with Google Play**, which the map and location need.
- A Google Maps API key
- Internet access (the app calls JSONPlaceholder)

## Setup: build the project from scratch

### 1. Create the project

In Android Studio: **New Project → Empty Activity** (the Jetpack Compose one) with these settings:

| Setting | Value |
|---------|-------|
| Name | `Lect9BackgroundWork` |
| Package name | `com.example.lect9backgroundwork` |
| Minimum SDK | API 29 |
| Build configuration language | Kotlin DSL (`build.gradle.kts`) |

The package name must match, because `MainActivity.kt` and the service declaration refer to it.

### 2. Add the code

Replace the generated `app/src/main/java/com/example/lect9backgroundwork/MainActivity.kt` with the `MainActivity.kt` from this repository. It holds the whole app: models, Room, Retrofit, Repository, WorkManager, the foreground service, the ViewModel and the UI.

### 3. Configure Gradle

The project uses **AGP 9 with built-in Kotlin**. Do not apply the `kotlin-android` plugin. KSP must be version 2.3.6 or newer.

**`gradle/libs.versions.toml`** — add this line under `[plugins]`:

```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

If your file has no `kotlin` entry under `[versions]`, use your Kotlin version number instead of `version.ref`.

**Root `build.gradle.kts`** — add these two lines inside `plugins { }`:

```kotlin
alias(libs.plugins.kotlin.serialization) apply false
id("com.google.devtools.ksp") version "2.3.10" apply false
```

**`app/build.gradle.kts`** — add these two lines inside `plugins { }`:

```kotlin
alias(libs.plugins.kotlin.serialization)
id("com.google.devtools.ksp")
```

Check that `minSdk = 29` is set under `defaultConfig`, then add these to `dependencies { }`:

```kotlin
// Room (KSP generates the database code)
implementation("androidx.room:room-runtime:2.8.4")
implementation("androidx.room:room-ktx:2.8.4")
ksp("androidx.room:room-compiler:2.8.4")

// WorkManager
implementation("androidx.work:work-runtime:2.12.0")

// Retrofit + JSON
implementation("com.squareup.retrofit2:retrofit:3.0.0")
implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

// Location and Maps
implementation("com.google.android.gms:play-services-location:21.3.0")
implementation("com.google.android.gms:play-services-maps:19.2.0")
implementation("com.google.maps.android:maps-compose:6.12.2")
```

The Compose, activity and lifecycle libraries already come with the Android Studio template.

| Library | Version |
|---------|---------|
| androidx.work:work-runtime | 2.12.0 |
| androidx.room (runtime, ktx, compiler) | 2.8.4 |
| com.squareup.retrofit2 (retrofit, converter-kotlinx-serialization) | 3.0.0 |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.9.0 |
| com.google.android.gms:play-services-location | 21.3.0 |
| com.google.android.gms:play-services-maps | 19.2.0 |
| com.google.maps.android:maps-compose | 6.12.2 |
| KSP plugin (`com.google.devtools.ksp`) | 2.3.10 |

### 4. Edit the manifest

Open `app/src/main/AndroidManifest.xml`.

**Permissions** — add these above `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

**Maps key and service** — add these inside `<application>`, next to the existing `<activity>`:

```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="YOUR_API_KEY" />

<service
    android:name=".RouteTrackingService"
    android:foregroundServiceType="location"
    android:exported="false" />
```

Both must be inside `<application>`, or the app can crash when the map opens.

### 5. Get a Maps API key

1. In the [Google Cloud Console](https://console.cloud.google.com/), create a project and enable **Maps SDK for Android**.
2. Create an API key and restrict it to the package `com.example.lect9backgroundwork` and your debug SHA-1.
3. Put the key in the manifest in place of `YOUR_API_KEY`.

Never commit your real key to a public repository.

### 6. Rename the app (optional)

In `app/src/main/res/values/strings.xml`:

```xml
<string name="app_name">RouteMapper</string>
```

### 7. Sync and run

Click **Sync Project with Gradle Files**, then run the app on an emulator or phone. Allow location and notification permissions when asked.

## Emulator tips

- **Fake a route:** Emulator → Extended Controls (⋮) → Location → Routes. Pick a route, set a walking speed, then play it.
- **Airplane mode:** swipe down from the top of the emulator screen and tap the Airplane mode icon (Demos 3 and 4).
- **Logcat filters:** `tag:RouteService`, `tag:TimerDemo`, `tag:SyncWorker`, `tag:NotesRepository`.
- Background location behaviour differs between emulator images. Rehearse Demo 1 once, or use a real phone.

## Troubleshooting

| Problem | Likely cause |
|---------|--------------|
| `Unresolved reference 'room'` | The Room dependencies or the KSP plugin were not added, or Gradle was not synced. |
| The map is blank, or the app crashes when the map opens | The Maps key is missing, wrong, or placed outside `<application>`; or the emulator image has no Google Play services. |
| `SecurityException` when tracking starts | The foreground service permission or type is missing in the manifest. (The demo switch causes this on purpose.) |
| Fetch always fails | No internet, or JSONPlaceholder is unreachable. |

## Project structure

Everything is in one file, `MainActivity.kt`, organised in numbered sections:

1. Models: DTOs, our own `Note` model, Room entities and mappers
2. Room: DAOs and the database
3. Network: Retrofit API and `NetworkModule`
4. Repository: `NotesRepository` and `ServiceLocator`
5. WorkManager: `SyncWorker` and `WorkScheduler`
6. Foreground service: `RouteTrackingService`
7. ViewModel: `MainViewModel`
8. UI: start screen, status strip and the three tabs

## Known limitations

- JSONPlaceholder is a fake API: uploaded notes are accepted but never stored, so they don't appear when you fetch.
- Only the delivery notes are uploaded. The route points stay in Room on the phone.
- The demo switches (Simulate Doze, skip the permission check) are for teaching and should not be copied into a real app.

