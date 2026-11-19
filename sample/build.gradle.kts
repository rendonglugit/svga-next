plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.readablesvga.sample"
    compileSdk = 35
    defaultConfig { applicationId = "dev.readablesvga.sample"; minSdk = 21; targetSdk = 35; versionCode = 1; versionName = "0.1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":svga-runtime"))
    implementation(project(":benchmark-upstream"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
