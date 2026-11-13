import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ch.nthnbch.kalendar"
    compileSdk = 36

    // The OAuth application registered in the Infomaniak Manager. Kept in local.properties (git-ignored).
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    defaultConfig {
        buildConfigField("String", "INFOMANIAK_CLIENT_ID", "\"${local.getProperty("infomaniakClientId", "")}\"")
        // Off by default: Infomaniak only grants third-party OAuth apps openid/profile/email, which cannot create a CalDAV password.
        buildConfigField("boolean", "INFOMANIAK_OAUTH", local.getProperty("infomaniakOAuth", "false"))
        buildConfigField("String", "INFOMANIAK_CLIENT_SECRET", "\"${local.getProperty("infomaniakClientSecret", "")}\"")
        applicationId = "ch.nthnbch.kalendar"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    // Release signing: reads keystore.properties at the project root (git-ignored, never committed).
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Fake account and events, installed next to the real app: used to take the README screenshots.
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += "debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.dmfs:lib-recur:0.17.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
