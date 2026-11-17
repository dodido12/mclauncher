// Motor de juego "Zalith/Pojav": libpojavexec (código nativo de ZalithLauncher, GPLv3),
// su GLFW en Java (lwjgl-glfw-classes.jar), LWJGL 3.3.6 para Android y Mesa OSMesa/Zink.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.example.mclauncher.zalith"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            ndkBuild {
                arguments += listOf("APP_PLATFORM=android-29")
            }
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/main/jni/Android.mk") }
    }

    buildFeatures { prefab = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Las .so de assets/zalith/natives se extraen tal cual al dispositivo
    androidResources { noCompress += listOf("so", "jar") }
}

dependencies {
    // Hook de exit() que usa libexithook (igual que ZalithLauncher)
    implementation("com.bytedance:bytehook:1.0.10")
    implementation("androidx.annotation:annotation:1.9.1")
}
