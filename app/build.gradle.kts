plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "org.smallthingz.alldoc"
    compileSdk = 37
    defaultConfig { applicationId = "org.smallthingz.alldoc"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1.0" }
    buildFeatures { compose = true; aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha15")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
}
