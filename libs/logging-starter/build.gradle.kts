plugins {
  id("build-jvm")
  id("idea-custom-plugin")
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  api(libs.bundles.logging)
}
