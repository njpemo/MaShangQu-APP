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
    }
}

rootProject.name = "IdCodeAgg"

include(":app")
include(":core:model")
include(":core:network")
include(":core:storage")
include(":core:webview")
include(":core:yyb")
include(":platform:api")
include(":platform:pdd")
include(":platform:taobao")
include(":platform:jd")
include(":platform:sf")
include(":platform:ryll")
