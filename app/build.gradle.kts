import java.time.Duration

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
        versionCode = 4
        versionName = "0.3.0"
        resValue("string", "app_name", "alldoc")
    }
    packaging {
        resources.excludes +=
            setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/versions/**",
                "module-info.class",
            )
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
                .forEach { key ->
                    System.getProperty(key)?.let { value -> it.systemProperty(key, value) }
                }
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://repo.maven.apache.org/maven2",
            )
            it.systemProperty("user.home", testHome)
            it.doFirst { file(testHome).mkdirs() }
            it.maxHeapSize = "512m"
            // Native graphics allocations are outside the JVM heap; isolate each test class.
            it.forkEvery = 1
            it.maxParallelForks = 1
            it.timeout.set(Duration.ofMinutes(10))
            if(providers.gradleProperty("verboseTests").isPresent) it.testLogging.events("started", "failed", "skipped")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation("org.apache.poi:poi:5.5.1")
    implementation("org.apache.poi:poi-scratchpad:5.5.1")
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
