plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseVersion = providers.fileContents(rootProject.layout.projectDirectory.file("VERSION"))
    .asText.get().trimEnd('\r', '\n')
val versionParts = Regex("""(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})""")
    .matchEntire(releaseVersion)?.groupValues?.drop(1)?.map(String::toInt)
    ?: throw GradleException("VERSION должен содержать X.Y.Z без ведущих нулей (X до 2099, Y и Z до 999).")
val (major, minor, patch) = versionParts
require(major <= 2099 && versionParts.any { it != 0 }) { "Недопустимая версия в VERSION." }
val releaseCode = major * 1_000_000 + minor * 1_000 + patch
val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val signingPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull

android {
    namespace = "ru.polka.reader"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.polka.reader"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseCode
        versionName = releaseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("publication") {
            storeFile = keystorePath?.let { file(it) }
            storePassword = keystorePassword
            keyAlias = signingAlias
            keyPassword = signingPassword
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName("publication") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val compose = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(compose)
    androidTestImplementation(compose)
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
