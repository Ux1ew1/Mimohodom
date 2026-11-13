plugins { id("com.android.application") }
android {
    namespace = "app.fastnotes"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    if (rootProject.file(".tools/ndk/android-ndk-r28c/source.properties").exists()) { ndkPath = rootProject.file(".tools/ndk/android-ndk-r28c").absolutePath }
    defaultConfig { applicationId = "app.fastnotes"; minSdk = 26; targetSdk = 35; versionCode = 33; versionName = "0.9.0"; testInstrumentationRunner = "app.fastnotes.SmokeInstrumentation"
        manifestPlaceholders["appLabel"] = "Мимоходом"
        ndk { abiFilters += listOf("arm64-v8a","x86_64") }
        externalNativeBuild { cmake { cppFlags += "-O3"; arguments += "-DANDROID_STL=c++_shared" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildTypes { release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    // FileProvider для снимка с камеры (раньше приходил транзитивно).
    implementation("androidx.core:core:1.9.0")
    // Gemini Nano через Google AICore (ML Kit GenAI Prompt API, бета).
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta5")
}
