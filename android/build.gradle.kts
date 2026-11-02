plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Pre-approved in ROADMAP-NEXTGEN.md for screenshot-testing new screens (P1.7)
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
    // KSP for Room's annotation processor (pre-approved: Room is explicitly named in
    // ROADMAP-NEXTGEN.md P2). Version is pinned to this project's Kotlin version (1.9.24).
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
