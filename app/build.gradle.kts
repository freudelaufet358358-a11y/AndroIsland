plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.ryunosuke.island"
    compileSdk = 37
    compileSdkMinor = 2
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.ryunosuke.island"
        // setTouchableRegion と showSystemOutputSwitcher が 34 から
        minSdk = 34
        targetSdk = 37
        versionCode = 2
        // GitHub Actions のビルドには番号を付ける（どの APK を入れたか、アプリ情報で分かるように）
        versionName = "1.1" + (providers.gradleProperty("islandBuild").orNull?.let { " (build $it)" } ?: "")
    }

    signingConfigs {
        // GitHub Actions では Secrets の鍵で署名する（毎回同じ鍵にして、前の APK に上書きで入れられるように）。
        // パスワードと別名を省くと debug 鍵の既定値（~/.android/debug.keystore をそのまま Secrets に入れた場合）
        providers.environmentVariable("ISLAND_KEYSTORE").orNull?.let { path ->
            fun env(name: String, default: String) =
                providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() } ?: default
            create("island") {
                storeFile = file(path)
                storePassword = env("ISLAND_KEYSTORE_PASSWORD", "android")
                keyAlias = env("ISLAND_KEY_ALIAS", "androiddebugkey")
                keyPassword = env("ISLAND_KEY_PASSWORD", "android")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 自分の端末に入れるだけなので debug 鍵で署名する（GitHub Actions では上の鍵）
            signingConfig = signingConfigs.findByName("island") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        localeFilters += listOf("ja")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)

    // adb shell と同じ権限でシステムを呼ぶ（省電力の直接の切り替え・許可の一括付与・最近のタスク）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
