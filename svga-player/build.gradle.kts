plugins { id("com.android.library"); kotlin("android"); `maven-publish` }
android {
    namespace = "dev.svganext.player"
    compileSdk = 35
    defaultConfig { minSdk = 21; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin {
    jvmToolchain(17)
    compilerOptions { freeCompilerArgs.add("-Xjvm-default=all-compatibility") }
}
dependencies {
    api("com.squareup.wire:wire-runtime:4.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "svga-player" } } }
}
