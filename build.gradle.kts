import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library") version "8.10.1" apply false
    id("com.android.application") version "8.10.1" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
}
allprojects {
    group = "io.github.rendonglugit"
    version = "0.1.1"
}

subprojects {
    plugins.withId("maven-publish") {
        apply(plugin = "signing")

        val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
            archiveClassifier.set("javadoc")
        }

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                artifact(emptyJavadocJar)
                pom {
                    name.set("SVGA-Next - ${project.name}")
                    description.set("Next-generation Android SVGA playback and independently implemented resource ownership")
                    url.set("https://github.com/rendonglugit/svga-next")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("rendonglu")
                            name.set("Rendong Lu")
                            url.set("https://github.com/rendonglugit")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/rendonglugit/svga-next.git")
                        developerConnection.set("scm:git:ssh://github.com/rendonglugit/svga-next.git")
                        url.set("https://github.com/rendonglugit/svga-next")
                    }
                }
            }
            repositories {
                maven {
                    name = "staging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
                }
            }
        }

        extensions.configure<org.gradle.plugins.signing.SigningExtension> {
            val signingKey = System.getenv("SIGNING_KEY") ?: (project.findProperty("signing.key") as? String)
            val signingPassword = System.getenv("SIGNING_PASSWORD") ?: (project.findProperty("signing.password") as? String)
            if (!signingKey.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                val publications = extensions.getByType<PublishingExtension>().publications
                sign(publications)
            }
        }
    }
}
