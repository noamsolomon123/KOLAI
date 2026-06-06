// Root build file for the KOLAI multi-module project.
// Plugins are declared here with `apply false` so subprojects can apply them
// from the version catalog without re-declaring versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
