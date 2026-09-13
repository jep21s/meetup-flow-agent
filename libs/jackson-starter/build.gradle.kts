plugins {
  id("build-jvm")
  id("idea-custom-plugin")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  api(libs.bundles.jackson)

  testImplementation(libs.bundles.junit)
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
