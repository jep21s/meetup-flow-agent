plugins {
  id("build-jvm")
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(kotlin("stdlib"))

  testImplementation(libs.bundles.junit)
}

tasks.test {
  useJUnitPlatform()
}
