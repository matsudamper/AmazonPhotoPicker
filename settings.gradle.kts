pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven {
            url = uri("https://maven.mozilla.org/maven2")
        }
        mavenCentral()
    }
}
rootProject.name = "AmazonPhotoPicker"
include(":app")
