plugins { kotlin("jvm") }

dependencies {
    api(project(":platform"))
    api(libs.skiko.standard)
}
