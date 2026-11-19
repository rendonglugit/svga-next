plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.readablesvga.upstream"
    compileSdk = 35
    defaultConfig { minSdk = 21; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies { api("com.squareup.wire:wire-runtime:4.4.1") }
