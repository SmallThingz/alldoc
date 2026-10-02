plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

val testHome =
  providers
    .gradleProperty("testUserHome")
    .orElse(layout.buildDirectory.dir("test-home").map { it.asFile.path })
    .get()

android {
  namespace = "org.smallthingz.alldoc"
  compileSdk = 37
  defaultConfig {
    applicationId = "org.smallthingz.alldoc"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    resValue("string","app_name","alldoc")
  }
  buildFeatures {
    compose = true
    aidl = true
    resValues = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions {
    unitTests.isIncludeAndroidResources = true
    unitTests.all {
      listOf(
          "https.proxyHost",
          "https.proxyPort",
          "http.proxyHost",
          "http.proxyPort",
          "javax.net.ssl.trustStore",
        )
        .forEach { key -> System.getProperty(key)?.let { value -> it.systemProperty(key, value) } }
      it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
      it.systemProperty("user.home", testHome)
      it.doFirst { file(testHome).mkdirs() }
      it.maxHeapSize = "1g"
    }
  }
  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    create("preview") {
      initWith(getByName("release"))
      signingConfig = getByName("debug").signingConfig
      applicationIdSuffix = ".preview"
      versionNameSuffix = "-preview"
      matchingFallbacks += listOf("release")
      resValue("string", "app_name", "alldoc Preview")
    }
  }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
  implementation("androidx.activity:activity-compose:1.13.0")
  implementation(platform("androidx.compose:compose-bom:2026.03.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3:1.4.0")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.17")
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
}
