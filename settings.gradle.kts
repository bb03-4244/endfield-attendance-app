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
        mavenCentral() // 이 줄만 있으면 Rhino를 가져오는 데 문제없습니다!
    }
}

rootProject.name = "EndfieldAssistant"
include(":app")