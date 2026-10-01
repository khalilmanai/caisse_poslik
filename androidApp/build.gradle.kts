plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    kotlin("android")
}

android {
    namespace = "com.poslik.pos.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poslik.pos.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val databaseUrl = project.findProperty("FIREBASE_DATABASE_URL") as String?
            ?: "https://cashregister-24f69-default-rtdb.firebaseio.com"
        buildConfigField("String", "FIREBASE_DATABASE_URL", "\"$databaseUrl\"")
        buildConfigField("String", "STORE_ID", "\"boutique-centre-ville\"")
        buildConfigField("String", "STORE_LABEL", "\"Boutique Centre-ville\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
