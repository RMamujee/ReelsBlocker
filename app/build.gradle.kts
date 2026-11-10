plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Where SilentFeed is published (its download page, which the app checks for updates). Set
// rbSiteUrl in gradle.properties (yours or the project's) to change it.
val siteUrl = (findProperty("rbSiteUrl") as String?) ?: "https://silentfeed.vercel.app"

android {
    namespace = "com.rahil.reelsblocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rahil.reelsblocker"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.6.0"
        buildConfigField("String", "SITE_URL", "\"$siteUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Public builds are signed with the release key, kept outside the project (paths and
    // passwords in ~/.gradle/gradle.properties). Every update must be signed with the same
    // key, or Android refuses to install it over the old one: the key must never be lost.
    signingConfigs {
        val storeFile = findProperty("RB_STORE_FILE") as String?
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = findProperty("RB_STORE_PASSWORD") as String?
                keyAlias = findProperty("RB_KEY_ALIAS") as String?
                keyPassword = findProperty("RB_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
