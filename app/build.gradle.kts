import java.util.Properties

plugins { id("com.android.application"); kotlin("android") }

val appInfo = Properties().apply {
    rootProject.file("app-info.properties").reader(Charsets.UTF_8).use { load(it) }
}
fun appInfoValue(key: String): String = appInfo.getProperty(key)?.trim().orEmpty()
val publicApplicationId = appInfoValue("application.id")
require(publicApplicationId.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
    "Invalid application.id in app-info.properties"
}
require(appInfoValue("application.name").isNotBlank()) { "application.name must not be empty" }

android {
    namespace = "app.keyra"
    compileSdk = 36
    // Pin the resource/packaging toolchain for F-Droid reproducibility.
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = publicApplicationId
        minSdk = 30
        targetSdk = 36
        versionCode = appInfoValue("application.version.code").toInt().also { require(it > 0) }
        versionName = appInfoValue("application.version.name").also { require(it.isNotBlank()) }
        mapOf(
            "app_name" to "application.name",
            "app_developer_name" to "developer.name",
            "app_project_website" to "project.website",
            "app_source_url" to "project.source",
            "app_issues_url" to "project.issues",
            "app_license_spdx" to "license.spdx",
            "app_support_email" to "support.email",
            "app_privacy_url" to "privacy.url"
        ).forEach { (resource, key) -> resValue("string", resource, appInfoValue(key)) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { viewBinding = true }
    // Language changes work offline, including installations from an App Bundle.
    bundle { language { enableSplit = false } }
    buildTypes { release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = providers.gradleProperty("androidTestBuildType").getOrElse("debug")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation(kotlin("test-junit"))
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("app.keemobile:kotpass:0.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("androidx.biometric:biometric:1.1.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
