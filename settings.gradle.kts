pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Linphone SIP SDK Repository
        maven { url = uri("https://download.linphone.org/maven_repository") }
    }
}
rootProject.name = "Love"
include(":app")
