plugins {
    id("com.android.application")
}

// The app uses only the Android framework: Camera2, MediaCodec, OpenGL ES, AudioRecord and AudioTrack.
android {
    namespace = "io.github.dogkeeper886.cleancam"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dogkeeper886.cleancam"
        // Android 13: preview stabilization (API 33), the camera's zoom ratio (API 30) and the audio route (API 31).
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
