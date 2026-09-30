plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

// CI では Secrets から復元した共通のデバッグ keystore で署名し、上書きインストールできるようにする。
// ローカルでは環境変数が残っていても常にローカルの debug keystore を使う
val isCi = System.getenv("CI") == "true"
val ciDebugKeystoreFile = System.getenv("DEBUG_KEYSTORE_PATH")?.takeIf { isCi }?.let { file(it) }
val useCiDebugKeystore = ciDebugKeystoreFile != null && ciDebugKeystoreFile.exists() && ciDebugKeystoreFile.length() > 0

android {
    namespace = "net.matsudamper.amazonphotopicker"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "net.matsudamper.amazonphotopicker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GeckoView は ABI ごとにネイティブライブラリが大きいため、CI では配布・テスト対象の ABI に絞る
        val ciAbiFilter = System.getenv("CI_ABI_FILTER")
        if (ciAbiFilter != null) {
            ndk {
                abiFilters += ciAbiFilter.split(",").map { it.trim() }
            }
        }
    }

    signingConfigs {
        if (useCiDebugKeystore) {
            create("debugCi") {
                storeFile = ciDebugKeystoreFile
                storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            if (useCiDebugKeystore) {
                signingConfig = signingConfigs.getByName("debugCi")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (useCiDebugKeystore) {
                signingConfigs.getByName("debugCi")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Paparazzi のスナップショットは通常のユニットテストと分け、Paparazzi のタスク実行時だけ動かす
tasks.withType<Test>().configureEach {
    val hasPaparazziTask = gradle.startParameter.taskNames.any {
        it.lowercase().contains("paparazzi")
    }
    useJUnit {
        if (hasPaparazziTask) {
            includeCategories("net.matsudamper.amazonphotopicker.PaparazziTestCategory")
        } else {
            excludeCategories("net.matsudamper.amazonphotopicker.PaparazziTestCategory")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.coil.compose)
    implementation(libs.zoomable)
    implementation(libs.mozilla.geckoview)
    implementation(libs.androidx.swiperefreshlayout)
    debugImplementation(libs.androidx.compose.ui.tooling)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.composable.preview.scanner)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
