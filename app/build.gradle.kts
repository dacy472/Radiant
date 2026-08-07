plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.radiant.webtoon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.radiant.webtoon"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    
    lint {
        abortOnError = false
    }


    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Room — KSP (not kapt) for the compiler: kapt's javac metadata reader caps below
    // the metadata version Kotlin 2.4.10 emits, so annotation processing fails under kapt.
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Networking — OkHttp 5.4.x aligned with TachiyomiX 1.6
    val okhttpVersion = "5.4.0"
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:$okhttpVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-brotli:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-zstd:$okhttpVersion")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Jsoup — HTML parsing for scraping manga sites
    implementation("org.jsoup:jsoup:1.22.2")

    // TachiyomiX stubs: optional compileOnly drift check when available on JitPack
    // compileOnly("com.github.mihonapp:tachiyomix:1.6")

    // RxJava — required for Tachiyomi extension Source interface compatibility
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("io.reactivex:rxandroid:1.2.1")

    // Gson — JSON serialization (standalone, no Retrofit converter)
    implementation("com.google.code.gson:gson:2.10.1")

    // Coil (Image Loading)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Service Locator for Tachiyomi extensions
    implementation("uy.kohesive.injekt:injekt-core:1.16.1")

    // Preferences for ConfigurableSource
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Kotlin Serialization — version must be >= what current Keiyoushi/Mihon extensions are
    // compiled against (currently 1.11.0 / Kotlin 2.4.10), or their generated $serializer
    // classes throw AbstractMethodError on GeneratedSerializer.typeParametersSerializers()
    // when loaded into an older host runtime via DexClassLoader.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
}

// Keep Kotlin stdlib aligned with the compiler (OkHttp 5 may pull a newer one)
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.4.10")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.4.10")
    }
}
