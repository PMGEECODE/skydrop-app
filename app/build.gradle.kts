plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.famage.skydrop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.famage.skydrop"
        minSdk = 24
        targetSdk = 35
        versionCode = 46
        versionName = "1.0.46"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            val rootKeystore = file("${rootDir}/debug.keystore")
            val homeKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storeFile = when {
                rootKeystore.exists() -> rootKeystore
                homeKeystore.exists() -> homeKeystore
                else -> {
                    if (!rootKeystore.exists()) {
                        rootKeystore.parentFile?.mkdirs()
                        try {
                            ProcessBuilder(
                                "keytool", "-genkeypair", "-v",
                                "-keystore", rootKeystore.absolutePath,
                                "-storepass", "android",
                                "-keypass", "android",
                                "-alias", "androiddebugkey",
                                "-keyalg", "RSA",
                                "-keysize", "2048",
                                "-validity", "10000",
                                "-dname", "CN=Android Debug,O=Android,C=US"
                            ).inheritIO().start().waitFor()
                        } catch (_: Exception) {}
                    }
                    rootKeystore
                }
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSizeClass)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.guava)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}