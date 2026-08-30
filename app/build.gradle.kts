plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "si.plahutar.karooarsoradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "si.plahutar.karooarsoradar"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // Za lastno uporabo je podpis z debug kljucem najlazji.
            // Za objavo v Extension Library si naredi pravi keystore (glej README).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.9")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // samostojni GIF dekoder iz Glide - rabimo ga, da iz animacije izlusci ZADNJO sliko
    implementation("com.github.bumptech.glide:gifdecoder:4.16.0")
}
