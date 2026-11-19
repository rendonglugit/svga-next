import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library") version "8.10.1" apply false
    id("com.android.application") version "8.10.1" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
}
allprojects {
    group = "dev.readablesvga"
    version = "0.1.0"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("Readable SVGA - ${project.name}")
                    description.set("Readable Android SVGA playback and independently implemented resource ownership")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            distribution.set("repo")
                        }
                    }
                }
            }
            repositories {
                maven {
                    name = "validation"
                    url = uri(rootProject.layout.buildDirectory.dir("repository"))
                }
            }
        }
    }
}
