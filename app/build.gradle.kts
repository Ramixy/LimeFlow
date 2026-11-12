import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties in the project root (not in git).
// Without it the release build is left unsigned and CI keeps building debug APKs.
val releaseSigning = Properties()
rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { releaseSigning.load(it) }

android {
    signingConfigs {
        if (releaseSigning.containsKey("storeFile")) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    namespace = "io.github.dovecoteescapee.byedpi"
    compileSdk = 34
    ndkVersion = "25.1.8937393"

    defaultConfig {
        applicationId = "app.alt11.mobile"
        minSdk = 23
        targetSdk = 34
        versionCode = 38
        versionName = "1.6.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("armeabi-v7a")
            abiFilters.add("arm64-v8a")
        }
    }

    // Р Р°Р·РґРµР»СЊРЅС‹Рµ APK РїРѕРґ Р°СЂС…РёС‚РµРєС‚СѓСЂС‹ + universal: РјРµРЅСЊС€Рµ СЂР°Р·РјРµСЂ, РјРµРЅСЊС€Рµ Р±Р°РіРѕРІ
    // РѕС‚ РЅРµСЃРѕРІРїР°РґР°СЋС‰РёС… РЅР°С‚РёРІРЅС‹С… Р±РёР±Р»РёРѕС‚РµРє. x86/x86_64 РЅРµ РІС‹РїСѓСЃРєР°СЋС‚СЃСЏ: РґР»СЏ РЅРёС…
    // РЅРµС‚ libtgwsproxy.so вЂ” СЂР°Р·РґРµР» Proxy РїР°РґР°Р» Р±С‹ РЅР° СЌРјСѓР»СЏС‚РѕСЂР°С….
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")

            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":tgwsproxy"))
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.takisoft.preferencex:preferencex:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    // Р•РґРёРЅСЃС‚РІРµРЅРЅР°СЏ РЅРѕРІР°СЏ Р·Р°РІРёСЃРёРјРѕСЃС‚СЊ: РіРµРЅРµСЂР°С†РёСЏ QR-РєРѕРґРѕРІ РґР»СЏ С€Р°СЂРёРЅРіР° СЃС‚СЂР°С‚РµРіРёР№
    // (С‚РѕР»СЊРєРѕ core, Р±РµР· camera/camera2).
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        // Р’С‹РІРѕРґРёРј РІ build/, Р° РЅРµ РІ src/main/jniLibs: ndk-build С‡РёСЃС‚РёС‚ РєР°С‚Р°Р»РѕРі РІС‹РІРѕРґР°.
        "NDK_LIBS_OUT=build/intermediates/ndkBuild/hev-libs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    ))

    println("Command: $commandLine")
}

android {
    sourceSets {
        getByName("main") {
            // libhev-socks5-tunnel.so СЃРѕР±РёСЂР°РµС‚СЃСЏ ndk-build РІ build/.
            jniLibs.srcDir("build/intermediates/ndkBuild/hev-libs")
        }
    }
}

tasks.preBuild {
    dependsOn("runNdkBuild")
}
