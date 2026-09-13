plugins {
  id("build-jvm")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(kotlin("stdlib"))

  testImplementation(libs.bundles.junit)
}

tasks.test {
  useJUnitPlatform()
}
