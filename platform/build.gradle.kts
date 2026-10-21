plugins { kotlin("jvm") }

dependencies {
    // Unpacks the Skiko native libraries that runtime bundles store xz-compressed.
    implementation(libs.xz)
}
