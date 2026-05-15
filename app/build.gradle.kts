plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.allard.regexphone"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.allard.regexphone"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"
    }

    signingConfigs {
        create("release") {
            val path = providers.gradleProperty("REGEXPHONE_KEYSTORE_PATH").orNull
            if (path != null) {
                storeFile = file(path)
                storePassword = providers.gradleProperty("REGEXPHONE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("REGEXPHONE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("REGEXPHONE_KEY_PASSWORD").orNull
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
