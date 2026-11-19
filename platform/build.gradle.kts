plugins { kotlin("jvm") }

dependencies {
    // Unpacks the Skiko native libraries that runtime bundles store xz-compressed.
    implementation(libs.xz)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.serialization.core)
}
