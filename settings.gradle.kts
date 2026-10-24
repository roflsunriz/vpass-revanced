rootProject.name = "vpass-revanced"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/revanced/revanced-patches-gradle-plugin")
            credentials {
                username = providers.gradleProperty("githubPackagesUsername").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                    ?: "token"
                password = providers.gradleProperty("githubPackagesPassword").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
                    ?: ""
            }
        }
    }
}

plugins {
    id("app.revanced.patches") version "1.0.0-dev.11"
}
