// The API dumper every published Kotlin module's tests share (L16): one copy of the rules, and never
// published. Pure JVM; kotlin-reflect is what reads a declaration's Kotlin visibility, which the bytecode
// does not keep.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.reflect)
}
