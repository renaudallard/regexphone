plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.allard.regexphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.allard.regexphone"
        minSdk = 31
        targetSdk = 36
        versionCode = 17
        versionName = "0.2.2"
    }

    signingConfigs {
        create("release") {
            val path = providers.gradleProperty("REGEXPHONE_KEYSTORE_PATH").orNull
            val keystoreFile = path?.let { file(it) }
            if (keystoreFile != null && keystoreFile.isFile) {
                storeFile = keystoreFile
                storePassword = providers.gradleProperty("REGEXPHONE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("REGEXPHONE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("REGEXPHONE_KEY_PASSWORD").orNull
                enableV2Signing = true
                enableV3Signing = true
            } else if (path != null) {
                logger.warn("REGEXPHONE_KEYSTORE_PATH=$path does not exist; release will be unsigned.")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

// Rename each APK after the version. The legacy applicationVariants API is
// removed in AGP 9, so drive the rename from the new Variant API instead. AGP
// still exposes no public setter for the output file name, so reach the impl
// behind a filterIsInstance guard.
androidComponents {
    onVariants { variant ->
        variant.outputs
            .filterIsInstance<com.android.build.api.variant.impl.VariantOutputImpl>()
            .forEach { output ->
                output.outputFileName.set(output.versionName.map { "regexphone-$it.apk" })
            }
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
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
