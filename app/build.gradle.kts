plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.mclauncher"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.example.mclauncher"
        minSdk = 29                 // Vulkan 1.1 + AHardwareBuffer + external memory
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        ndk { abiFilters += listOf("arm64-v8a") }   // OpenJDK/Mesa solo aarch64

        // Client ID de tu app registrada en Azure (Entra ID). NO lo inventes:
        // regístrala en portal.azure.com y solicita acceso a la API de Minecraft.
        buildConfigField("String", "MSA_CLIENT_ID", "\"${project.findProperty("MSA_CLIENT_ID") ?: ""}\"")
        // Modrinth exige un User-Agent identificable (usuario/proyecto/versión).
        buildConfigField("String", "MODRINTH_UA", "\"tu-usuario-github/mclauncher/0.1.0\"")

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fno-exceptions", "-O2")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_PLATFORM=android-29")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Las .so deben existir en disco (nativeLibraryDir) para que Mesa/LWJGL/HotSpot
    // puedan resolverlas por ruta con dlopen().
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Los runtimes .tar.xz no deben recomprimirse.
    androidResources { noCompress += listOf("xz", "tar") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // HTTP: Ktor (Mojang CDN, Modrinth v2, Microsoft/Xbox/Minecraft services)
    val ktor = "3.2.3"
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-logging:$ktor")

    // Extracción de runtimes OpenJDK (.tar.xz) preservando permisos/symlinks
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")

    // UI
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
}
