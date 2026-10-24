import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 同じソースから Android アプリと Web アプリ (WebAssembly) を作る。
// 共通のコードは src/commonMain、Android だけのコードは src/androidMain、Web だけのコードは src/wasmJsMain
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "obdmeter.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
        }
        androidMain.dependencies {
            // ストアを通さずに入れた APK でも、ライブラリの起動用プロファイルを事前コンパイルさせる (起動が速くなる)
            implementation("androidx.profileinstaller:profileinstaller:1.4.1")
            implementation("androidx.core:core-ktx:1.16.0")
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.lifecycle:lifecycle-service:2.9.0")
            implementation("androidx.datastore:datastore-preferences:1.1.7")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
            // FTDI / CP210x / CH34x などの USB シリアル変換を Android から扱うライブラリ
            implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
        }
    }
}

android {
    namespace = "com.obdmeter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.obdmeter"
        minSdk = 26
        targetSdk = 35
        // GitHub Actions の実行番号をバージョンにする (ローカルビルドは 1)
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "0.19.$runNumber"
    }

    // どこでビルドしても同じ鍵で署名する。
    // (GitHub Actions は毎回新しいデバッグ鍵を作るため、鍵を固定しないと上書きインストールできない)
    // この鍵は開発用で秘密情報ではない。Play ストア配布には別の鍵を使うこと。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        // 普段使う版。コードを最適化・縮小して起動と描画を速くする (署名はデバッグ版と同じ鍵なので上書きできる)
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
