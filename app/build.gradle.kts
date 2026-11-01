import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val uploadSigningFile = rootProject.file("keystore.properties")
val uploadSigning = Properties().apply {
    if (uploadSigningFile.exists()) uploadSigningFile.inputStream().use { load(it) }
}
android {
    namespace = "io.prendie.weazlcloud"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.prendie.weazlcloud"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        if (uploadSigningFile.exists()) create("upload") {
            fun setting(name: String) = requireNotNull(uploadSigning.getProperty(name)) {
                "Missing $name in local keystore.properties"
            }.also { require(it.isNotBlank()) { "Empty signing setting: $name" } }
            storeFile = rootProject.file(setting("storeFile"))
            storePassword = setting("storePassword")
            keyAlias = setting("keyAlias")
            keyPassword = setting("keyPassword")
            storeType = "PKCS12"
        }
    }
    buildTypes { release {
        isMinifyEnabled = false
        if (uploadSigningFile.exists()) signingConfig = signingConfigs.getByName("upload")
    } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.work:work-testing:2.10.5")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
