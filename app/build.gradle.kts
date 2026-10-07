plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.gtav.rpfmanager"
    compileSdk = 36
    ndkVersion = "27.3.13750724"
    providers.environmentVariable("ANDROID_NDK_ROOT").orNull?.let { ndkPath = it }
    defaultConfig {
        applicationId = "com.gtav.rpfmanager"
        minSdk = 27
        targetSdk = 36
        versionCode = 6
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildTypes {
        debug { applicationIdSuffix = providers.gradleProperty("rpfTestSuffix").orElse(".debug").get() }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    bundle { language { enableSplit = false } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets.getByName("main").jniLibs.srcDirs(layout.buildDirectory.dir("rustJni"))
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val buildRust by tasks.registering(Exec::class) {
    val bridge = rootProject.file("rust-core/jni-bridge")
    workingDir = bridge
    commandLine("bash", rootProject.file("scripts/build-rust.sh").absolutePath)
    inputs.files(fileTree(bridge.resolve("src")), bridge.resolve("Cargo.toml"), bridge.resolve("Cargo.lock"),
        fileTree(rootProject.file("rust-core/rpf-archive/src")), rootProject.file("rust-core/rpf-archive/Cargo.toml"), rootProject.file("rust-toolchain.toml"),
        rootProject.file("scripts/build-rust.sh"), rootProject.file("scripts/host-env.sh"), rootProject.file("scripts/android-env.sh"))
    outputs.file(layout.buildDirectory.file("rustJni/arm64-v8a/librpf_jni.so"))
    outputs.file(layout.buildDirectory.file("rustJni/arm64-v8a/libc++_shared.so"))
}
tasks.named("preBuild") { dependsOn(buildRust) }
