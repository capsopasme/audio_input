import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
}

// set by CI from the git tag, see .github/workflows/build.yml
val appVersionName: String = providers.gradleProperty("appVersionName").getOrElse("1.0.0-dev")
val appVersionCode: Int = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()

// Release signing comes from the environment (CI decodes the keystore secret into a file).
// Without it, release builds are signed with the debug key so that local builds stay installable.
val signKeyFile: String? = System.getenv("SIGN_KEY_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.capsopasme.audioinput"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.capsopasme.audioinput"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // the speech recognizer's native libraries (jniLibs) exist for arm64-v8a only
    }

    signingConfigs {
        create("release") {
            if (signKeyFile != null) {
                storeFile = file(signKeyFile)
                keyAlias = System.getenv("SIGN_KEY_ALIAS")
                keyPassword = System.getenv("SIGN_KEY_PWD")
                storePassword = System.getenv("SIGN_STORE_PWD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("SIGN_KEY_PWD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName(if (signKeyFile != null) "release" else "debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Extract native libraries to nativeLibraryDir on install. Required for the NPU:
            // ADSP_LIBRARY_PATH must point at a real directory holding libQnnHtpV75Skel.so,
            // which the DSP loads by path (error 1008 otherwise).
            useLegacyPackaging = true
            // QNN libraries are shipped as Qualcomm built them; the Skel library is a Hexagon
            // (DSP) binary that the NDK's strip must never touch.
            keepDebugSymbols += setOf("**/libQnn*.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/*.version",
                "/META-INF/androidx/**",
                "/DebugProbesKt.bin",
                "/kotlin-tooling-metadata.json",
            )
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.material)
}
