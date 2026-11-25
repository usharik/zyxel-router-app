import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// google-services.json в репозиторий не кладётся. Без него приложение собирается и работает, просто без Firebase.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// Ключ загрузки для Google Play: android/keystore.properties (storeFile, storePassword, keyAlias, keyPassword), вне Git.
val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("keystore.properties")
if (signingPropertiesFile.exists()) signingPropertiesFile.inputStream().use(signingProperties::load)

android {
    namespace = "com.usharik.routermanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.usharik.routermanager"
        minSdk = 29
        // Android 17 (target 37) requires a runtime local-network permission; target 36 keeps access to the router simple.
        // Google Play принимает target 36 для новых приложений и обновлений до 31.08.2027.
        targetSdk = 36
        versionCode = 8
        versionName = "1.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (signingPropertiesFile.exists()) create("upload") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Без ключа загрузки release подписывается debug-ключом только для локальной проверки (-PallowTestAds):
            // такую сборку можно поставить на эмулятор, а checkReleaseConfig не даст выпустить её без флага.
            (signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug").takeIf { providers.gradleProperty("allowTestAds").isPresent })
                ?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // Выбор языка приложения в настройках Android 13+; список берётся из values-*.
    androidResources { generateLocaleConfig = true }
    // Реальный org.json (ниже) используется в JVM-тестах вместо заглушки android.jar.
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// Release без настоящих идентификаторов AdMob, без Firebase или без подписи вышел бы молча испорченным: ни рекламы,
// ни отчётов о сбоях, ни возможности загрузить в Play. Для локальной проверки release-сборки: -PallowTestAds.
val checkReleaseConfig by tasks.registering {
    val adsFile = file("src/release/res/values/ads.xml")
    val firebaseFile = file("google-services.json")
    val signingFile = rootProject.file("keystore.properties")
    val allowTestAds = providers.gradleProperty("allowTestAds").isPresent
    inputs.file(adsFile)
    doLast {
        if (allowTestAds) return@doLast
        val problems = buildList {
            if (adsFile.readText().contains("3940256099942544")) add("в ${adsFile.path} тестовые идентификаторы AdMob")
            if (!firebaseFile.exists()) add("нет ${firebaseFile.path} (Firebase)")
            if (!signingFile.exists()) add("нет ${signingFile.path} (ключ загрузки)")
        }
        if (problems.isNotEmpty()) throw GradleException("Release не готов: ${problems.joinToString("; ")}. Для локальной проверки: -PallowTestAds.")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseConfig) }

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Firebase: без google-services.json SDK не инициализируется, Telemetry тогда ничего не отправляет.
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    // Реклама, согласие GDPR, покупка «без рекламы», оценка в Google Play.
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.google.android.play:review-ktx:2.0.2")
    // QR-код сети Wi-Fi: только кодировщик, без камеры и сканера.
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // настоящий JSON для JVM-тестов разбора
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
