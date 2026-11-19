plugins { kotlin("jvm"); `maven-publish` }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
java { withSourcesJar() }
publishing { publications { create<MavenPublication>("core") { from(components["java"]); artifactId = "svga-core" } } }
