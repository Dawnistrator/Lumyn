plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lumyn.core.designsystem"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:model"))
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)

    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.coil.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.materialkolor.color.utilities)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
