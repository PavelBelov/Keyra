plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("app.keemobile:kotpass:0.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
