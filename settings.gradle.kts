pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "svga-next"
include(":svga-core", ":svga-player", ":svga-runtime", ":svga-compat", ":benchmark-upstream", ":sample")
