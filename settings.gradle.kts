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
        // karoo-ext se objavlja na GitHub Packages in ZAHTEVA prijavo,
        // tudi ce je paket javen. Prijavni podatki se berejo v tem vrstnem redu:
        //   1. local.properties (lokalni build):  gpr.user=... / gpr.key=...
        //   2. okoljski spremenljivki GPR_USER / GPR_KEY (GitHub Actions)
        //   3. GITHUB_ACTOR / GITHUB_TOKEN (samodejni token v Actions)
        // Token mora biti classic z obsegom read:packages.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GPR_USER")
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GPR_KEY")
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

rootProject.name = "karoo-arso-radar"
include(":app")
