pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://plugins.gradle.org/m2/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.dmulloy2.net/repository/public/")
    }
}

rootProject.name = "ShadowCore"
