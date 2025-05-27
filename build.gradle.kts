// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    dependencies {
        classpath("com.google.gms:google-services:4.4.1")
    }
}

plugins {
    // If you use version catalogs, keep this line:
    alias(libs.plugins.android.application) apply false
    // Add other plugins as needed, e.g.:
    // alias(libs.plugins.android.library) apply false
    // alias(libs.plugins.kotlin.android) apply false
}