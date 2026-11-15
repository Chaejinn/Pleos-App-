import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseProperties = Properties().apply {
    val propertiesFile = rootProject.file(".local/release-signing.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
val kakaoProperties = Properties().apply {
    val file = rootProject.file(".local/kakao.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val envProperties = Properties().apply {
    val file = rootProject.file(".env")
    if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
}
fun String.envValue(): String = trim().removeSurrounding("\"").removeSurrounding("'").trim()
val kakaoNativeAppKey = providers.environmentVariable("KAKAO_NATIVE_APP_KEY")
    .orElse(envProperties.getProperty("KAKAO_NATIVE_APP_KEY", kakaoProperties.getProperty("nativeAppKey", ""))).get().envValue()
require(kakaoNativeAppKey.isEmpty() || kakaoNativeAppKey.matches(Regex("[A-Za-z0-9]+"))) {
    "Invalid Kakao native app key format"
}
val kakaoJavascriptAppKey = providers.environmentVariable("KAKAO_JAVASCRIPT_APP_KEY")
    .orElse(envProperties.getProperty("KAKAO_JAVASCRIPT_APP_KEY", "")).get().envValue()
require(kakaoJavascriptAppKey.isEmpty() || kakaoJavascriptAppKey.matches(Regex("[A-Za-z0-9]+"))) {
    "Invalid Kakao JavaScript app key format"
}

android {
    namespace = "com.pleos.explorer"
    compileSdk = 34
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "com.roadlog.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoNativeAppKey\"")
        buildConfigField("String", "KAKAO_JAVASCRIPT_APP_KEY", "\"$kakaoJavascriptAppKey\"")
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }

    signingConfigs {
        if (releaseProperties.isNotEmpty()) {
            create("localRelease") {
                storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.kakaomap)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    annotationProcessor(libs.room.compiler)
    implementation(libs.gson)
    implementation(project(":domain"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navi)
    implementation(libs.pleos.fused)
    implementation(libs.pleos.tts)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
