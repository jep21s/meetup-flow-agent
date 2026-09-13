plugins {
  id("build-jvm")
  id("idea-custom-plugin")
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  api(libs.bundles.logging)
}
