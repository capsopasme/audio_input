plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin by itself ("built-in Kotlin"); declaring the Kotlin plugin here only
    // pins the Kotlin compiler version it uses.
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
