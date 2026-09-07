plugins {
    id("com.android.application")
}

android {
    namespace = "com.haseeb.mediadownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haseeb.mediadownloader"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.5-beta"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core:1.16.0")

    // youtubedl-android 0.18.1 is compiled against Kotlin 1.7.22, while
    // newer AndroidX dependencies bring kotlin-stdlib 1.8.22. Kotlin 1.8
    // merged the old jdk7/jdk8 stdlib artifacts into kotlin-stdlib, so a
    // mixed 1.7/1.8 graph causes duplicate classes. The BOM aligns all
    // Kotlin stdlib variants to 1.8.22 (the jdk7/jdk8 artifacts at 1.8.22
    // are compatibility shims and no longer duplicate the merged classes).
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
