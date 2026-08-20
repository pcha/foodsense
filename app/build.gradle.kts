/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

android {
    namespace = "dev.pcha.foodsense.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pcha.foodsense.app"
        minSdk = 26
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "1.0"

        testInstrumentationRunner = "dev.pcha.foodsense.app.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }


    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Expose exported Room schemas to MigrationTestHelper (androidTest).
    sourceSets["androidTest"].assets.directories.add("$projectDir/schemas")

    testOptions {
        // Return defaults (e.g. android.util.Log) instead of throwing in JVM unit tests.
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged resources/manifest to boot a real Room database on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // lifecycle-viewmodel-compose baja serialization-core a 1.7.3, y androidTest hereda esa
    // versión por la resolución consistente de AGP. El MigrationTestHelper de Room 2.8.4 está
    // compilado contra la API 1.8.x y en runtime tira AbstractMethodError.
    implementation(platform(libs.kotlinx.serialization.bom))
    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Hilt Dependency Injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Hilt and instrumented tests.
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    // Hilt and Robolectric tests.
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real Room on the JVM: the hand-written DAO fakes drift from SQLite (they don't cascade).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests: jUnit rules and runners

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // WorkManager + Hilt Workers
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // CameraX
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ML Kit
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.barcode.scanning)

    // Coroutines Play Services (Task.await())
    implementation(libs.kotlinx.coroutines.play.services)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)

    // Image loading
    implementation(libs.coil.compose)

    // Credential Manager (Google Sign-In)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id.credential)
}

// ---------------------------------------------------------------------------------------------
// Emuladores de Firebase para los tests instrumentados.
//
// Los tests corren en el dispositivo Android y los emuladores son procesos del host, así que la
// suite no puede levantarlos por sí sola. Gradle sí. Un BuildService es la pieza indicada: su
// close() corre al final del build y es compatible con la configuration cache, a diferencia de
// guardarse un Process en una acción de tarea.
//
// Si los puertos ya están ocupados asume que alguien los levantó a mano (`npm run emulators`) y
// no los toca ni los baja.
// ---------------------------------------------------------------------------------------------

abstract class FirebaseEmulators : BuildService<FirebaseEmulators.Params>, AutoCloseable {

    interface Params : BuildServiceParameters {
        val firebaseDir: DirectoryProperty
        val logFile: RegularFileProperty
    }

    /** null = ya estaban corriendo, no somos dueños del proceso. */
    private val process: Process?

    init {
        process = if (portsOpen()) {
            logger.lifecycle("Emuladores de Firebase ya corriendo; se usan tal cual.")
            null
        } else {
            start()
        }
    }

    private fun start(): Process {
        val log = parameters.logFile.get().asFile
        log.parentFile.mkdirs()
        logger.lifecycle("Levantando emuladores de Firebase (log: ${log.path})")
        val started = ProcessBuilder("npx", "firebase", "emulators:start", "--only", "firestore,auth")
            .directory(parameters.firebaseDir.get().asFile)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()

        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (portsOpen()) return started
            if (!started.isAlive) error("Los emuladores de Firebase terminaron al arrancar. Ver ${log.path}")
            Thread.sleep(POLL_MS)
        }
        started.destroyForcibly()
        error("Los emuladores de Firebase no estuvieron listos en ${STARTUP_TIMEOUT_MS / 1000}s. Ver ${log.path}")
    }

    override fun close() {
        val owned = process ?: return
        logger.lifecycle("Bajando emuladores de Firebase.")
        // firebase-tools deja procesos hijos (el jar de Firestore); destruir sólo el padre los orfana.
        owned.descendants().forEach { it.destroy() }
        owned.destroy()
        if (!owned.waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            owned.descendants().forEach { it.destroyForcibly() }
            owned.destroyForcibly()
        }
    }

    private fun portsOpen() = PORTS.all { port ->
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), PROBE_MS) }
        }.isSuccess
    }

    private companion object {
        val logger = org.gradle.api.logging.Logging.getLogger(FirebaseEmulators::class.java)
        val PORTS = listOf(8080, 9099)
        const val STARTUP_TIMEOUT_MS = 120_000L
        const val POLL_MS = 500L
        const val SHUTDOWN_TIMEOUT_MS = 15_000L
        const val PROBE_MS = 500
    }
}

val firebaseEmulators = gradle.sharedServices.registerIfAbsent("firebaseEmulators", FirebaseEmulators::class) {
    parameters.firebaseDir.set(rootProject.layout.projectDirectory.dir("firebase"))
    parameters.logFile.set(layout.buildDirectory.file("firebase-emulators.log"))
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    // El provider se captura en un local: si el lambda referenciara la propiedad del script,
    // arrastraría el objeto del script entero y la configuration cache no puede serializarlo.
    val emulators = firebaseEmulators
    usesService(emulators)
    doFirst { emulators.get() }
}
