plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.vehicletracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.vehicletracker"
        minSdk = 26          // Android 8.0 এর নিচে সাপোর্ট নেই
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true   // XML থেকে সরাসরি view ব্যবহার করতে সাহায্য করে
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // GPS (Google Play Services দরকার; না থাকলে অ্যাপ লোকেশন দিতে পারবে না)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Firebase: BoM মানে সব Firebase-এর version একসাথে মিলে থাকবে
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // .await() ব্যবহার করতে (Firebase কাজের জন্য সহজ syntax)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
