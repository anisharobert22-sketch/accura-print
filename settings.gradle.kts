pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // DantSu ESC/POS is published on JitPack, not Maven Central.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "AccuraPrint"
include(":app")
