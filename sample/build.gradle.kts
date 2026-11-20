plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.svganext.sample"
    compileSdk = 35
    defaultConfig { applicationId = "dev.svganext.sample"; minSdk = 21; targetSdk = 35; versionCode = 3; versionName = "0.1.2" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.github.yyued:SVGAPlayer-Android"))
            .using(project(":svga-compat"))
            .because("Simulate zero-code drop-in replacement of official svgaplayer in demo")
    }
}

dependencies {
    implementation(project(":svga-runtime"))
    // 模拟存量项目：声明依赖官方库坐标！通过上方的 dependencySubstitution 自动被替换为 :svga-compat
    implementation("com.github.yyued:SVGAPlayer-Android:2.6.1")
    implementation(project(":benchmark-upstream"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
