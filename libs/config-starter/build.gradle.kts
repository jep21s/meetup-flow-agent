plugins {
  id("build-jvm")
  id("idea-custom-plugin")
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.logging.kotlin)

  testImplementation(libs.bundles.junit)
  testRuntimeOnly(libs.logback)
}

tasks.test {
  jvmArgs = listOf(
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.math=ALL-UNNAMED"
  )
  useJUnitPlatform()
}
